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

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.synapse.config.SynapseConfiguration;
import org.apache.synapse.core.SynapseEnvironment;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

/**
 * Handles MCP JSON-RPC 2.0 requests received on {@code POST /mcp}.
 *
 * <p>Routes each request to the appropriate method handler and returns a
 * {@link HandleResult} containing the JSON-RPC 2.0 response and, for
 * {@code initialize}, the newly allocated session ID.
 */
public class McpProtocolHandler {

    /**
     * Result of a {@link #handle} call.
     */
    public static class HandleResult {
        /** JSON-RPC 2.0 response object; {@code null} for notifications (204). */
        public final JSONObject response;
        /** Non-null only when the call was {@code initialize} — the newly created session ID. */
        public final String newSessionId;

        HandleResult(JSONObject response, String newSessionId) {
            this.response = response;
            this.newSessionId = newSessionId;
        }
    }

    private static final Log log = LogFactory.getLog(McpProtocolHandler.class);

    private final String serverName;
    private final String serverVersion;
    private final String toolKeys;
    private final SynapseEnvironment synapseEnvironment;
    private final int mainHttpPort;

    public McpProtocolHandler(String serverName, String serverVersion, String toolKeys,
                              SynapseEnvironment synapseEnvironment, int mainHttpPort) {
        this.serverName = serverName;
        this.serverVersion = serverVersion;
        this.toolKeys = toolKeys;
        this.synapseEnvironment = synapseEnvironment;
        this.mainHttpPort = mainHttpPort;
    }

    /**
     * Processes a raw JSON-RPC 2.0 request body and returns a {@link HandleResult}.
     *
     * @param requestBody raw JSON string from the HTTP body
     * @return result containing the response JSON (null for notifications) and an optional new session ID
     */
    public HandleResult handle(String requestBody) {
        JSONObject request;
        try {
            request = new JSONObject(requestBody);
        } catch (Exception e) {
            return new HandleResult(errorResponse(null, McpConstants.ERROR_PARSE,
                    "Parse error: " + e.getMessage()), null);
        }

        Object id = request.opt(McpConstants.ID);
        String method = request.optString(McpConstants.METHOD, null);

        if (method == null) {
            return new HandleResult(errorResponse(id, McpConstants.ERROR_METHOD_NOT_FOUND,
                    "Missing 'method' field"), null);
        }

        JSONObject params = request.optJSONObject(McpConstants.PARAMS);
        if (params == null) {
            params = new JSONObject();
        }

        if (log.isDebugEnabled()) {
            log.debug("MCP request: method=" + method + " id=" + id);
        }

        switch (method) {
            case McpConstants.METHOD_INITIALIZE: {
                String newSessionId = McpSessionRegistry.getInstance().createSession();
                return new HandleResult(successResponse(id, handleInitialize()), newSessionId);
            }
            case McpConstants.METHOD_INITIALIZED:
                // Notification — no response (204 No Content)
                return new HandleResult(null, null);
            case McpConstants.METHOD_TOOLS_LIST:
                // TODO: Need to implement this
                JSONObject dummyResult = new JSONObject();
                JSONArray dummyToolsArray = new JSONArray();
                dummyToolsArray.put(toolKeys);
                dummyResult.put("tools", dummyToolsArray);
                return new HandleResult(successResponse(id, dummyResult), null);
            case McpConstants.METHOD_TOOLS_CALL:
                return new HandleResult(handleToolsCall(id, params), null);
            case McpConstants.METHOD_PING:
                return new HandleResult(successResponse(id, new JSONObject()), null);
            default:
                return new HandleResult(errorResponse(id, McpConstants.ERROR_METHOD_NOT_FOUND,
                        "Method not found: " + method), null);
        }
    }

    private JSONObject handleInitialize() {
        JSONObject result = new JSONObject();
        result.put("protocolVersion", McpConstants.MCP_PROTOCOL_VERSION);

        JSONObject capabilities = new JSONObject();
        capabilities.put("tools", new JSONObject().put("listChanged", false));
        result.put("capabilities", capabilities);

        JSONObject serverInfo = new JSONObject();
        serverInfo.put("name", serverName);
        serverInfo.put("version", serverVersion);
        result.put("serverInfo", serverInfo);

        return result;
    }

    private JSONObject handleToolsCall(Object id, JSONObject params) {
        String toolName = params.optString("name", null);
        if (toolName == null || toolName.trim().isEmpty()) {
            return errorResponse(id, McpConstants.ERROR_INVALID_PARAMS, "Missing 'name' in params");
        }

        JSONObject arguments = params.optJSONObject("arguments");
        if (arguments == null) {
            arguments = new JSONObject();
        }

        SynapseConfiguration config = synapseEnvironment.getSynapseConfiguration();

        try {
            String resultText = executeTool(arguments);
            return successResponse(id, buildCallResult(resultText, false));
        } catch (McpToolExecutionException e) {
            log.error("MCP tool '" + toolName + "' execution failed", e);
            return successResponse(id, buildCallResult(e.getMessage(), true));
        }
    }

    private String executeTool(JSONObject args) throws McpToolExecutionException {
        ApiToolExecutor executor = new ApiToolExecutor();
        return executor.execute(args);
    }

    private JSONObject buildCallResult(String text, boolean isError) {
        JSONObject result = new JSONObject();
        JSONArray content = new JSONArray();
        JSONObject textContent = new JSONObject();
        textContent.put("type", "text");
        textContent.put("text", text != null ? text : "");
        content.put(textContent);
        result.put("content", content);
        if (isError) {
            result.put("isError", true);
        }
        return result;
    }

    private JSONObject successResponse(Object id, JSONObject result) {
        JSONObject response = new JSONObject();
        response.put(McpConstants.JSONRPC, McpConstants.JSONRPC_VERSION);
        response.put(McpConstants.ID, id != null ? id : JSONObject.NULL);
        response.put(McpConstants.RESULT, result);
        return response;
    }

    private JSONObject errorResponse(Object id, int code, String message) {
        JSONObject error = new JSONObject();
        error.put(McpConstants.ERROR_CODE, code);
        error.put(McpConstants.ERROR_MESSAGE, message);

        JSONObject response = new JSONObject();
        response.put(McpConstants.JSONRPC, McpConstants.JSONRPC_VERSION);
        response.put(McpConstants.ID, id != null ? id : JSONObject.NULL);
        response.put(McpConstants.ERROR, error);
        return response;
    }
}
