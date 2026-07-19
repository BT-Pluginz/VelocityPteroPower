package dev.example.vppaddons;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import de.tubyoub.vpp.api.*;
import de.tubyoub.vpp.api.event.PlayerPostConnectEvent;
import de.tubyoub.vpp.api.event.VPPEvent;
import de.tubyoub.vpp.api.event.VPPEventBus;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;

import java.net.URI;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;

@Plugin(id = "vpp-addon-sample", name = "VPP Addon Sample", version = "1.0.0")
public final class SampleAddon {

    private VPPApi api;
    private AddonConfig cfg;
    private ComponentLogger logger;

    // Keep reference so we can unregister
    private de.tubyoub.vpp.api.routing.RoutingProvider registeredRouter;
    private AutoCloseable eventSubHandle;

    @Inject
    public SampleAddon(ComponentLogger logger) {
        this.logger = logger;
    }

    // 1) Minimal AddonCommand: /ptero greet [name]
    public static final class GreetCmd implements AddonCommand {
        @Override public String name() { return "greet"; }
        @Override public List<String> aliases() { return List.of("hello"); }
        @Override public String description() { return "Greets a player (example addon)"; }
        @Override public String permission() { return "vpp.sample.greet"; }
        @Override public void execute(CommandActor actor, String[] args) {
            String who = (args != null && args.length > 0) ? args[0] : actor.getName();
            actor.sendMessage("Hello, " + who + "! (from VPP sample addon)");
        }
    }

    // 2) Command showing API info and demonstrating dispatch/unregister
    public final class InfoCmd implements AddonCommand {
        @Override public String name() { return "info"; }
        @Override public List<String> aliases() { return List.of("about", "api"); }
        @Override public String description() { return "Shows VPP API info and demos dispatch/unregister"; }
        @Override public String permission() { return "vpp.sample.info"; }
        @Override public void execute(CommandActor actor, String[] args) {
            actor.sendMessage("VPP API version: " + api.getApiVersion());
            actor.sendMessage("Registered addon commands: " + api.getRegisteredCommands().size());
            // Dispatch demo (redispatch to greet without direct call)
            api.dispatchSubcommand("greet", actor, new String[]{actor.getName()});
            // Temporary register/unregister demo
            AddonCommand temp = new AddonCommand() {
                @Override public String name() { return "temp"; }
                @Override public List<String> aliases() { return List.of(); }
                @Override public String description() { return "temporary command"; }
                @Override public String permission() { return ""; }
                @Override public void execute(CommandActor a, String[] a2) { a.sendMessage("temp executed"); }
            };
            api.registerCommand(temp);
            boolean executed = api.dispatchSubcommand("temp", actor, new String[0]);
            actor.sendMessage("Temp dispatch worked: " + executed);
            api.unregisterCommand("temp");
            actor.sendMessage("Temp command unregistered.");
        }
    }

    // 3) Command to play with AddonConfig
    public final class ConfigCmd implements AddonCommand {
        @Override public String name() { return "cfg"; }
        @Override public List<String> aliases() { return List.of("config"); }
        @Override public String description() { return "Demonstrates AddonConfig get/set/save/reload"; }
        @Override public String permission() { return "vpp.sample.cfg"; }
        @Override public void execute(CommandActor actor, String[] args) {
            cfg.ensureDefaults(Map.of(
                    "routingEnabled", true,
                    "defaultLobby", "lobby",
                    "allowHttpCalls", false,
                    "allowServerControl", false,
                    "testServerName", "lobby",
                    "httpTestPath", "/api/application/users?page=1" // harmless GET for Ptero; may vary by panel
            ));
            if (args.length >= 2 && args[0].equalsIgnoreCase("set")) {
                cfg.set(args[1], (args.length >= 3) ? args[2] : "");
                cfg.save();
                actor.sendMessage("Set '" + args[1] + "' and saved.");
                return;
            }
            cfg.save();
            cfg.reload();
            actor.sendMessage("routingEnabled=" + cfg.getBoolean("routingEnabled", true) + ", defaultLobby=" + cfg.getString("defaultLobby", "lobby"));
            actor.sendMessage("allowHttpCalls=" + cfg.getBoolean("allowHttpCalls", false) + ", allowServerControl=" + cfg.getBoolean("allowServerControl", false));
            actor.sendMessage("testServerName=" + cfg.getString("testServerName", "lobby") + ", httpTestPath=" + cfg.getString("httpTestPath", "/"));
        }
    }

