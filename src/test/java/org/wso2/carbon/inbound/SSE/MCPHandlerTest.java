package org.wso2.carbon.inbound.SSE;

import org.json.JSONObject;
import org.json.JSONArray;
import java.util.HashMap;
import java.util.Map;

/**
 * Direct test of MCPHandler with simulated Synapse tools map
 * Tests tool list and tool data retrieval from a real map structure
 */
public class MCPHandlerTest {
    
    public static void main(String[] args) throws Exception {
        System.out.println("=== MCP Handler with Synapse Tools Map Test ===\n");
        
        // Create a mock tools map like Synapse would have (composite key: "localEntryKey:toolName")
        Map<String, Map<String, Object>> toolsMap = createMockToolsMap();
        System.out.println("✓ Created mock tools map with " + toolsMap.size() + " entries\n");
        
        // Print the map structure for verification
        System.out.println("Tools map structure:");
        for (String key : toolsMap.keySet()) {
            Map<String, Object> toolConfig = toolsMap.get(key);
            System.out.println("  Key: " + key);
            System.out.println("    name: " + toolConfig.get("name"));
            System.out.println("    description: " + toolConfig.get("description"));
        }
        System.out.println();
        
        // Test 1: List tools from the map
        System.out.println("--- Test 1: Tools/List from Map ---");
        JSONArray toolsList = listToolsFromMap(toolsMap, "testMcpEntry");
        System.out.println("Tools found: " + toolsList.length());
        for (int i = 0; i < toolsList.length(); i++) {
            JSONObject tool = toolsList.getJSONObject(i);
            System.out.println("  Tool " + (i+1) + ": " + tool.getString("name"));
            System.out.println("    Description: " + tool.getString("description"));
        }
        
        // Test 2: Get tool data from map
        System.out.println("\n--- Test 2: Get Tool Data from Map ---");
        String toolName = "getUsersTool";
        Map<String, Object> toolData = getToolFromMap(toolsMap, "testMcpEntry", toolName);
        if (toolData != null) {
            System.out.println("✓ Found tool: " + toolData.get("name"));
            System.out.println("  Description: " + toolData.get("description"));
            System.out.println("  API: " + toolData.get("api"));
            System.out.println("  Resource: " + toolData.get("resource"));
            System.out.println("  Method: " + toolData.get("method"));
            System.out.println("  Has input schema: " + toolData.containsKey("inputSchema"));
        } else {
            System.out.println("✗ Tool not found");
        }
        
        // Test 3: Get another tool
        System.out.println("\n--- Test 3: Get Different Tool ---");
        String toolName2 = "createUserTool";
        Map<String, Object> toolData2 = getToolFromMap(toolsMap, "testMcpEntry", toolName2);
        if (toolData2 != null) {
            System.out.println("✓ Found tool: " + toolData2.get("name"));
            System.out.println("  Description: " + toolData2.get("description"));
            System.out.println("  API: " + toolData2.get("api"));
            System.out.println("  Resource: " + toolData2.get("resource"));
            System.out.println("  Method: " + toolData2.get("method"));
        } else {
            System.out.println("✗ Tool not found");
        }
        
        // Test 4: Tool not found
        System.out.println("\n--- Test 4: Tool Not Found ---");
        String nonExistentTool = "nonExistentTool";
        Map<String, Object> toolData3 = getToolFromMap(toolsMap, "testMcpEntry", nonExistentTool);
        if (toolData3 != null) {
            System.out.println("✓ Found tool: " + toolData3.get("name"));
        } else {
            System.out.println("✓ Correctly returned null for non-existent tool: " + nonExistentTool);
        }
        
        // Test 5: Build JSON response from map data
        System.out.println("\n--- Test 5: Build JSON Response from Map ---");
        JSONObject toolsListResponse = buildToolsListResponse(toolsMap, "testMcpEntry", 2);
        System.out.println("Response:");
        System.out.println(toolsListResponse.toString(2));
        
        System.out.println("\n=== All Map-Based Tests Complete ===");
    }
    
   
    private static Map<String, Map<String, Object>> createMockToolsMap() {
        Map<String, Map<String, Object>> toolsMap = new HashMap<>();
        
        // Tool 1: getUsersTool
        Map<String, Object> getUsersTool = new HashMap<>();
        getUsersTool.put("name", "getUsersTool");
        getUsersTool.put("description", "Get list of users");
        getUsersTool.put("api", "TestAPI");
        getUsersTool.put("resource", "/users");
        getUsersTool.put("method", "GET");
        getUsersTool.put("inputSchema", new JSONObject()
            .put("type", "object")
            .put("properties", new JSONObject()
                .put("limit", new JSONObject().put("type", "integer"))
                .put("offset", new JSONObject().put("type", "integer"))));
        toolsMap.put("testMcpEntry:getUsersTool", getUsersTool);
        
        // Tool 2: createUserTool
        Map<String, Object> createUserTool = new HashMap<>();
        createUserTool.put("name", "createUserTool");
        createUserTool.put("description", "Create a new user");
        createUserTool.put("api", "TestAPI");
        createUserTool.put("resource", "/users");
        createUserTool.put("method", "POST");
        createUserTool.put("inputSchema", new JSONObject()
            .put("type", "object")
            .put("properties", new JSONObject()
                .put("name", new JSONObject().put("type", "string"))
                .put("email", new JSONObject().put("type", "string"))));
        toolsMap.put("testMcpEntry:createUserTool", createUserTool);
        
        // Tool 3: getOrdersTool
        Map<String, Object> getOrdersTool = new HashMap<>();
        getOrdersTool.put("name", "getOrdersTool");
        getOrdersTool.put("description", "Get list of orders");
        getOrdersTool.put("api", "TestAPI");
        getOrdersTool.put("resource", "/orders");
        getOrdersTool.put("method", "GET");
        getOrdersTool.put("inputSchema", new JSONObject()
            .put("type", "object")
            .put("properties", new JSONObject()
                .put("userId", new JSONObject().put("type", "string"))));
        toolsMap.put("testMcpEntry:getOrdersTool", getOrdersTool);
        
        // Tool 4: updateOrderTool
        Map<String, Object> updateOrderTool = new HashMap<>();
        updateOrderTool.put("name", "updateOrderTool");
        updateOrderTool.put("description", "Update an order");
        updateOrderTool.put("api", "TestAPI");
        updateOrderTool.put("resource", "/orders/{id}");
        updateOrderTool.put("method", "PUT");
        updateOrderTool.put("inputSchema", new JSONObject()
            .put("type", "object")
            .put("properties", new JSONObject()
                .put("status", new JSONObject().put("type", "string"))
                .put("notes", new JSONObject().put("type", "string"))));
        toolsMap.put("testMcpEntry:updateOrderTool", updateOrderTool);
        
        return toolsMap;
    }
    
