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

    public String execute(Map<String, Object> toolDefinition, JSONObject arguments)
            throws McpToolExecutionException {
        if (toolDefinition == null) {
            throw new McpToolExecutionException("Tool definition is null");
        }

        String method = (String) toolDefinition.getOrDefault("method", "POST");
        String resource = (String) toolDefinition.getOrDefault("resource", "/");
        
        String apiContext = resolveApiContext(toolDefinition);

        String path = substitutePathParameters(resource, arguments);
        if (apiContext != null && !apiContext.isEmpty() && !apiContext.equals("/")) {
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

        if ("GET".equalsIgnoreCase(method)) {
            java.util.Set<String> pathParams = new java.util.HashSet<>(extractPathParamNames(resource));
            String query = buildQueryString(arguments, pathParams);
            if (query != null && !query.isEmpty()) {
                url = url + "?" + query;
            }
        } else {
            if (arguments.has("payload")) {
                Object payloadVal = arguments.opt("payload");
                body = (payloadVal != null) ? payloadVal.toString() : null;
            } else {
                java.util.Set<String> pathParams = new java.util.HashSet<>(extractPathParamNames(resource));
                JSONObject bodyOnly = new JSONObject(arguments.toString());
                for (String param : pathParams) {
                    bodyOnly.remove(param);
                }
                body = bodyOnly.toString();
            }
        }

        if (log.isDebugEnabled()) {
            log.debug("Executing tool: " + method + " " + url + (body != null ? " with body: " + body : ""));
        }

        return sendHttpRequest(method, url, body);
    }

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

    private String substitutePathParameters(String resource, JSONObject arguments) {
        String result = resource;
        Iterator<String> keys = arguments.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object value = arguments.opt(key);
            if (value != null) {
                String placeholder = "{" + key + "}";
                try {
                    result = result.replace(placeholder, URLEncoder.encode(value.toString(), "UTF-8"));
                } catch (Exception e) {
                    log.warn("Failed to encode path parameter " + key + "; using raw value", e);
                    result = result.replace(placeholder, value.toString());
                }
            }
        }
        return result;
    }

    private String buildQueryString(JSONObject arguments, java.util.Set<String> exclude) {
        StringBuilder sb = new StringBuilder();
        Iterator<String> keys = arguments.keys();
        boolean first = true;
        while (keys.hasNext()) {
            String key = keys.next();
            if (exclude != null && exclude.contains(key)) {
                continue;
            }
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

    private String sendHttpRequest(String method, String urlStr, String body)
            throws McpToolExecutionException {
        try {
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod(method);
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "application/json");

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

        if (apiVal.startsWith("/")) {
            return apiVal;
        }

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
                log.debug("Could not resolve API name '" + apiVal + "' via SynapseConfiguration: " + e.getMessage());
            }
        }
        return apiVal;
    }

    private String readResponse(HttpURLConnection conn) throws Exception {
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