    // 4) Event bus demo: subscribe and post test event
    public final class EventCmd implements AddonCommand {
        @Override public String name() { return "event"; }
        @Override public List<String> aliases() { return List.of("events"); }
        @Override public String description() { return "Subscribes to PlayerPostConnectEvent and posts a test event"; }
        @Override public String permission() { return "vpp.sample.event"; }
        @Override public void execute(CommandActor actor, String[] args) {
            VPPEventBus bus = api.getEventBus();
            if (eventSubHandle == null) {
                eventSubHandle = bus.subscribe(PlayerPostConnectEvent.class, ev -> {
                    System.out.println("[VPP-SAMPLE] Saw PlayerPostConnectEvent for " + ev.getPlayerName() + " -> " + ev.getTargetServer());
                });
                actor.sendMessage("Subscribed to PlayerPostConnectEvent.");
            } else {
                try { eventSubHandle.close(); } catch (Exception ignored) {}
                eventSubHandle = null;
                actor.sendMessage("Unsubscribed from PlayerPostConnectEvent.");
            }
            // Post a synthetic event to validate delivery
            bus.post(new PlayerPostConnectEvent(UUID.randomUUID(), actor.getName(), "dummy-server"));
            actor.sendMessage("Posted synthetic PlayerPostConnectEvent; check console for log.");
        }
    }

    // 5) HTTP facade demo (safe GET)
    public final class HttpCmd implements AddonCommand {
        @Override public String name() { return "http"; }
        @Override public List<String> aliases() { return List.of("panel"); }
        @Override public String description() { return "Tests PanelHttpFacade GET on configured path (guarded by config)"; }
        @Override public String permission() { return "vpp.sample.http"; }
        @Override public void execute(CommandActor actor, String[] args) {
            if (!cfg.getBoolean("allowHttpCalls", false)) {
                actor.sendMessage("HTTP test disabled by config (allowHttpCalls=false). Use /ptero addon cfg set allowHttpCalls true");
                return;
            }
            String path = (args.length >= 1) ? args[0] : cfg.getString("httpTestPath", "/");
            PanelHttpFacade http = api.getPanelHttp();
            try {
                URI uri = http.resolve(path);
                actor.sendMessage("GET " + uri);
                CompletableFuture<PanelHttpResponse> fut = http.get(path).orTimeout(10, java.util.concurrent.TimeUnit.SECONDS);
                fut.whenComplete((resp, err) -> {
                    if (err != null) {
                        actor.sendMessage("HTTP error: " + err.getClass().getSimpleName() + ": " + err.getMessage());
                    } else {
                        actor.sendMessage("HTTP status=" + resp.statusCode() + ", bodyLen=" + (resp.body() != null ? resp.body().length() : 0));
                    }
                });
            } catch (Throwable t) {
                actor.sendMessage("HTTP setup failed: " + t.getMessage());
            }
        }
    }

    // 6) Servers and control demo
    public final class ServersCmd implements AddonCommand {
        @Override public String name() { return "servers"; }
        @Override public List<String> aliases() { return List.of("srvs", "list"); }
        @Override public String description() { return "Lists managed servers and optionally starts/stops a configured one"; }
        @Override public String permission() { return "vpp.sample.servers"; }
        @Override public void execute(CommandActor actor, String[] args) {
            ServerRegistry reg = api.getServerRegistry();
            if (reg == null) {
                actor.sendMessage("ServerRegistry not available in this build.");
                return;
            }
            Collection<ManagedServer> list = reg.listServers();
            actor.sendMessage("Managed servers (" + list.size() + "): " + String.join(", ", list.stream().map(ManagedServer::name).toList()));

            if (cfg.getBoolean("allowServerControl", false)) {
                String name = cfg.getString("testServerName", "lobby");
                ServerControl ctl = api.getServerControl();
                if (ctl != null) {
                    boolean online = ctl.isOnline(name);
                    actor.sendMessage("Server '" + name + "' online=" + online + ". Toggling power...");
                    if (online) ctl.stopByName(name); else ctl.startByName(name);
                } else {
                    actor.sendMessage("ServerControl not available in this build.");
                }
            } else {
                actor.sendMessage("Server control disabled by config (allowServerControl=false).");
            }
        }
    }

