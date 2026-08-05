package de.tubyoub.velocitypteropower.service;

/**
 * Reasons why a player is currently on a limbo server.
 */
public enum LimboReason {
    /**
     * Plugin redirected the player to limbo while their requested server is starting up.
     */
    SERVER_START_WAIT,

    /**
     * The player switched to a limbo server by themselves (manual move).
     */
    SELF_MOVE,

    /**
     * The player's requested server is under maintenance; waiting for it to be lifted.
     */
    MAINTENANCE_WAIT,

    /**
     * Any other plugin/external reason.
     */
    OTHER
}
