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
import org.apache.http.Header;
import org.apache.synapse.transport.passthru.Pipe;
import org.apache.synapse.transport.passthru.SourceContext;
import org.apache.synapse.transport.passthru.SourceRequest;
import org.apache.synapse.transport.passthru.SourceResponse;
import org.apache.synapse.transport.passthru.config.SourceConfiguration;
import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Worker for {@code POST /mcp} — reads the JSON-RPC 2.0 request body, validates the
 * MCP session, delegates to {@link McpProtocolHandler}, and writes the response using
 * the PassThrough Pipe mechanism.
 *
 * <p>Session lifecycle:
 * <ul>
 *   <li>{@code initialize} — no session required; creates one and returns it via
 *       {@code Mcp-Session-Id} response header.</li>
 *   <li>All other methods — {@code Mcp-Session-Id} header required; 404 if missing or invalid.</li>
 * </ul>
 *
 * <p>If the client sends {@code Accept: text/event-stream} the response is wrapped
 * as a single-shot SSE message ({@code event: message}).
 */
public class McpRpcWorker implements Runnable {

    private static final Log log = LogFactory.getLog(McpRpcWorker.class);

    private final SourceRequest request;
    private final SourceConfiguration sourceConfiguration;
    private final McpProtocolHandler protocolHandler;

    public McpRpcWorker(SourceRequest request, SourceConfiguration sourceConfiguration,
                        McpProtocolHandler protocolHandler) {
        this.request = request;
        this.sourceConfiguration = sourceConfiguration;
        this.protocolHandler = protocolHandler;
    }

    @Override
    public void run() {
        try {
            String requestBody = readBody();

            // Determine method without fully parsing (avoids double-parse on error)
            String method = extractMethod(requestBody);

            // Session validation — initialize is exempt (it creates the session)
            if (!McpConstants.METHOD_INITIALIZE.equals(method)) {
                String incomingSessionId = getHeader(McpConstants.HEADER_MCP_SESSION_ID);
                if (!McpSessionRegistry.getInstance().isValid(incomingSessionId)) {
                    sendJsonError(404, McpConstants.ERROR_INTERNAL, "Session not found or expired");
                    return;
                }
            }

            McpProtocolHandler.HandleResult result = protocolHandler.handle(requestBody);

            String accept = getHeader(McpConstants.HEADER_ACCEPT);
            boolean wantsSse = accept != null && accept.contains(McpConstants.CONTENT_TYPE_SSE);

            if (result.response == null) {
                // notifications/initialized — 204 No Content
                sendNoContentResponse(result.newSessionId);
            } else if (wantsSse) {
                sendSseResponse(result.response, result.newSessionId);
            } else {
                byte[] responseBytes = result.response.toString().getBytes(StandardCharsets.UTF_8);
                sendJsonResponse(200, responseBytes, result.newSessionId);
            }
        } catch (Exception e) {
            log.error("McpRpcWorker failed", e);
            try {
                sendJsonError(500, McpConstants.ERROR_INTERNAL, "Internal server error");
            } catch (Exception ex) {
                log.error("Failed to send MCP error response", ex);
            }
        }
    }

    // ---- body reading -------------------------------------------------------

