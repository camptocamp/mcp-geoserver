package org.geoserver.mcp.http;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;
import org.geoserver.config.GeoServer;
import org.geoserver.mcp.settings.McpSettingsStore;
import org.geoserver.mcp.tool.ToolContext;
import org.geoserver.mcp.tool.ToolException;

public class GeoServerHttpClient {
    public record Response(int status, String contentType, byte[] body) {
        public String text() {
            return new String(body, StandardCharsets.UTF_8);
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Response other && status == other.status && Objects.equals(contentType, other.contentType) && Arrays.equals(body, other.body);
        }

        @Override
        public int hashCode() {
            return 31 * Objects.hash(status, contentType) + Arrays.hashCode(body);
        }

        @Override
        public String toString() {
            return "Response[status=" + status + ", contentType=" + contentType + ", body=" + (body == null ? "null" : body.length + " bytes") + "]";
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
        // No trusted base URL configured: the request URL is built from the client Host header,
        // so forwarding the caller's Authorization to it would be SSRF + credential exfiltration.
        // Only accept it when it targets loopback; otherwise require an explicit mcp.internalBaseUrl.
        String request = strip(ctx.requestBaseUrl());
        if (!isLoopback(hostOf(request))) {
            throw new ToolException("refusing to call GeoServer at '" + request
                    + "' derived from the request Host header; set mcp.internalBaseUrl to the server's own base URL");
        }
        return request;
    }

    private static String hostOf(String url) {
        try {
            return URI.create(url).getHost();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static boolean isLoopback(String host) {
        if (host == null) return false;
        String h = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        if (h.equalsIgnoreCase("localhost")) return true;
        if (!h.matches("[0-9.]+|[0-9a-fA-F:]+")) return false; // only resolve IP literals, never DNS names
        try {
            return InetAddress.getByName(h).isLoopbackAddress();
        } catch (UnknownHostException e) {
            return false;
        }
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
            throw new ToolException("HTTP " + method + " " + url + " failed: " + e.getMessage()
                    + "; set mcp.internalBaseUrl if GeoServer cannot reach itself at this URL");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ToolException("HTTP " + method + " " + url + " interrupted");
        }
    }
}
