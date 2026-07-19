package de.tubyoub.velocitypteropower.lifecycle;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import de.tubyoub.velocitypteropower.VelocityPteroPower;
import de.tubyoub.velocitypteropower.http.PanelAPIClient;
import de.tubyoub.velocitypteropower.http.PowerSignal;
import de.tubyoub.velocitypteropower.manager.ConfigurationManager;
import de.tubyoub.velocitypteropower.manager.MessageKey;
import de.tubyoub.velocitypteropower.manager.MessagesManager;
import de.tubyoub.velocitypteropower.model.PteroServerInfo;
import de.tubyoub.velocitypteropower.util.RateLimitTracker;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Manages the lifecycle of servers related to automatic shutdowns,
 * including scheduling, checking, and retrying stop commands.
 */
public class ServerLifecycleManager {

  private final ProxyServer proxyServer;
  private final VelocityPteroPower plugin;
  private final ComponentLogger logger;
  private final ConfigurationManager configurationManager;
  private final MessagesManager messages;
  private final PanelAPIClient apiClient;
  private final RateLimitTracker rateLimitTracker;
  private final Map<String, PteroServerInfo> serverInfoMap;

  // State managed by this class
  private final Map<String, Integer> shutdownRetryCounts = new ConcurrentHashMap<>();
  // Track scheduled shutdown tasks centrally to avoid duplicates between listeners and periodic sweep
  private final Map<String, ScheduledTask> scheduledShutdowns = new ConcurrentHashMap<>();

  /**
   * Constructor for ServerLifecycleManager.
   *
   * @param proxyServer Velocity ProxyServer instance.
   * @param plugin Plugin instance.
   */
  public ServerLifecycleManager(ProxyServer proxyServer, VelocityPteroPower plugin) {
    this.proxyServer = proxyServer;
    this.plugin = plugin;
    this.logger = plugin.getFilteredLogger();
    this.configurationManager = plugin.getConfigurationManager();
    this.messages = plugin.getMessagesManager();
    this.apiClient = plugin.getApiClient();
    this.rateLimitTracker = plugin.getRateLimitTracker();
    this.serverInfoMap = plugin.getServerInfoMap();
  }

  /**
   * Schedules a task to shut down a server after a specified timeout if it remains empty.
   * This method contains the logic executed by the scheduled task.
   *
   * @param serverName The name of the server.
   * @param serverId The panel ID of the server.
   * @param timeoutSeconds The delay in seconds before checking for emptiness and shutting down. Negative values disable shutdown.
   * @return The scheduled task, or null if disabled by configuration.
   */
  public ScheduledTask scheduleServerShutdown(String serverName, String serverId, int timeoutSeconds) {
    // Always-online servers must never be auto-shutdown, regardless of per-server timeout
    var alwaysOnline = configurationManager.getAlwaysOnlineList();
    if (alwaysOnline != null && alwaysOnline.stream().anyMatch(s -> s.equalsIgnoreCase(serverName))) {
      logger.debug("Skipping shutdown scheduling for always-online server '{}'.", serverName);
      // Ensure no TTL is shown for this server
      plugin.getShutdownDeadlines().remove(serverName);
      return null;
    }

    if (timeoutSeconds < 0) {
      logger.debug("Automatic shutdown disabled for server '{}' (timeout < 0).", serverName);
      return null;
    }

    // Informative log with MiniMessage template (rendered to plain in console, keeps consistent style)
    Component scheduledMsg =
        messages.mm(
            MessageKey.SERVER_SHUTDOWN_SCHEDULED,
            Map.of("server", serverName, "timeout", String.valueOf(timeoutSeconds)));
    logger.info(scheduledMsg);

    return proxyServer
        .getScheduler()
        .buildTask(
            plugin,
            () -> {
              // Clear tracking and any displayed deadline when the scheduled check fires
              scheduledShutdowns.remove(serverName);
              plugin.getShutdownDeadlines().remove(serverName);
              if (plugin.getPlayerConnectionHandler() != null
                  && !plugin.getPlayerConnectionHandler().isSafeToIdleStop(serverName)) {
                logger.info(
                    "Shutdown task for '{}' skipped — players are waiting for this server.",
                    serverName);
                return;
              }
              // Check emptiness and rate limit before sending stop
              boolean empty = apiClient.isServerEmpty(serverName);
              if (rateLimitTracker.canMakeRequest() && empty) {
                if (apiClient.isServerOnline(serverName, serverId)) {
                  // Log power action using MiniMessage template
                  logger.info(
                      messages.mm(
                          MessageKey.POWER_ACTION_SENT,
                          Map.of("action", "stop", "server", serverName)));
                  apiClient.powerServer(serverId, PowerSignal.STOP);
                  shutdownRetryCounts.put(serverName, 0);
                  scheduleShutdownConfirmationCheck(serverName, serverId);
                } else {
                  logger.debug(
                      "Shutdown task for '{}' executed, but server was already offline.",
                      serverName);
                }
              } else {
                if (!empty) {
                  logger.info(messages.mm(MessageKey.SERVER_SHUTDOWN_CANCELLED_PLAYERS, Map.of("server", serverName)));
                } else {
                  logger.warn("Shutdown check for {} skipped due to rate limit.", serverName);
                }
              }
            })
        .delay(timeoutSeconds, TimeUnit.SECONDS)
        .schedule();
  }