    private String readBody() throws IOException {
        if (!request.isEntityEnclosing()) {
            return "";
        }
        Pipe requestPipe = request.getPipe();
        if (requestPipe == null) {
            return "";
        }
        java.io.InputStream in = requestPipe.getInputStream();
        if (in == null) {
            return "";
        }
        byte[] buffer = new byte[4096];
        StringBuilder sb = new StringBuilder();
        int n;
        while ((n = in.read(buffer)) != -1) {
            sb.append(new String(buffer, 0, n, StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    /** Extracts the {@code method} field from the JSON body without full parsing. */
    private String extractMethod(String body) {
        try {
            return new JSONObject(body).optString(McpConstants.METHOD, "");
        } catch (Exception e) {
            return "";
        }
    }

    // ---- header helpers -----------------------------------------------------

    private String getHeader(String headerName) {
        if (request.getRequest() == null) {
            return null;
        }
        Header h = request.getRequest().getFirstHeader(headerName);
        return h != null ? h.getValue() : null;
    }

    // ---- response helpers ---------------------------------------------------

    private void addCorsHeaders(SourceResponse resp) {
        resp.addHeader(McpConstants.HEADER_CORS_ALLOW_ORIGIN, McpConstants.CORS_ALLOW_ORIGIN_VALUE);
        resp.addHeader(McpConstants.HEADER_CORS_EXPOSE_HEADERS, McpConstants.CORS_EXPOSE_HEADERS_VALUE);
    }

    private void sendJsonResponse(int statusCode, byte[] body, String sessionId) throws IOException {
        SourceResponse sourceResponse = new SourceResponse(sourceConfiguration, statusCode, request);
        sourceResponse.addHeader(McpConstants.HEADER_CONTENT_TYPE,
                McpConstants.CONTENT_TYPE_JSON + "; charset=UTF-8");
        addCorsHeaders(sourceResponse);
        if (sessionId != null) {
            sourceResponse.addHeader(McpConstants.HEADER_MCP_SESSION_ID, sessionId);
        }

        Pipe pipe = new Pipe(sourceConfiguration.getBufferFactory().getBuffer(),
                "MCP-RPC", sourceConfiguration);
        pipe.attachConsumer(request.getConnection());
        sourceResponse.connect(pipe);
        SourceContext.setResponse(request.getConnection(), sourceResponse);
        request.getConnection().requestOutput();

        try (OutputStream out = pipe.getOutputStream()) {
            out.write(body);
            out.flush();
        }
        pipe.setSerializationComplete(true);
    }

    private void sendNoContentResponse(String sessionId) throws IOException {
        SourceResponse sourceResponse = new SourceResponse(sourceConfiguration, 204, request);
        addCorsHeaders(sourceResponse);
        if (sessionId != null) {
            sourceResponse.addHeader(McpConstants.HEADER_MCP_SESSION_ID, sessionId);
        }
        sourceResponse.connect(null);
        SourceContext.setResponse(request.getConnection(), sourceResponse);
        request.getConnection().requestOutput();
    }

    /**
     * Wraps the JSON-RPC response in a single-shot SSE message for clients that
     * send {@code Accept: text/event-stream}.
     */
    private void sendSseResponse(JSONObject responseJson, String sessionId) throws IOException {
        String ssePayload = "event: message\ndata: " + responseJson.toString() + "\n\n";
        byte[] body = ssePayload.getBytes(StandardCharsets.UTF_8);

        SourceResponse sourceResponse = new SourceResponse(sourceConfiguration, 200, request);
        sourceResponse.addHeader(McpConstants.HEADER_CONTENT_TYPE, McpConstants.CONTENT_TYPE_SSE);
        sourceResponse.addHeader(McpConstants.HEADER_CACHE_CONTROL, "no-cache");
        addCorsHeaders(sourceResponse);
        if (sessionId != null) {
            sourceResponse.addHeader(McpConstants.HEADER_MCP_SESSION_ID, sessionId);
        }

        Pipe pipe = new Pipe(sourceConfiguration.getBufferFactory().getBuffer(),
                "MCP-SSE-RPC", sourceConfiguration);
        pipe.attachConsumer(request.getConnection());
        sourceResponse.connect(pipe);
        SourceContext.setResponse(request.getConnection(), sourceResponse);
        request.getConnection().requestOutput();

        try (OutputStream out = pipe.getOutputStream()) {
            out.write(body);
            out.flush();
        }
        pipe.setSerializationComplete(true);
    }

    private void sendJsonError(int statusCode, int errorCode, String message) throws IOException {
        JSONObject err = new JSONObject();
        err.put(McpConstants.ERROR_CODE, errorCode);
        err.put(McpConstants.ERROR_MESSAGE, message);
        JSONObject body = new JSONObject();
        body.put(McpConstants.JSONRPC, McpConstants.JSONRPC_VERSION);
        body.put(McpConstants.ID, JSONObject.NULL);
        body.put(McpConstants.ERROR, err);
        sendJsonResponse(statusCode, body.toString().getBytes(StandardCharsets.UTF_8), null);
    }
}
