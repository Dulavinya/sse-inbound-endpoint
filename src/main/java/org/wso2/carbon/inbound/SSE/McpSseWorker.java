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

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Worker for {@code GET /mcp} — establishes a long-lived SSE stream for server-to-client
 * notifications as per the MCP Streamable HTTP transport spec.
 *
 * <p>This worker holds a thread from the inbound endpoint's worker pool for the duration
 * of the SSE connection. It sends a keepalive comment every
 * {@link McpConstants#SSE_KEEPALIVE_INTERVAL_MS} milliseconds to prevent the
 * PassThrough transport's socket timeout from firing.
 *
 * <p>The session is registered in {@link McpSseSessionRegistry} so that other components
 * (e.g., a future tool progress notifier) can push events to connected clients.
 */
public class McpSseWorker implements Runnable {

    private static final Log log = LogFactory.getLog(McpSseWorker.class);

    private static final byte[] KEEPALIVE_BYTES =
            McpConstants.SSE_KEEPALIVE_COMMENT.getBytes(StandardCharsets.UTF_8);

    private final SourceRequest request;
    private final SourceConfiguration sourceConfiguration;
    private final String sessionId;
    private final BlockingQueue<String> eventQueue = new LinkedBlockingQueue<>();

    public McpSseWorker(SourceRequest request, SourceConfiguration sourceConfiguration) {
        this.request = request;
        this.sourceConfiguration = sourceConfiguration;
        this.sessionId = UUID.randomUUID().toString();
    }

    public String getSessionId() {
        return sessionId;
    }

    /**
     * Enqueues an SSE event to be sent to this client. Thread-safe.
     *
     * @param eventData the {@code data:} field value (single line, no newlines)
     */
    public void sendEvent(String eventData) {
        eventQueue.offer("data: " + eventData + "\n\n");
    }

    /**
     * Enqueues a named SSE event. Thread-safe.
     */
    public void sendEvent(String eventName, String eventData) {
        eventQueue.offer("event: " + eventName + "\ndata: " + eventData + "\n\n");
    }

    @Override
    public void run() {
        SourceResponse sourceResponse = new SourceResponse(sourceConfiguration, 200, request);
        sourceResponse.addHeader(McpConstants.HEADER_CONTENT_TYPE, McpConstants.CONTENT_TYPE_SSE);
        sourceResponse.addHeader(McpConstants.HEADER_CACHE_CONTROL, "no-cache");
        sourceResponse.addHeader(McpConstants.HEADER_CONNECTION, "keep-alive");
        sourceResponse.addHeader(McpConstants.HEADER_CORS_ALLOW_ORIGIN, McpConstants.CORS_ALLOW_ORIGIN_VALUE);
        sourceResponse.addHeader(McpConstants.HEADER_CORS_EXPOSE_HEADERS, McpConstants.CORS_EXPOSE_HEADERS_VALUE);

        // Echo back the session ID the client opened this stream with, if present
        String mcpSessionId = getHeader(McpConstants.HEADER_MCP_SESSION_ID);
        if (mcpSessionId != null) {
            sourceResponse.addHeader(McpConstants.HEADER_MCP_SESSION_ID, mcpSessionId);
        }

        Pipe pipe = new Pipe(sourceConfiguration.getBufferFactory().getBuffer(),
                "MCP-SSE-" + sessionId, sourceConfiguration);
        pipe.attachConsumer(request.getConnection());
        sourceResponse.connect(pipe);

        SourceContext.setResponse(request.getConnection(), sourceResponse);
        request.getConnection().requestOutput();

        McpSseSessionRegistry.getInstance().register(sessionId, this);
        log.info("MCP SSE session opened: " + sessionId);

        try (OutputStream out = pipe.getOutputStream()) {
            // Per MCP Streamable HTTP spec (2024-11-05), GET /mcp is a notification channel.
            // No initial event is sent — the connection is held open and keepalives are used.
            while (true) {
                String event = eventQueue.poll(McpConstants.SSE_KEEPALIVE_INTERVAL_MS,
                        TimeUnit.MILLISECONDS);
                if (event != null) {
                    writeRaw(out, event);
                } else {
                    // No event within the keepalive window — send a comment to stay alive
                    out.write(KEEPALIVE_BYTES);
                    out.flush();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.debug("MCP SSE worker interrupted for session: " + sessionId);
        } catch (IOException e) {
            // Client disconnected — normal SSE termination
            log.info("MCP SSE session closed (client disconnected): " + sessionId);
        } finally {
            McpSseSessionRegistry.getInstance().unregister(sessionId);
            pipe.setSerializationComplete(true);
        }
    }

    private String getHeader(String headerName) {
        if (request.getRequest() == null) {
            return null;
        }
        Header h = request.getRequest().getFirstHeader(headerName);
        return h != null ? h.getValue() : null;
    }

    private void writeRaw(OutputStream out, String text) throws IOException {
        out.write(text.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }
}
