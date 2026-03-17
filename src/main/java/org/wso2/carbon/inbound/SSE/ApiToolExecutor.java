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
import org.apache.synapse.api.API;
import org.apache.synapse.config.SynapseConfiguration;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Map;

/**
 * Executes an MCP tool by dispatching an HTTP request to the corresponding Synapse REST API
 * on localhost. The API context is resolved from the deployed {@link API} artifact.
 */
public class ApiToolExecutor {

    private static final Log log = LogFactory.getLog(ApiToolExecutor.class);
    private static final int CONNECT_TIMEOUT = 10000;  // 10 seconds
    private static final int READ_TIMEOUT = 30000;     // 30 seconds

    private final int port;
    private final SynapseConfiguration synapseConfig;

    public ApiToolExecutor(int port) {
        this(port, null);
    }

    public ApiToolExecutor(int port, SynapseConfiguration synapseConfig) {
        this.port = port;
        this.synapseConfig = synapseConfig;
    }

    /**
     * Executes the API-backed tool and returns the response body as a string.
     *
     * @param toolDefinition tool metadata (method, resource, description, api, etc.)
     * @param arguments MCP tool arguments
     * @return response body string
     * @throws McpToolExecutionException if the API call fails
     */
    public String execute(Map<String, Object> toolDefinition, JSONObject arguments)
            throws McpToolExecutionException {
        if (toolDefinition == null) {
            throw new McpToolExecutionException("Tool definition is null");
        }

        // Extract method and resource from tool definition
        String method = (String) toolDefinition.getOrDefault("method", "POST");
        String resource = (String) toolDefinition.getOrDefault("resource", "/");
        
        // Resolve API context (toolDefinition may contain an API name like "TestAPI" or a context like "/test")
        String apiContext = resolveApiContext(toolDefinition);

        // For GET requests, substitute path parameters in the URL (e.g., /orders/{id} -> /orders/123).
        // For non-GET requests, keep the path template as-is (e.g., /orders/{id}) so Synapse can
        // extract the path parameter. This avoids issues with PayloadFactory trying to resolve
        // XPath variables that may not be populated when we pre-substitute the URL.
        String path;
        JSONObject bodyJson = new JSONObject(arguments.toString());
        java.util.Map<String, String> pathParamValues = new java.util.HashMap<>();
        
        if ("GET".equalsIgnoreCase(method)) {
            // For GET: substitute path parameters in the URL
            path = substitutePathParameters(resource, arguments);
        } else {
            // For non-GET: keep the path template, and store param values for headers/body
            path = resource;
            for (String param : extractPathParamNames(resource)) {
                if (arguments.has(param)) {
                    Object v = arguments.opt(param);
                    if (v != null) {
                        pathParamValues.put(param, v.toString());
                    }
                }
                // Don't remove from body yet; we'll decide after seeing if the request succeeds
            }
        }
        
        // Prepend API context if present (e.g., /test + /users = /test/users)
        if (apiContext != null && !apiContext.isEmpty() && !apiContext.equals("/")) {
            // Ensure the context starts with '/'
            if (!apiContext.startsWith("/")) {
                apiContext = "/" + apiContext;
                log.warn("API context did not start with '/'; normalized to '" + apiContext + "'.");
            }
            if (!path.startsWith("/")) {
                path = "/" + path;
            }
            path = apiContext + path;
        }

        String url = "http://localhost:" + port + path;
        String body = null;
        String query = null;

        // For GET: append query parameters; for POST/PUT/PATCH: send JSON body with all arguments
        if ("GET".equalsIgnoreCase(method)) {
            query = buildQueryString(arguments);
            if (query != null && !query.isEmpty()) {
                url = url + "?" + query;
            }
        } else {
            // POST, PUT, PATCH, DELETE, etc. -> send JSON body with original arguments
            // (including path params) so the API can access them from the body if needed.
            body = arguments.toString();
        }

        if (log.isDebugEnabled()) {
            log.debug("Executing tool: " + method + " " + url + (body != null ? " with body: " + body : ""));
        }

        try {
            return sendHttpRequest(method, url, body, pathParamValues);
        } catch (McpToolExecutionException e) {
            // If the request failed due to Synapse not resolving $uri:<name>, retry by
            // including the original arguments (with path params) in the body. This is
            // a fallback for APIs that read the identifier from the message body instead
            // of URI template variables.
            // We detect this by looking for HTTP 5xx errors (server-side mediator failure)
            // or explicit XPath/Variable errors in the response message.
            String msg = e.getMessage() != null ? e.getMessage() : "";
            boolean looksLikeUriVarError = msg.contains("HTTP 5") || msg.contains("UnresolvableException") || msg.contains("Variable ");
            if (looksLikeUriVarError && bodyJson.length() >= 0) {
                // Retry with original arguments JSON (including path params)
                String originalBody = arguments != null ? arguments.toString() : "";
                try {
                    log.warn("Initial API call failed (likely URI variable issue); retrying with original body including path params. Error was: " + msg);
                    return sendHttpRequest(method, url, originalBody, pathParamValues);
                } catch (McpToolExecutionException ex2) {
                    // If retry fails, throw the original exception to surface the initial cause.
                    log.error("Retry with original body also failed", ex2);
                    throw e;
                }
            }
            throw e;
        }
    }

