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

/**
 * Constants for the MCP inbound endpoint protocol.
 */
public class McpConstants {

    // Inbound endpoint parameter keys
    public static final String PARAM_PORT = "inbound.mcp.port";
    public static final String PARAM_TOOLS = "mcp.tools";
    public static final String PARAM_SERVER_NAME = "mcp.server.name";
    public static final String PARAM_SERVER_VERSION = "mcp.server.version";

    // MCP transport paths
    public static final String PATH_MCP = "/mcp";

    // MCP protocol version
    public static final String MCP_PROTOCOL_VERSION = "2024-11-05";

    // Default server info
    public static final String DEFAULT_SERVER_NAME = "WSO2 Micro Integrator";
    public static final String DEFAULT_SERVER_VERSION = "4.4.0";

    // JSON-RPC method names
    public static final String METHOD_INITIALIZE = "initialize";
    public static final String METHOD_INITIALIZED = "notifications/initialized";
    public static final String METHOD_TOOLS_LIST = "tools/list";
    public static final String METHOD_TOOLS_CALL = "tools/call";
    public static final String METHOD_PING = "ping";

    // HTTP methods
    public static final String HTTP_GET = "GET";
    public static final String HTTP_POST = "POST";

    // HTTP headers
    public static final String HEADER_CONTENT_TYPE = "Content-Type";
    public static final String HEADER_CACHE_CONTROL = "Cache-Control";
    public static final String HEADER_CONNECTION = "Connection";
    public static final String HEADER_ACCEPT = "Accept";
    public static final String HEADER_LAST_EVENT_ID = "Last-Event-ID";
    public static final String HEADER_MCP_SESSION_ID = "Mcp-Session-Id";

    // CORS headers
    public static final String HEADER_CORS_ALLOW_ORIGIN = "Access-Control-Allow-Origin";
    public static final String HEADER_CORS_ALLOW_METHODS = "Access-Control-Allow-Methods";
    public static final String HEADER_CORS_ALLOW_HEADERS = "Access-Control-Allow-Headers";
    public static final String HEADER_CORS_EXPOSE_HEADERS = "Access-Control-Expose-Headers";
    public static final String CORS_ALLOW_ORIGIN_VALUE = "*";
    public static final String CORS_ALLOW_METHODS_VALUE = "GET, POST, DELETE, OPTIONS";
    public static final String CORS_ALLOW_HEADERS_VALUE = "Content-Type, Mcp-Session-Id";
    public static final String CORS_EXPOSE_HEADERS_VALUE = "Mcp-Session-Id";

    // Content types
    public static final String CONTENT_TYPE_JSON = "application/json";
    public static final String CONTENT_TYPE_SSE = "text/event-stream";

    // SSE
    public static final String SSE_KEEPALIVE_COMMENT = ": keepalive\n\n";
    public static final long SSE_KEEPALIVE_INTERVAL_MS = 30_000L;

    // MCP message context properties
    public static final String MC_PROPERTY_TOOL_NAME = "mcp.tool.name";
    public static final String MC_PROPERTY_TOOL_ARGUMENTS = "mcp.tool.arguments";
    public static final String MC_PROPERTY_TOOL_RESULT = "mcp.tool.result";
    public static final String MC_PROPERTY_TOOL_IS_ERROR = "mcp.tool.isError";

    // JSON-RPC field names
    public static final String JSONRPC = "jsonrpc";
    public static final String JSONRPC_VERSION = "2.0";
    public static final String ID = "id";
    public static final String METHOD = "method";
    public static final String PARAMS = "params";
    public static final String RESULT = "result";
    public static final String ERROR = "error";
    public static final String ERROR_CODE = "code";
    public static final String ERROR_MESSAGE = "message";

    // JSON-RPC error codes
    public static final int ERROR_METHOD_NOT_FOUND = -32601;
    public static final int ERROR_INVALID_PARAMS = -32602;
    public static final int ERROR_INTERNAL = -32603;
    public static final int ERROR_PARSE = -32700;
    public static final int ERROR_TOOL_NOT_FOUND = -32001;
    public static final int ERROR_TOOL_EXECUTION = -32002;

    private McpConstants() {
    }
}
