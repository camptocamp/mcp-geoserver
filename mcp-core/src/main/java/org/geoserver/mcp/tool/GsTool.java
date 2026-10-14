package org.geoserver.mcp.tool;

import io.modelcontextprotocol.spec.McpSchema;

public interface GsTool {
    McpSchema.Tool definition();

    Object call(ToolContext ctx, Args args) throws Exception;
}
