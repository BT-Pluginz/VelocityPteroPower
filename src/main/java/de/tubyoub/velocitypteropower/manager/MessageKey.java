package de.tubyoub.velocitypteropower.manager;

/**
 * Type-safe message keys to improve discoverability and reduce typos.
 * Paths correspond to entries in the language YAML files.
 */
public enum MessageKey {
    PREFIX("prefix"),

    // Generic feedback
    GENERIC_SUCCESS("generic.success"),
    GENERIC_ERROR("generic.error"),
    GENERIC_RELOAD_SUCCESS("generic.reload-success"),
    GENERIC_RELOAD_FAILED("generic.reload-failed"),

    // Permissions / usage
    COMMAND_NO_PERMISSION("command.no-permission"),
    COMMAND_PLAYER_ONLY("command.player-only"),
    COMMAND_CONSOLE_ONLY("command.console-only"),
    COMMAND_USAGE("command.usage"),
    COMMAND_UNKNOWN_SUBCOMMAND("command.unknown-subcommand"),
    COMMAND_MCSS_NOT_SUPPORTED("command.mcss-not-supported"),
    COMMAND_NO_SERVERS_FOUND("command.no-servers-found"),
    COMMAND_RATE_LIMIT_EXCEEDED("command.rate-limit-exceeded"),
    COMMAND_STOPPING_ALL_SERVERS("command.stopping-all-servers"),
    COMMAND_FORCE_STOPPING_ALL_SERVERS("command.force-stopping-all-servers"),
    COMMAND_FORCE_STARTING_ALL_SERVERS("command.force-starting-all-servers"),
    COMMAND_FORCE_START_WARNING("command.force-start-warning"),
    COMMAND_HELP_HEADER("command.help-header"),
    COMMAND_COOLDOWN_ACTIVE("command.cooldown-active"),
    COMMAND_LIST_HEADER("command.list-header"),
    COMMAND_LIST_ENTRY("command.list-entry"),
    COMMAND_INFO_HEADER("command.info-header"),
    COMMAND_INFO_BODY("command.info-body"),
    COMMAND_INFO_NOT_FOUND("command.info-not-found"),

    // Connection flow
    CONNECT_UNMANAGED_SERVER("connect.unmanaged-server"),
    CONNECT_SERVER_STARTING("connect.server-starting"),
    CONNECT_SERVER_STARTING_INITIATOR("connect.server-starting-initiator"),
    CONNECT_ERROR_RATE_LIMITED("connect.error-rate-limited"),
    CONNECT_REDIRECTING_TO_LIMBO("connect.redirecting-to-limbo"),
    CONNECT_STARTING_SERVER_DISCONNECT("connect.starting-server-disconnect"),
    CONNECT_START_TIMEOUT("connect.start-timeout"),
    CONNECT_QUEUE_CANCELLED("connect.queue-cancelled"),
    CONNECT_QUEUE_PROGRESS("connect.queue-progress"),
    CONNECT_QUEUE_STATUS("connect.queue-status"),
    CONNECT_QUEUE_EMPTY("connect.queue-empty"),
    CONNECT_QUEUE_LIST_HEADER("connect.queue-list-header"),
    CONNECT_QUEUE_LIST_ENTRY("connect.queue-list-entry"),
    CONNECT_QUEUE_LIST_EMPTY("connect.queue-list-empty"),
    CONNECT_CAPACITY_QUEUED("connect.capacity-queued"),
    CONNECT_MAINTENANCE_BLOCKED("connect.maintenance-blocked"),
    CONNECT_TARGET_SERVER_NOT_FOUND("connect.target-server-not-found"),
    CONNECT_NOT_WHITELISTED("connect.not-whitelisted"),
    CONNECT_MAX_ONLINE_REACHED("connect.max-online-reached"),

    // Server lifecycle
    SERVER_STARTING("server.starting"),
    SERVER_STARTED("server.started"),
    SERVER_STOPPING("server.stopping"),
    SERVER_STOPPED("server.stopped"),
    SERVER_IDLE_SHUTDOWN("server.idle-shutdown"),
    SERVER_SHUTDOWN_SCHEDULED("server.shutdown-scheduled"),
    SERVER_SHUTDOWN_CANCELLED_PLAYERS("server.shutdown-cancelled-players"),
    SERVER_SHUTDOWN_CANCELLED("server.shutdown-cancelled"),
    SERVER_SHUTDOWN_SUCCESS("server.shutdown-success"),
    SERVER_SHUTDOWN_FAILED("server.shutdown-failed"),
    SERVER_STILL_ONLINE_RETRYING("server.still-online-retrying"),

    // Pterodactyl/Power actions (example keys)
    POWER_ACTION_SENT("power.action-sent"),
    POWER_ACTION_FAILED("power.action-failed"),
    POWER_STATUS("power.status");

    private final String path;

    MessageKey(String path) {
        this.path = path;
    }

    public String getPath() {
        return path;
    }
}
