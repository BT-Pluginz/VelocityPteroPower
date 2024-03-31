/*
 * This file is part of VelocityPteroPower, licensed under the MIT License.
 *
 *  Copyright (c) TubYoub <github@tubyoub.de>
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in all
 *  copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *  SOFTWARE.
 */

package de.tubyoub.velocitypteropower;

import java.util.List;

/**
 * This class represents the server information for a Pterodactyl server.
 * It includes the server ID, timeout, and join delay.
 */
public  class PteroServerInfo {
    private final String serverId;
    private final int timeout;
    private final int joinDelay;


    /**
     * Constructor for the PteroServerInfo class.
     *
     * @param serverId the ID of the server
     * @param timeout the timeout for the server
     * @param joinDelay the join delay for the server
     */
    public PteroServerInfo(String serverId, int timeout, int joinDelay) {
        this.serverId = serverId;
        this.timeout = timeout;
        this.joinDelay = joinDelay;
    }

    /**
     * This method returns the server ID.
     *
     * @return the server ID
     */
    public String getServerId() {
        return serverId;
    }

    /**
     * This method returns the timeout for the server.
     *
     * @return the timeout for the server
     */
    public int getTimeout() {
        return timeout;
    }

    /**
     * This method returns the join delay for the server.
     *
     * @return the join delay for the server
     */
    public int getJoinDelay() {
        return joinDelay;
    }
}