  public boolean hasScheduledShutdown(String serverName) {
    return scheduledShutdowns.containsKey(serverName);
  }

  public void cancelScheduledShutdown(String serverName, String reason) {
    ScheduledTask existing = scheduledShutdowns.remove(serverName);
    if (existing != null) {
      existing.cancel();
      clearRetryCount(serverName);
      plugin.getShutdownDeadlines().remove(serverName);
      logger.info(
          messages.mm(
              MessageKey.SERVER_SHUTDOWN_CANCELLED,
              Map.of("server", serverName, "reason", reason)));
    } else {
      logger.debug("No pending shutdown task found for server '{}' to cancel.", serverName);
    }
  }

  public void checkAndScheduleShutdownIfNeeded(String serverName, PteroServerInfo serverInfo) {
    if (hasScheduledShutdown(serverName)) {
      logger.debug("Shutdown check for '{}': Task already pending.", serverName);
      return;
    }

    var alwaysOnline = configurationManager.getAlwaysOnlineList();
    if (alwaysOnline != null && alwaysOnline.stream().anyMatch(s -> s.equalsIgnoreCase(serverName))) {
      logger.debug("Always-online is set for '{}'; skipping idle shutdown scheduling.", serverName);
      plugin.getShutdownDeadlines().remove(serverName);
      return;
    }

    // Only schedule a shutdown when the server is actually ONLINE
    boolean online = false;
    try {
      online = apiClient.isServerOnline(serverName, serverInfo.getServerId());
    } catch (Exception ex) {
      logger.debug("Online check failed for '{}': {}", serverName, ex.toString());
      online = false;
    }

    if (!online) {
      logger.debug("Server '{}' is offline or unknown. Skipping idle shutdown scheduling.", serverName);
      plugin.getShutdownDeadlines().remove(serverName);
      return;
    }

    if (apiClient.isServerEmpty(serverName)) {
      if (plugin.getPlayerConnectionHandler() != null
          && !plugin.getPlayerConnectionHandler().isSafeToIdleStop(serverName)) {
        logger.debug("Server '{}' is empty but has waiters. Skipping idle shutdown.", serverName);
        return;
      }
      logger.debug("Server '{}' is empty. Scheduling shutdown.", serverName);
      ScheduledTask shutdownTask = scheduleServerShutdown(serverName, serverInfo.getServerId(), serverInfo.getTimeout());
      if (shutdownTask != null) {
        scheduledShutdowns.put(serverName, shutdownTask);
        plugin.getShutdownDeadlines().put(serverName, System.currentTimeMillis() + (serverInfo.getTimeout() * 1000L));
      }
    } else {
      logger.debug("Server '{}' is not empty. No shutdown needed.", serverName);
    }
  }

