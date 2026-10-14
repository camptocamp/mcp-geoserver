package org.geoserver.mcp.http;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.geoserver.config.GeoServer;
import org.geoserver.mcp.settings.McpSettingsStore;
import org.geoserver.mcp.tool.ToolContext;
import org.geoserver.mcp.tool.ToolException;

public class GeoServerHttpClient {
    public record Response(int status, String contentType, byte[] body) {
        public String text() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    private final GeoServer geoServer;
    private final McpSettingsStore settings;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public GeoServerHttpClient(GeoServer geoServer, McpSettingsStore settings) {
        this.geoServer = geoServer;
        this.settings = settings;
    }

    public String baseUrl(ToolContext ctx) {
        String internal = settings.load().internalBaseUrl();
        if (!internal.isBlank()) return strip(internal);
        String proxy = geoServer.getGlobal().getSettings().getProxyBaseUrl();
        if (proxy != null && !proxy.isBlank()) return strip(proxy);
        return strip(ctx.requestBaseUrl());
    }

    private static String strip(String url) {
        String u = url.trim();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u;
    }

    public Response get(ToolContext ctx, String path) {
        return send(ctx, "GET", path, null, null);
    }

    public Response send(ToolContext ctx, String method, String path, String contentType, byte[] body) {
        ctx.trace().curl(method, path, contentType, body == null ? null : new String(body, StandardCharsets.UTF_8));
        String url = baseUrl(ctx) + path;
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(2));
        if (ctx.authorization() != null) b.header("Authorization", ctx.authorization());
        if (contentType != null) b.header("Content-Type", contentType);
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        try {
            HttpResponse<byte[]> r = http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
            String ct = r.headers().firstValue("Content-Type").orElse("application/octet-stream");
            return new Response(r.statusCode(), ct, r.body());
        } catch (IOException e) {
            throw new ToolException("HTTP " + method + " " + url + " failed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ToolException("HTTP " + method + " " + url + " interrupted");
        }
    }
}
