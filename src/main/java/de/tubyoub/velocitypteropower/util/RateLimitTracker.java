/*
 * This file is part of VelocityPteroPower, licensed under the MIT License.
 */
package de.tubyoub.velocitypteropower.util;

import de.tubyoub.velocitypteropower.http.PanelType;
import de.tubyoub.velocitypteropower.manager.ConfigurationManager;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;

import java.net.http.HttpResponse;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Tracks and manages API rate limit information obtained from panel responses.
 */
public class RateLimitTracker {

    private final ComponentLogger logger;
    private final ConfigurationManager configurationManager;

    private final AtomicInteger rateLimit = new AtomicInteger(60);
    private final AtomicInteger remainingRequests = new AtomicInteger(60);
    private final AtomicLong resetAtEpochMs = new AtomicLong(0);
    private final ReentrantLock rateLimitLock = new ReentrantLock();

    public RateLimitTracker(ComponentLogger logger, ConfigurationManager configurationManager) {
        this.logger = logger;
        this.configurationManager = configurationManager;
    }

    /**
     * Checks if an API request can be made based on the remaining request count.
     */
    public boolean canMakeRequest() {
        return canMakeRequest(PanelRequestPriority.PLAYER);
    }

    /**
     * Priority-aware gate. When remaining is 0, allows a probe after the reset window.
     */
    public boolean canMakeRequest(PanelRequestPriority priority) {
        if (configurationManager.getPanelType().equals(PanelType.mcServerSoft)) {
            return true;
        }
        rateLimitLock.lock();
        try {
            maybeRecoverAfterReset();
            int remaining = remainingRequests.get();
            if (remaining > 0) {
                return true;
            }
            // Allow one probe after reset so headers can refresh (avoids permanent stuck-at-zero)
            long resetAt = resetAtEpochMs.get();
            long now = System.currentTimeMillis();
            if (resetAt > 0 && now >= resetAt) {
                logger.debug("Rate limit window elapsed — allowing probe request (priority={}).", priority);
                remainingRequests.set(1);
                return true;
            }
            // Player lane may probe sooner if we never learned a reset time
            if (resetAt <= 0 && priority == PanelRequestPriority.PLAYER) {
                long fallback = now + configurationManager.getRateLimitProbeFallbackSeconds() * 1000L;
                if (resetAtEpochMs.compareAndSet(0, fallback) || now >= resetAtEpochMs.get()) {
                    logger.debug("No reset header known — allowing player-lane probe.");
                    remainingRequests.set(1);
                    return true;
                }
            }
            logger.debug(
                    "API request blocked due to rate limiting ({} remaining, reset in {}s, priority={}).",
                    remaining,
                    secondsUntilReset(),
                    priority);
            return false;
        } finally {
            rateLimitLock.unlock();
        }
    }

    /** Optimistic decrement before sending a request. */
    public void consumeOne() {
        if (configurationManager.getPanelType().equals(PanelType.mcServerSoft)) {
            return;
        }
        remainingRequests.updateAndGet(v -> Math.max(0, v - 1));
    }

    /** Restore one unit after a transport failure that did not count against the panel. */
    public void restoreOne() {
        if (configurationManager.getPanelType().equals(PanelType.mcServerSoft)) {
            return;
        }
        remainingRequests.updateAndGet(v -> Math.min(rateLimit.get(), v + 1));
    }

    private void maybeRecoverAfterReset() {
        long resetAt = resetAtEpochMs.get();
        if (resetAt > 0 && System.currentTimeMillis() >= resetAt && remainingRequests.get() <= 0) {
            remainingRequests.set(Math.max(1, rateLimit.get()));
        }
    }

    public long secondsUntilReset() {
        long resetAt = resetAtEpochMs.get();
        if (resetAt <= 0) {
            return configurationManager.getRateLimitProbeFallbackSeconds();
        }
        long sec = (resetAt - System.currentTimeMillis() + 999) / 1000L;
        return Math.max(1, sec);
    }

    /**
     * Updates the rate limit information based on headers from an API response.
     */
    public void updateRateLimitInfo(HttpResponse<?> response) {
        rateLimitLock.lock();
        try {
            Optional<String> limitHeader = response.headers().firstValue("x-ratelimit-limit");
            Optional<String> remainingHeader = response.headers().firstValue("x-ratelimit-remaining");
            Optional<String> resetHeader = response.headers().firstValue("x-ratelimit-reset");
            Optional<String> retryAfter = response.headers().firstValue("retry-after");

            limitHeader.ifPresent(limitStr -> {
                try {
                    rateLimit.set(Integer.parseInt(limitStr));
                } catch (NumberFormatException e) {
                    logger.warn("Failed to parse X-RateLimit-Limit header: {}", limitStr);
                }
            });

            remainingHeader.ifPresent(remainingStr -> {
                try {
                    remainingRequests.set(Integer.parseInt(remainingStr));
                } catch (NumberFormatException e) {
                    logger.warn("Failed to parse X-RateLimit-Remaining header: {}", remainingStr);
                }
            });

            if (resetHeader.isPresent()) {
                try {
                    long resetEpochSec = Long.parseLong(resetHeader.get().trim());
                    // Panel may send unix seconds or seconds-from-now; treat large values as epoch
                    long resetMs = resetEpochSec > 1_000_000_000L
                            ? resetEpochSec * 1000L
                            : System.currentTimeMillis() + resetEpochSec * 1000L;
                    resetAtEpochMs.set(resetMs);
                } catch (NumberFormatException e) {
                    logger.warn("Failed to parse X-RateLimit-Reset header: {}", resetHeader.get());
                }
            } else if (retryAfter.isPresent()) {
                try {
                    long seconds = Long.parseLong(retryAfter.get().trim());
                    resetAtEpochMs.set(System.currentTimeMillis() + seconds * 1000L);
                } catch (NumberFormatException e) {
                    logger.warn("Failed to parse Retry-After header: {}", retryAfter.get());
                }
            }

            if (configurationManager.isPrintRateLimit()) {
                logger.info(
                        "Rate limit updated: Limit: {}, Remaining: {}, ResetIn: {}s",
                        rateLimit.get(),
                        remainingRequests.get(),
                        secondsUntilReset());
            }
        } finally {
            rateLimitLock.unlock();
        }
    }

    public int getRemainingRequests() {
        return remainingRequests.get();
    }

    public int getRateLimit() {
        return rateLimit.get();
    }
}