  /**
   * Schedules a follow-up task to check if a server actually stopped after a stop signal was sent.
   * Retries stopping the server if it's still online and empty, up to a configured limit.
   *
   * @param serverName The name of the server.
   * @param serverId The panel ID of the server.
   */
  private void scheduleShutdownConfirmationCheck(String serverName, String serverId) {
    long retryDelay = configurationManager.getShutdownRetryDelay();

    proxyServer
        .getScheduler()
        .buildTask(
            plugin,
            new Runnable() {
              @Override
              public void run() {
                if (!rateLimitTracker.canMakeRequest()) {
                  logger.debug("Could not confirm shutdown status for {} due to rate limit. Rescheduling.", serverName);
                  reschedule();
                  return;
                }

                if (apiClient.isServerOnline(serverName, serverId)) {
                  int currentRetries = shutdownRetryCounts.getOrDefault(serverName, 0);
                  int maxRetries = configurationManager.getShutdownRetries();

                  if (currentRetries < maxRetries) {
                    // Check emptiness again before retrying stop
                    if (apiClient.isServerEmpty(serverName)) {
                      int nextRetry = currentRetries + 1;
                      shutdownRetryCounts.put(serverName, nextRetry);

                      logger.warn(
                          messages.mm(
                              MessageKey.SERVER_STILL_ONLINE_RETRYING,
                              Map.of(
                                  "server", serverName,
                                  "retry", String.valueOf(nextRetry),
                                  "maxretries", String.valueOf(maxRetries))));

                      apiClient.powerServer(serverId, PowerSignal.STOP);
                      scheduleShutdownConfirmationCheck(serverName, serverId);
                    } else {
                      // Players came back — cancel shutdown process
                      logger.info(
                          messages.mm(MessageKey.SERVER_SHUTDOWN_CANCELLED_PLAYERS, Map.of("server", serverName)));
                      shutdownRetryCounts.remove(serverName);
                    }
                  } else {
                    // Max retries reached
                    logger.error(
                        messages.mm(
                            MessageKey.SERVER_SHUTDOWN_FAILED,
                            Map.of("server", serverName, "retry", String.valueOf(maxRetries))));
                    shutdownRetryCounts.remove(serverName);
                  }
                } else {
                  // Server is offline, shutdown successful
                  logger.info(messages.mm(MessageKey.SERVER_SHUTDOWN_SUCCESS, Map.of("server", serverName)));
                  shutdownRetryCounts.remove(serverName);
                }
              }

              private void reschedule() {
                proxyServer
                    .getScheduler()
                    .buildTask(plugin, this)
                    .delay(retryDelay, TimeUnit.SECONDS)
                    .schedule();
              }
            })
        .delay(retryDelay, TimeUnit.SECONDS)
        .schedule();
  }

  /**
   * Clears the shutdown retry count for a server, typically called when a shutdown is cancelled.
   * @param serverName The name of the server.
   */
  public void clearRetryCount(String serverName) {
    shutdownRetryCounts.remove(serverName);
  }

  /**
   * Counts the number of currently online managed servers, excluding any provided names.
   * If PANEL_API is used and rate limited, returns -1 to indicate unknown.
   */
  public void scheduleIdleShutdownSweep() {
    int interval = configurationManager.getIdleShutdownCheckInterval();
    if (interval <= 0) {
      logger.debug("Idle shutdown sweep disabled (interval <= 0).");
      return;
    }

    Runnable task = new Runnable() {
      @Override public void run() {
        try {
          for (Map.Entry<String, PteroServerInfo> e : serverInfoMap.entrySet()) {
            String name = e.getKey();
            PteroServerInfo info = e.getValue();
            if (info == null) continue;

            // Always-online override
            var alwaysOnline = configurationManager.getAlwaysOnlineList();
            if (alwaysOnline != null && alwaysOnline.stream().anyMatch(s -> s.equalsIgnoreCase(name))) {
              plugin.getShutdownDeadlines().remove(name);
              continue;
            }

            int timeout = info.getTimeout();
            if (timeout < 0) continue; // disabled per-server

            if (hasScheduledShutdown(name)) {
              continue; // already pending
            }

            // Only schedule when online
            boolean online = false;
            try {
              online = apiClient.isServerOnline(name, info.getServerId());
            } catch (Exception ex) {
              logger.debug("Idle sweep: online check failed for '{}': {}", name, ex.toString());
              online = false;
            }
            if (!online) {
              logger.debug("Idle sweep: '{}' is offline or unknown. Skipping scheduling.", name);
              plugin.getShutdownDeadlines().remove(name);
              continue;
            }

            if (apiClient.isServerEmpty(name)) {
              if (plugin.getPlayerConnectionHandler() != null
                  && !plugin.getPlayerConnectionHandler().isSafeToIdleStop(name)) {
                logger.debug("Idle sweep: '{}' has waiters — skipping shutdown schedule.", name);
                continue;
              }
              logger.debug("[DEBUG] Idle sweep: scheduling shutdown for '{}' (timeout={}s)", name, timeout);
              ScheduledTask t = scheduleServerShutdown(name, info.getServerId(), timeout);
              if (t != null) {
                scheduledShutdowns.put(name, t);
                plugin.getShutdownDeadlines().put(name, System.currentTimeMillis() + timeout * 1000L);
              }
            }
          }
        } catch (Exception ex) {
          logger.error("Error in idle shutdown sweep: {}", ex.toString());
        } finally {
          reschedule();
        }
      }

      private void reschedule() {
        proxyServer.getScheduler().buildTask(plugin, this).delay(interval, TimeUnit.SECONDS).schedule();
      }
    };

    proxyServer.getScheduler().buildTask(plugin, task).delay(interval, TimeUnit.SECONDS).schedule();
    logger.info("Scheduled idle shutdown sweep every {} seconds.", interval);
  }

