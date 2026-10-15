package org.geoserver.mcp.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpStatelessSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStatelessServerTransport;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.geoserver.mcp.Version;
import org.geoserver.mcp.settings.McpSettingsStore;
import org.geoserver.mcp.tool.GsTool;
import org.geoserver.mcp.tool.ToolAdapter;
import org.geoserver.mcp.tool.ToolContext;

public class McpServerHolder {
    private final HttpServletStatelessServerTransport transport;
    private final McpStatelessSyncServer server;

    public McpServerHolder(List<GsTool> tools, McpSettingsStore settings, ObjectMapper json) {
        McpJsonMapper mapper = new JacksonMcpJsonMapper(json);
        this.transport = HttpServletStatelessServerTransport.builder()
                .jsonMapper(mapper)
                .messageEndpoint("/mcp")
                .contextExtractor(McpServerHolder::context)
                .build();
        this.server = McpServer.sync(transport)
                .serverInfo("geoserver-mcp", Version.current())
                .instructions("Tools for administering this GeoServer. Names are qualified as workspace:name.")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                .tools(tools.stream().map(t -> ToolAdapter.spec(t, settings, json)).toList())
                .jsonMapper(mapper)
                .requestTimeout(Duration.ofMinutes(5))
                // tools must run on the request thread: SecurityContextHolder is thread-local
                .immediateExecution(true)
                .build();
    }

    static McpTransportContext context(HttpServletRequest req) {
        Map<String, Object> ctx = new HashMap<>();
        String auth = req.getHeader("Authorization");
        if (auth != null) ctx.put(ToolContext.AUTHORIZATION_KEY, auth);
        ctx.put(ToolContext.REQUEST_BASE_URL_KEY, baseUrl(req));
        return McpTransportContext.create(ctx);
    }

    public static String baseUrl(HttpServletRequest req) {
        String scheme = req.getScheme();
        int port = req.getServerPort();
        boolean dflt = ("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443) || port <= 0;
        return scheme + "://" + req.getServerName() + (dflt ? "" : ":" + port) + req.getContextPath();
    }

    public HttpServletStatelessServerTransport transport() {
        return transport;
    }

    public void close() {
        server.closeGracefully();
    }
}
