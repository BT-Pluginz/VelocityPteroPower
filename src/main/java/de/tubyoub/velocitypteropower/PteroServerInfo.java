package de.tubyoub.velocitypteropower;

public  class PteroServerInfo {
        private final String serverId;
        private final int timeout;
        private final int joinDelay;

        public PteroServerInfo(String serverId, int timeout, int joinDelay) {
            this.serverId = serverId;
            this.timeout = timeout;
            this.joinDelay = joinDelay;
        }

        public String getServerId() {
            return serverId;
        }

        public int getTimeout() {
            return timeout;
        }

        public int getJoinDelay() {
            return joinDelay;
        }
    }