  public int countOnlineServersExcluding(Set<String> exempt) {
    var method = configurationManager.getServerCheckMethod();
    if (method == ConfigurationManager.ServerCheckMethod.PANEL_API && !rateLimitTracker.canMakeRequest()) {
      logger.warn("Skipping online server count due to API rate limiting.");
      return -1; // unknown due to rate limit
    }
    int count = 0;
    for (Map.Entry<String, PteroServerInfo> e : serverInfoMap.entrySet()) {
      String name = e.getKey();
      if (exempt != null && exempt.contains(name)) continue;
      PteroServerInfo info = e.getValue();
      try {
        if (apiClient.isServerOnline(name, info.getServerId())) {
          count++;
        }
      } catch (Exception ex) {
        logger.debug("Error checking online status for {}: {}", name, ex.toString());
      }
    }
    return count;
  }

  /**
   * Periodically ensures that configured servers are kept online.
   */
  public void scheduleAlwaysOnlineMaintenance() {
    int interval = configurationManager.getAlwaysOnlineCheckInterval();
    var keepOnline = configurationManager.getAlwaysOnlineList();
    if (interval <= 0 || keepOnline == null || keepOnline.isEmpty()) {
      logger.debug("Always-online keeper disabled or no servers configured.");
      return;
    }

    Runnable task = new Runnable() {
      @Override public void run() {
        try {
          var method = configurationManager.getServerCheckMethod();
          boolean canQuery = method == ConfigurationManager.ServerCheckMethod.VELOCITY_PING || rateLimitTracker.canMakeRequest();
          if (!canQuery) {
            logger.debug("Always-online check skipped due to API rate limiting.");
          } else {
            for (String name : keepOnline) {
              PteroServerInfo info = serverInfoMap.get(name);
              if (info == null) {
                logger.warn("Always-online server '{}' not found in configuration.", name);
                continue;
              }
              String id = info.getServerId();
              boolean online = apiClient.isServerOnline(name, id);
              if (!online) {
                if (plugin.getMaintenanceService() != null
                    && plugin.getMaintenanceService().isServerInMaintenance(name)) {
                  logger.debug("Always-online keeper: '{}' skipped (maintenance mode).", name);
                  continue;
                }
                logger.info(messages.mm(MessageKey.POWER_ACTION_SENT, Map.of("action", "start", "server", name)));
                apiClient.powerServer(id, PowerSignal.START);
                plugin.recordServerStartSignalSent();
              }
            }
          }
        } catch (Exception ex) {
          logger.error("Error in always-online maintenance: {}", ex.toString());
        } finally {
          reschedule();
        }
      }

      private void reschedule() {
        proxyServer.getScheduler().buildTask(plugin, this).delay(interval, TimeUnit.SECONDS).schedule();
      }
    };

    // schedule first run
    proxyServer.getScheduler().buildTask(plugin, task).delay(interval, TimeUnit.SECONDS).schedule();
    logger.info("Scheduled always-online keeper every {} seconds for {} server(s).", interval, keepOnline.size());
  }
}