    /**
     * List all tools from the map for a given local entry key
     */
    private static JSONArray listToolsFromMap(Map<String, Map<String, Object>> toolsMap, String localEntryKey) {
        JSONArray tools = new JSONArray();
        String filterPrefix = localEntryKey + ":";
        
        for (Map.Entry<String, Map<String, Object>> entry : toolsMap.entrySet()) {
            if (entry.getKey().startsWith(filterPrefix)) {
                Map<String, Object> toolConfig = entry.getValue();
                JSONObject tool = new JSONObject();
                tool.put("name", toolConfig.get("name"));
                tool.put("description", toolConfig.get("description"));
                tool.put("inputSchema", toolConfig.get("inputSchema"));
                tools.put(tool);
            }
        }
        
        return tools;
    }
    
    /**
     * Get a specific tool from the map by name
     */
    private static Map<String, Object> getToolFromMap(Map<String, Map<String, Object>> toolsMap, 
                                                        String localEntryKey, String toolName) {
        String filterPrefix = localEntryKey + ":";
        
        for (Map.Entry<String, Map<String, Object>> entry : toolsMap.entrySet()) {
            if (entry.getKey().startsWith(filterPrefix)) {
                Map<String, Object> config = entry.getValue();
                if (toolName.equals(config.get("name"))) {
                    return config;
                }
            }
        }
        
        return null;
    }
    
    /**
     * Build a JSON-RPC response with tools list from the map
     */
    private static JSONObject buildToolsListResponse(Map<String, Map<String, Object>> toolsMap, 
                                                      String localEntryKey, long requestId) {
        JSONArray tools = listToolsFromMap(toolsMap, localEntryKey);
        
        JSONObject result = new JSONObject();
        result.put("tools", tools);
        
        JSONObject response = new JSONObject();
        response.put("jsonrpc", "2.0");
        response.put("id", requestId);
        response.put("result", result);
        
        return response;
    }
}
