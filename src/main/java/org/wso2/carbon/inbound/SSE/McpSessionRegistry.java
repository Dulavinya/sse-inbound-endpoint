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

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
public class McpSessionRegistry {

    private static final McpSessionRegistry INSTANCE = new McpSessionRegistry();
    private final Set<String> sessions = ConcurrentHashMap.newKeySet();

    private McpSessionRegistry() {
    }

    public static McpSessionRegistry getInstance() {
        return INSTANCE;
    }

    public String createSession() {
        String id = UUID.randomUUID().toString();
        sessions.add(id);
        return id;
    }

    public boolean isValid(String sessionId) {
        return sessionId != null && sessions.contains(sessionId);
    }

    public void remove(String sessionId) {
        if (sessionId != null) {
            sessions.remove(sessionId);
        }
    }
}
