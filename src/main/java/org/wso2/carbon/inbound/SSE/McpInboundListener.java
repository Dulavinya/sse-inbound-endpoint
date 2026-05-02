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
import org.apache.synapse.SynapseException;
import org.apache.synapse.config.SynapsePropertiesLoader;
import org.apache.synapse.core.SynapseEnvironment;
import org.apache.synapse.inbound.InboundProcessorParams;
import org.apache.synapse.transport.passthru.api.PassThroughInboundEndpointHandler;
import org.apache.synapse.transport.passthru.config.SourceConfiguration;
import org.wso2.carbon.inbound.endpoint.persistence.PersistenceUtils;
import org.wso2.carbon.inbound.endpoint.protocol.generic.GenericInboundListener;
import org.wso2.carbon.inbound.endpoint.protocol.http.InboundHttpConstants;
import org.wso2.carbon.inbound.endpoint.protocol.http.management.HTTPEndpointManager;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.Properties;
public class McpInboundListener extends GenericInboundListener {

    private static final Log log = LogFactory.getLog(McpInboundListener.class);

    private final String name;
    private final int port;
    private final InboundProcessorParams processorParams;
    private final McpProtocolHandler protocolHandler;
    private final CorsConfig corsConfig;
    private final boolean startInPausedMode;

    public McpInboundListener(InboundProcessorParams params) {

        super(params);
        this.processorParams = params;
        this.name = params.getName();
        this.startInPausedMode = params.startInPausedMode();

        Properties props = params.getProperties();

        boolean enablePortOffset = SynapsePropertiesLoader.getBooleanProperty(
                InboundHttpConstants.ENABLE_PORT_OFFSET_FOR_INBOUND_ENDPOINT, false);
        String portParam = props.getProperty(McpConstants.PARAM_PORT);
        if (portParam == null || portParam.trim().isEmpty()) {
            throw new SynapseException(
                    "MCP inbound endpoint '" + name + "' is missing required parameter '"
                    + McpConstants.PARAM_PORT + "'");
        }
        int rawPort;
        try {
            rawPort = Integer.parseInt(portParam.trim());
        } catch (NumberFormatException e) {
            throw new SynapseException("MCP inbound endpoint '" + name
                    + "': invalid port value '" + portParam + "'", e);
        }
        this.port = enablePortOffset ? rawPort + PersistenceUtils.getPortOffset() : rawPort;

        String serverName = props.getProperty(McpConstants.PARAM_SERVER_NAME,
                McpConstants.DEFAULT_SERVER_NAME);
        String serverVersion = props.getProperty(McpConstants.PARAM_SERVER_VERSION,
                McpConstants.DEFAULT_SERVER_VERSION);
        String localEntryName = props.getProperty(McpConstants.PARAM_TOOLS_LOCALENTRY,
                props.getProperty(McpConstants.PARAM_TOOLS, ""));

        // Load CORS configuration from properties
        String corsAllowOrigin = props.getProperty(McpConstants.PARAM_CORS_ALLOW_ORIGIN,
                McpConstants.DEFAULT_CORS_ALLOW_ORIGIN);
        String corsAllowMethods = props.getProperty(McpConstants.PARAM_CORS_ALLOW_METHODS,
                McpConstants.DEFAULT_CORS_ALLOW_METHODS);
        String corsAllowHeaders = props.getProperty(McpConstants.PARAM_CORS_ALLOW_HEADERS,
                McpConstants.DEFAULT_CORS_ALLOW_HEADERS);
        String corsExposeHeaders = props.getProperty(McpConstants.PARAM_CORS_EXPOSE_HEADERS,
                McpConstants.DEFAULT_CORS_EXPOSE_HEADERS);

        this.corsConfig = new CorsConfig(corsAllowOrigin, corsAllowMethods, corsAllowHeaders,
                corsExposeHeaders);

        // Log CORS configuration
        log.info("MCP inbound endpoint [" + name + "] CORS configuration loaded:");
        log.info("  - Allow-Origin: " + corsAllowOrigin);
        log.info("  - Allow-Methods: " + corsAllowMethods);
        log.info("  - Allow-Headers: " + corsAllowHeaders);
        log.info("  - Expose-Headers: " + corsExposeHeaders);

        // Load and log SSE configuration
        long sseKeepaliveInterval = McpConstants.DEFAULT_SSE_KEEPALIVE_INTERVAL_MS;
        String keepaliveParam = props.getProperty(McpConstants.PARAM_SSE_KEEPALIVE_INTERVAL, 
                String.valueOf(McpConstants.DEFAULT_SSE_KEEPALIVE_INTERVAL_MS));
        try {
            sseKeepaliveInterval = Long.parseLong(keepaliveParam.trim());
        } catch (NumberFormatException e) {
            log.warn("Invalid SSE keepalive interval value '" + keepaliveParam + "', using default " 
                    + McpConstants.DEFAULT_SSE_KEEPALIVE_INTERVAL_MS + "ms");
        }
        log.info("MCP inbound endpoint [" + name + "] SSE configuration loaded:");
        log.info("  - Keepalive Interval: " + sseKeepaliveInterval + "ms");

        SynapseEnvironment synapseEnvironment = params.getSynapseEnvironment();
        int mainHttpPort = resolveMainHttpPort(synapseEnvironment);

        this.protocolHandler = new McpProtocolHandler(serverName, serverVersion, localEntryName,
                synapseEnvironment, mainHttpPort);
    }

