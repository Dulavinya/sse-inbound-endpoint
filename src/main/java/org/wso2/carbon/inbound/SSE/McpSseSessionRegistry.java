/*
 * Copyright (c) 2025, WSO2 LLC. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 LLC. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.wso2.carbon.inbound.SSE;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Singleton registry of active MCP SSE sessions.
 *
 * <p>Allows any component to push events to a specific connected SSE client by session ID.
 */
public class McpSseSessionRegistry {

    private static final McpSseSessionRegistry INSTANCE = new McpSseSessionRegistry();

    private final ConcurrentHashMap<String, McpSseWorker> sessions = new ConcurrentHashMap<>();

    private McpSseSessionRegistry() {
    }

    public static McpSseSessionRegistry getInstance() {
        return INSTANCE;
    }

    public void register(String sessionId, McpSseWorker worker) {
        sessions.put(sessionId, worker);
    }

    public void unregister(String sessionId) {
        sessions.remove(sessionId);
    }

    public McpSseWorker getSession(String sessionId) {
        return sessions.get(sessionId);
    }

    public int getActiveSessionCount() {
        return sessions.size();
    }
}