    /**
     * Extract path parameter names from a resource template, e.g. "/orders/{id}" -> ["id"].
     */
    private java.util.List<String> extractPathParamNames(String resource) {
        java.util.List<String> params = new java.util.ArrayList<>();
        if (resource == null || resource.isEmpty()) {
            return params;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\{([^/}]+)\\}").matcher(resource);
        while (m.find()) {
            params.add(m.group(1));
        }
        return params;
    }

    /**
     * Substitute path parameters in the resource path (e.g., /orders/{id} -> /orders/123).
     */
    private String substitutePathParameters(String resource, JSONObject arguments) {
        String result = resource;
        Iterator<String> keys = arguments.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object value = arguments.opt(key);
            if (value != null) {
                String placeholder = "{" + key + "}";
                result = result.replace(placeholder, value.toString());
            }
        }
        return result;
    }

    /**
     * Build query string from arguments (for GET requests).
     */
    private String buildQueryString(JSONObject arguments) {
        StringBuilder sb = new StringBuilder();
        Iterator<String> keys = arguments.keys();
        boolean first = true;
        while (keys.hasNext()) {
            String key = keys.next();
            Object value = arguments.opt(key);
            if (value != null) {
                if (!first) {
                    sb.append("&");
                }
                try {
                    sb.append(URLEncoder.encode(key, "UTF-8")).append("=")
                            .append(URLEncoder.encode(value.toString(), "UTF-8"));
                    first = false;
                } catch (Exception e) {
                    log.warn("Failed to encode query parameter " + key, e);
                }
            }
        }
        return sb.toString();
    }

    /**
     * Send an HTTP request and return the response body.
     */
    private String sendHttpRequest(String method, String urlStr, String body, java.util.Map<String, String> pathParamValues)
            throws McpToolExecutionException {
        try {
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod(method);
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "application/json");

            // Add headers carrying path parameter values to help the Synapse XPath variable
            // resolver (if it maps transport headers to variables). We add a few variants
            // to increase compatibility with different Synapse setups.
            if (pathParamValues != null && !pathParamValues.isEmpty()) {
                for (java.util.Map.Entry<String, String> e : pathParamValues.entrySet()) {
                    String k = e.getKey();
                    String v = e.getValue();
                    try {
                        conn.setRequestProperty("uri.var." + k, v);
                        conn.setRequestProperty("uri." + k, v);
                        conn.setRequestProperty("X-URI-VAR-" + k, v);
                    } catch (Exception ex) {
                        // ignore header set failures
                    }
                }
            }

            if (body != null && !body.isEmpty()) {
                conn.setDoOutput(true);
                try (OutputStream os = conn.getOutputStream()) {
                    byte[] input = body.getBytes(StandardCharsets.UTF_8);
                    os.write(input, 0, input.length);
                }
            }

            int responseCode = conn.getResponseCode();
            String responseBody = readResponse(conn);

            if (responseCode < 200 || responseCode >= 300) {
                String errMsg = "HTTP " + responseCode + ": " + responseBody;
                log.warn("API call failed: " + errMsg);
                throw new McpToolExecutionException(errMsg);
            }

            log.debug("API call succeeded (HTTP " + responseCode + "): " + responseBody);
            return responseBody;

        } catch (McpToolExecutionException e) {
            throw e;
        } catch (Exception e) {
            String errMsg = "Failed to execute API tool: " + e.getMessage();
            log.error(errMsg, e);
            throw new McpToolExecutionException(errMsg, e);
        }
    }

    /**
     * Resolve the API context from the tool definition. The tool may provide either
     * the API context (e.g. "/test") or the API name (e.g. "TestAPI"). When an API
     * name is supplied, attempt to resolve it via the SynapseConfiguration to obtain
     * the deployed API context.
     */
    private String resolveApiContext(Map<String, Object> toolDefinition) {
        if (toolDefinition == null) {
            return "";
        }
        Object apiObj = toolDefinition.get("api");
        if (apiObj == null) {
            return "";
        }
        String apiVal = apiObj.toString().trim();
        if (apiVal.isEmpty()) {
            return "";
        }

        // If it's already a path, return as-is (normalization happens later)
        if (apiVal.startsWith("/")) {
            return apiVal;
        }

        // Otherwise treat it as an API name and try to resolve via SynapseConfiguration
        if (this.synapseConfig != null) {
            try {
                API api = this.synapseConfig.getAPI(apiVal);
                if (api != null) {
                    String ctx = api.getContext();
                    if (ctx != null) {
                        return ctx;
                    }
                }
            } catch (NoSuchMethodError | Exception e) {
                // Some Synapse versions may not expose getAPI(String) or behavior may differ.
                log.debug("Could not resolve API name '" + apiVal + "' via SynapseConfiguration: " + e.getMessage());
            }
        }

        // Fallback: return the raw value; caller will prepend '/' if needed.
        return apiVal;
    }

    /**
     * Read response body from HttpURLConnection.
     */
    private String readResponse(HttpURLConnection conn) throws Exception {
        // Try success stream first, then error stream
        java.io.InputStream is = null;
        try {
            is = conn.getInputStream();
        } catch (java.io.IOException e) {
            is = conn.getErrorStream();
        }

        if (is == null) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line);
            }
        }
        return sb.toString();
    }
}