    @Override
    public void init() {
        log.info("MCP inbound endpoint [" + name + "] initializing on port " + port
                + (startInPausedMode ? " (suspended mode)" : ""));

        if (startInPausedMode) {
            return;
        }

        if (isPortUsedByAnotherApplication(port)) {
            throw new SynapseException("Port " + port + " used by MCP inbound endpoint ["
                    + name + "] is already in use by another application");
        }

        startListener();
    }

    @Override
    public void destroy() {
        log.info("MCP inbound endpoint [" + name + "] stopping on port " + port);
        HTTPEndpointManager.getInstance().closeEndpoint(port);
    }

    @Override
    public void pause() {
        HTTPEndpointManager.getInstance().closeEndpoint(port);
    }

    @Override
    public boolean activate() {
        if (isPortUsedByAnotherApplication(port)) {
            log.error("Cannot activate MCP inbound endpoint [" + name + "]: port " + port + " is in use");
            return false;
        }
        boolean activated = startListener();
        if (activated) {
            log.info("MCP inbound endpoint [" + name + "] activated on port " + port);
        }
        return activated;
    }

    @Override
    public boolean deactivate() {
        HTTPEndpointManager.getInstance().closeEndpoint(port);
        boolean deactivated = !HTTPEndpointManager.getInstance().isEndpointRunning(name, port);
        if (deactivated) {
            log.info("MCP inbound endpoint [" + name + "] deactivated");
        }
        return deactivated;
    }

    @Override
    public boolean isDeactivated() {
        return !HTTPEndpointManager.getInstance().isEndpointRunning(name, port);
    }

    private boolean startListener() {
        SourceConfiguration sourceConfig;
        try {
            sourceConfig = PassThroughInboundEndpointHandler.getPassThroughSourceConfiguration();
        } catch (Exception e) {
            throw new SynapseException(
                    "Failed to obtain PassThrough source configuration for MCP endpoint [" + name + "]", e);
        }
        if (sourceConfig == null) {
            throw new SynapseException(
                    "PassThrough source configuration is not available — ensure the HTTP transport is started");
        }

        McpSourceHandler mcpSourceHandler = new McpSourceHandler(sourceConfig, protocolHandler, corsConfig);

        try {
            PassThroughInboundEndpointHandler.startEndpoint(
                    new InetSocketAddress(port), mcpSourceHandler, name);
            log.info("MCP server [" + name + "] started on port " + port
                    + " — POST /mcp (JSON-RPC), GET /mcp (SSE)");
            return true;
        } catch (Exception e) {
            log.error("Failed to start MCP inbound endpoint [" + name + "] on port " + port, e);
            return false;
        }
    }

    private int resolveMainHttpPort(SynapseEnvironment synapseEnvironment) {
        try {
            org.apache.axis2.description.TransportInDescription httpTransport =
                    synapseEnvironment.getSynapseConfiguration().getAxisConfiguration()
                            .getTransportIn("http");
            if (httpTransport != null) {
                org.apache.axis2.description.Parameter portParam =
                        httpTransport.getParameter("port");
                if (portParam != null) {
                    return Integer.parseInt(portParam.getValue().toString().trim());
                }
            }
        } catch (Exception e) {
            log.warn("Could not resolve main HTTP port from Axis2 config; using 8290", e);
        }
        return 8290; // default MI HTTP port (8280 base + 10 offset)
    }

    private boolean isPortUsedByAnotherApplication(int port) {
        if (PassThroughInboundEndpointHandler.isEndpointRunning(port)) {
            return false; // already registered in our transport — not another app
        }
        try (ServerSocket srv = new ServerSocket(port)) {
            return false;
        } catch (IOException e) {
            return true;
        }
    }
}