    // 7) Routing provider exercising PlayerRouteContext
    public static final class SimpleLobbyRouter implements de.tubyoub.vpp.api.routing.RoutingProvider {
        private final String lobby;
        public SimpleLobbyRouter(String lobby) { this.lobby = lobby; }
        @Override
        public boolean selectTarget(de.tubyoub.vpp.api.routing.PlayerRouteContext ctx) {
            if (ctx.getRequestedServer() == null) { // initial join
                ctx.setTargetServer(lobby);
                return true; // handled
            }
            // Example: if player requested an unknown server named "@lobby", redirect
            if ("@lobby".equalsIgnoreCase(ctx.getRequestedServer())) {
                ctx.setTargetServer(lobby);
                return true;
            }
            return false; // let VPP continue with defaults
        }
    }

    public final class RouteCmd implements AddonCommand {
        @Override public String name() { return "route"; }
        @Override public List<String> aliases() { return List.of("routing"); }
        @Override public String description() { return "Toggle/register router and self-test selectRoute"; }
        @Override public String permission() { return "vpp.sample.route"; }
        @Override public void execute(CommandActor actor, String[] args) {
            boolean enabled = cfg.getBoolean("routingEnabled", true);
            String lobby = cfg.getString("defaultLobby", "lobby");
            if (registeredRouter == null && enabled) {
                registeredRouter = new SimpleLobbyRouter(lobby);
                api.registerRoutingProvider(registeredRouter, 100);
                actor.sendMessage("Registered SimpleLobbyRouter -> " + lobby);
            } else if (registeredRouter != null) {
                api.unregisterRoutingProvider(registeredRouter);
                registeredRouter = null;
                actor.sendMessage("Unregistered routing provider.");
            } else {
                actor.sendMessage("Routing disabled by config (routingEnabled=false). Use cfg set routingEnabled true");
            }

            // Self-test selectRoute with dummy context
            de.tubyoub.vpp.api.routing.PlayerRouteContext ctx = new de.tubyoub.vpp.api.routing.PlayerRouteContext(
                    UUID.randomUUID(), actor.getName(), null, "INITIAL");
            boolean handled = api.selectRoute(ctx);
            actor.sendMessage("selectRoute handled=" + handled + ", target=" + ctx.getTargetServer());
            actor.sendMessage("hasRoutingProvider()=" + api.hasRoutingProvider());
        }
    }

    @Subscribe
    public void onInit(ProxyInitializeEvent e) {
        this.api = VPPApiProvider.get();
        if (api == null) {
            logger.error("[VPP-SAMPLE] VPP API not available. Is VelocityPteroPower installed?");
            return;
        }
        if (!"1.0.0".equals(api.getApiVersion())) {
            logger.error("[VPP-SAMPLE] VPP API is on the wrong version. Is VelocityPteroPower up to date?");
            return;
        }
        System.out.println("[VPP-SAMPLE] Starting sample addon with API=" + api.getApiVersion());

        // Commands
        api.registerCommand(new GreetCmd());
        api.registerCommand(new InfoCmd());
        api.registerCommand(new ConfigCmd());
        api.registerCommand(new EventCmd());
        api.registerCommand(new HttpCmd());
        api.registerCommand(new ServersCmd());
        api.registerCommand(new RouteCmd());

        // Config defaults
        this.cfg = api.getAddonConfig("vpp-addon-sample");
        cfg.ensureDefaults(Map.of(
                "routingEnabled", true,
                "defaultLobby", "lobby",
                "allowHttpCalls", false,
                "allowServerControl", false,
                "testServerName", "lobby",
                "httpTestPath", "/"
        ));
        cfg.save();

        // Optionally pre-register routing provider if enabled
        if (cfg.getBoolean("routingEnabled", true)) {
            String lobby = cfg.getString("defaultLobby", "lobby");
            registeredRouter = new SimpleLobbyRouter(lobby);
            api.registerRoutingProvider(registeredRouter, 100);
            System.out.println("[VPP-SAMPLE] Registered SimpleLobbyRouter to send initial joins to '" + lobby + "'.");
        }

        // Subscribe to base VPPEvent interface to show any event hitting the bus (summary only)
        try {
            eventSubHandle = api.getEventBus().subscribe(VPPEvent.class, ev -> {
                System.out.println("[VPP-SAMPLE] Event: " + ev.getClass().getSimpleName());
            });
        } catch (Throwable ignored) {}

        System.out.println("[VPP-SAMPLE] Registered commands: greet, info, cfg, event, http, servers, route. Use /ptero <name> ...");
    }
}
