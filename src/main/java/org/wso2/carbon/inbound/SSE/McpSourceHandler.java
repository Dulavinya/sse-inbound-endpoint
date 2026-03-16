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

import org.apache.axis2.transport.base.threads.WorkerPool;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.Header;
import org.apache.http.HttpException;
import org.apache.http.nio.NHttpServerConnection;
import org.apache.synapse.transport.passthru.Pipe;
import org.apache.synapse.transport.passthru.ProtocolState;
import org.apache.synapse.transport.passthru.SourceContext;
import org.apache.synapse.transport.passthru.SourceHandler;
import org.apache.synapse.transport.passthru.SourceRequest;
import org.apache.synapse.transport.passthru.SourceResponse;
import org.apache.synapse.transport.passthru.config.SourceConfiguration;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * NIO event handler for the MCP inbound endpoint port.
 *
 * <p>Extends {@link SourceHandler} to reuse the PassThrough NIO infrastructure.
 * On each incoming request, routes to:
 * <ul>
 *   <li>{@link McpRpcWorker} for {@code POST /mcp} (JSON-RPC)</li>
 *   <li>{@link McpSseWorker} for {@code GET /mcp} (SSE stream)</li>
 *   <li>Inline handler for {@code OPTIONS /mcp} (CORS preflight — 204)</li>
 *   <li>Inline handler for {@code DELETE /mcp} (session termination — 200)</li>
 * </ul>
 * CORS headers are added to every response.
 */
public class McpSourceHandler extends SourceHandler {

    private static final Log log = LogFactory.getLog(McpSourceHandler.class);

    private final SourceConfiguration sourceConfiguration;
    private final McpProtocolHandler protocolHandler;
    private WorkerPool workerPool;

    public McpSourceHandler(SourceConfiguration sourceConfiguration,
                            McpProtocolHandler protocolHandler) {
        super(sourceConfiguration);
        this.sourceConfiguration = sourceConfiguration;
        this.protocolHandler = protocolHandler;
    }

    @Override
    public void requestReceived(NHttpServerConnection conn) {
        try {
            SourceRequest request = getSourceRequest(conn);
            if (request == null) {
                return;
            }

            String method = request.getRequest() != null
                    ? request.getRequest().getRequestLine().getMethod().toUpperCase() : "";
            String uri = request.getUri();

            // Strip query string for path matching
            String path = uri.contains("?") ? uri.substring(0, uri.indexOf("?")) : uri;

            if (!McpConstants.PATH_MCP.equals(path)) {
                sendSimpleResponse(request, 404, "Not Found: only /mcp is served by this endpoint");
                return;
            }

            switch (method) {
                case "OPTIONS":
                    sendOptions(request);
                    break;
                case McpConstants.HTTP_GET:
                    getWorkerPool().execute(new McpSseWorker(request, sourceConfiguration));
                    break;
                case McpConstants.HTTP_POST:
                    getWorkerPool().execute(new McpRpcWorker(request, sourceConfiguration, protocolHandler));
                    break;
                case "DELETE":
                    handleDelete(request);
                    break;
                default:
                    sendSimpleResponse(request, 405, "Method Not Allowed: use GET, POST, DELETE, or OPTIONS");
                    break;
            }

        } catch (HttpException e) {
            log.error("HttpException in McpSourceHandler.requestReceived", e);
            informReaderError(conn);
            SourceContext.updateState(conn, ProtocolState.CLOSED);
            sourceConfiguration.getSourceConnections().shutDownConnection(conn, true);
        } catch (IOException e) {
            logIOException(conn, e);
            informReaderError(conn);
            SourceContext.updateState(conn, ProtocolState.CLOSED);
            sourceConfiguration.getSourceConnections().shutDownConnection(conn, true);
        }
    }

    // ---- request handlers ---------------------------------------------------

    /**
     * Responds to CORS preflight ({@code OPTIONS /mcp}) with 204 and full CORS headers.
     */
    private void sendOptions(SourceRequest request) {
        try {
            SourceResponse resp = new SourceResponse(sourceConfiguration, 204, request);
            addCorsHeaders(resp);
            resp.connect(null);
            SourceContext.setResponse(request.getConnection(), resp);
            request.getConnection().requestOutput();
        } catch (Exception e) {
            log.error("Failed to send MCP OPTIONS response", e);
        }
    }

    /**
     * Handles {@code DELETE /mcp} — terminates the session identified by the
     * {@code Mcp-Session-Id} request header.
     */
    private void handleDelete(SourceRequest request) {
        String sessionId = getHeader(request, McpConstants.HEADER_MCP_SESSION_ID);
        if (sessionId != null) {
            McpSessionRegistry.getInstance().remove(sessionId);
            log.info("MCP session terminated: " + sessionId);
        }
        sendSimpleResponse(request, 200, "");
    }

    // ---- response helpers ---------------------------------------------------

    private void addCorsHeaders(SourceResponse resp) {
        resp.addHeader(McpConstants.HEADER_CORS_ALLOW_ORIGIN, McpConstants.CORS_ALLOW_ORIGIN_VALUE);
        resp.addHeader(McpConstants.HEADER_CORS_ALLOW_METHODS, McpConstants.CORS_ALLOW_METHODS_VALUE);
        resp.addHeader(McpConstants.HEADER_CORS_ALLOW_HEADERS, McpConstants.CORS_ALLOW_HEADERS_VALUE);
        resp.addHeader(McpConstants.HEADER_CORS_EXPOSE_HEADERS, McpConstants.CORS_EXPOSE_HEADERS_VALUE);
    }

    private void sendSimpleResponse(SourceRequest request, int statusCode, String body) {
        try {
            byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
            SourceResponse resp = new SourceResponse(sourceConfiguration, statusCode, request);
            addCorsHeaders(resp);
            if (bodyBytes.length > 0) {
                resp.addHeader(McpConstants.HEADER_CONTENT_TYPE, "text/plain; charset=UTF-8");
            }
            Pipe pipe = new Pipe(sourceConfiguration.getBufferFactory().getBuffer(),
                    "MCP-ERR", sourceConfiguration);
            pipe.attachConsumer(request.getConnection());
            resp.connect(pipe);
            SourceContext.setResponse(request.getConnection(), resp);
            request.getConnection().requestOutput();
            try (OutputStream out = pipe.getOutputStream()) {
                out.write(bodyBytes);
                out.flush();
            }
            pipe.setSerializationComplete(true);
        } catch (Exception e) {
            log.error("Failed to send MCP response (status=" + statusCode + ")", e);
        }
    }

    // ---- utilities ----------------------------------------------------------

    private WorkerPool getWorkerPool() {
        if (workerPool == null) {
            synchronized (this) {
                if (workerPool == null) {
                    workerPool = sourceConfiguration.getWorkerPool();
                }
            }
        }
        return workerPool;
    }

    private String getHeader(SourceRequest request, String headerName) {
        if (request.getRequest() == null) {
            return null;
        }
        Header h = request.getRequest().getFirstHeader(headerName);
        return h != null ? h.getValue() : null;
    }
}
