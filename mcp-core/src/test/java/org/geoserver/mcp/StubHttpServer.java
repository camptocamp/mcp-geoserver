package org.geoserver.mcp;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

public class StubHttpServer implements AutoCloseable {
    public record Received(String method, String path, String query, String authorization, String body) {}
    public record Reply(int status, String contentType, byte[] body) {
        public static Reply json(String s) { return new Reply(200, "application/json", s.getBytes(StandardCharsets.UTF_8)); }
        public static Reply of(int status, String contentType, String s) { return new Reply(status, contentType, s.getBytes(StandardCharsets.UTF_8)); }
    }

    private final HttpServer server;
    public final List<Received> received = new ArrayList<>();

    public StubHttpServer(Function<Received, Reply> handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Received r = new Received(ex.getRequestMethod(), ex.getRequestURI().getPath(), ex.getRequestURI().getQuery(),
                    ex.getRequestHeaders().getFirst("Authorization"), body);
            received.add(r);
            Reply reply = handler.apply(r);
            ex.getResponseHeaders().add("Content-Type", reply.contentType());
            ex.sendResponseHeaders(reply.status(), reply.body().length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(reply.body());
            }
        });
        server.start();
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/geoserver";
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
