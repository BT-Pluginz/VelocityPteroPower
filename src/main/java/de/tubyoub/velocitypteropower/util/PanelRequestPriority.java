/*
 * This file is part of VelocityPteroPower, licensed under the MIT License.
 */
package de.tubyoub.velocitypteropower.util;

/**
 * Priority lanes for panel API traffic. Higher ordinal = lower priority.
 */
public enum PanelRequestPriority {
    /** Player-triggered start / status for waiters. */
    PLAYER,
    /** Always-online keepers and idle shutdown power signals. */
    LIFECYCLE,
    /** Lobby balancer health and scale. */
    BALANCER,
    /** Prefetch, whitelist warm, background sweeps. */
    BACKGROUND
}
