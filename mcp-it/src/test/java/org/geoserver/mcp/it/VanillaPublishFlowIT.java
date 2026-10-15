package org.geoserver.mcp.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.image.BufferedImage;
import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import org.geotools.coverage.grid.GridCoverage2D;
import org.geotools.coverage.grid.GridCoverageFactory;
import org.geotools.gce.geotiff.GeoTiffWriter;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.geotools.referencing.crs.DefaultGeographicCRS;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class VanillaPublishFlowIT {
    // Initialised before the container field: Docker would create a missing bind source as root.
    static final Path DATA = writeGeoTiff(Path.of("target/it-data").toAbsolutePath());
    static final ObjectMapper JSON = new ObjectMapper();
    static final String AUTH = "Basic " + Base64.getEncoder().encodeToString("admin:geoserver".getBytes());

    @Container
    static GenericContainer<?> geoserver = new GenericContainer<>("docker.osgeo.org/geoserver:3.0.1")
            .withExposedPorts(8080)
            .withEnv("SKIP_DEMO_DATA", "true")
            .withEnv("EXTRA_JAVA_OPTS", "-Xmx1g")
            .withFileSystemBind(Path.of("../docker/vanilla/additional_libs").toAbsolutePath().normalize().toString(), "/opt/additional_libs", BindMode.READ_ONLY)
            .withFileSystemBind(DATA.toString(), "/opt/it-data", BindMode.READ_ONLY)
            .waitingFor(Wait.forHttp("/geoserver/rest/about/version.json").withBasicCredentials("admin", "geoserver").forStatusCode(200).withStartupTimeout(Duration.ofMinutes(5)));

    static String base() {
        return "http://" + geoserver.getHost() + ":" + geoserver.getMappedPort(8080) + "/geoserver";
    }

    static Path writeGeoTiff(Path dir) {
        try {
            Files.createDirectories(dir);
            BufferedImage img = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
            for (int x = 0; x < 64; x++) for (int y = 0; y < 64; y++) img.setRGB(x, y, (x * 4) << 16 | (y * 4) << 8);
            ReferencedEnvelope env = new ReferencedEnvelope(0, 10, 40, 50, DefaultGeographicCRS.WGS84);
            GridCoverage2D cov = new GridCoverageFactory().create("it", img, env);
            try (var w = new GeoTiffWriterCloseable(new File(dir.toFile(), "sample.tif"))) {
                w.writer.write(cov, null);
            }
            return dir;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot write the test GeoTIFF", e);
        }
    }

    static class GeoTiffWriterCloseable implements AutoCloseable {
        final GeoTiffWriter writer;
        GeoTiffWriterCloseable(File f) throws Exception { writer = new GeoTiffWriter(f); }
        public void close() { writer.dispose(); }
    }

    static HttpResponse<byte[]> http(String method, String path, String contentType, String body, String accept) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base() + path)).header("Authorization", AUTH);
        if (contentType != null) b.header("Content-Type", contentType);
        if (accept != null) b.header("Accept", accept);
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    static JsonNode call(String tool, Map<String, Object> args) throws Exception {
        String body = JSON.writeValueAsString(Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/call",
                "params", Map.of("name", tool, "arguments", args)));
        HttpResponse<byte[]> r = http("POST", "/mcp", "application/json", body, "application/json, text/event-stream");
        assertEquals(200, r.statusCode(), new String(r.body()));
        JsonNode result = JSON.readTree(r.body()).get("result");
        assertFalse(result.path("isError").asBoolean(false), result.toString());
        return result;
    }

    static JsonNode text(JsonNode result) throws Exception {
        return JSON.readTree(result.get("content").get(0).get("text").asText());
    }

    @Test
    void publishGeoTiffStyleAndRender() throws Exception {
        assertEquals(503, http("POST", "/mcp", "application/json", "{\"jsonrpc\":\"2.0\",\"id\":0,\"method\":\"tools/list\",\"params\":{}}",
                "application/json, text/event-stream").statusCode());
        assertEquals(200, http("PUT", "/rest/mcp/settings", "application/json",
                "{\"enabled\":true,\"verbose\":true,\"internalBaseUrl\":\"http://localhost:8080/geoserver\"}", null).statusCode());

        JsonNode ws = text(call("gs_create_workspace", Map.of("name", "it")));
        assertTrue(ws.get("created").asBoolean());

        JsonNode store = text(call("gs_create_store", Map.of("workspace", "it", "name", "sample", "type", "GeoTIFF",
                "params", Map.of("url", "file:///opt/it-data/sample.tif"))));
        assertEquals("raster", store.get("kind").asText());

        JsonNode layer = text(call("gs_publish_layer", Map.of("workspace", "it", "store", "sample", "name", "sample")));
        assertEquals("it:sample", layer.get("name").asText());

        String sld = "<?xml version=\"1.0\"?><StyledLayerDescriptor version=\"1.0.0\" xmlns=\"http://www.opengis.net/sld\">"
                + "<NamedLayer><Name>sample</Name><UserStyle><FeatureTypeStyle><Rule><RasterSymbolizer><Opacity>0.8</Opacity>"
                + "</RasterSymbolizer></Rule></FeatureTypeStyle></UserStyle></NamedLayer></StyledLayerDescriptor>";
        text(call("gs_put_style", Map.of("name", "sample-style", "workspace", "it", "body", sld)));
        JsonNode updated = text(call("gs_update_layer", Map.of("name", "it:sample", "defaultStyle", "it:sample-style")));
        assertEquals("it:sample-style", updated.get("defaultStyle").asText());

        JsonNode map = call("gs_get_map", Map.of("layers", java.util.List.of("it:sample"), "bbox", java.util.List.of(0, 40, 10, 50),
                "width", 128, "height", 128));
        JsonNode image = map.get("content").get(0);
        assertEquals("image", image.get("type").asText());
        byte[] png = Base64.getDecoder().decode(image.get("data").asText());
        assertEquals((byte) 0x89, png[0]);
        assertEquals('P', png[1]);
        assertTrue(map.get("content").get(1).get("text").asText().startsWith("Equivalent commands:"));

        JsonNode tiles = text(call("gs_gwc_list_tile_layers", Map.of()));
        assertTrue(tiles.get("tileLayers").toString().contains("it:sample"));

        JsonNode tileLayer = text(call("gs_gwc_set_tile_layer", Map.of("layer", "it:sample", "enabled", true,
                "formats", java.util.List.of("image/png"))));
        assertTrue(tileLayer.get("updated").asBoolean());
        assertTrue(text(call("gs_gwc_get_tile_layer", Map.of("layer", "it:sample"))).get("mimeFormats").toString().contains("image/png"));

        JsonNode features = text(call("gs_list_store_types", Map.of("kind", "raster")));
        assertTrue(features.get("raster").toString().contains("GeoTIFF"));
    }
}
