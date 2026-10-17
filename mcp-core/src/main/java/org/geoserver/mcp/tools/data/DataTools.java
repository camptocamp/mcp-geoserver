package org.geoserver.mcp.tools.data;

import io.modelcontextprotocol.spec.McpSchema;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.imageio.ImageIO;
import org.geoserver.catalog.Catalog;
import org.geoserver.catalog.FeatureTypeInfo;
import org.geoserver.catalog.LayerInfo;
import org.geoserver.mcp.http.GeoServerHttpClient;
import org.geoserver.mcp.tool.Args;
import org.geoserver.mcp.tool.GsTool;
import org.geoserver.mcp.tool.Payloads;
import org.geoserver.mcp.tool.Schema;
import org.geoserver.mcp.tool.ToolContext;
import org.geoserver.mcp.tool.ToolException;
import org.geoserver.mcp.tools.catalog.Lookups;
import org.geotools.api.data.FeatureSource;
import org.geotools.api.data.Query;
import org.geotools.api.filter.Filter;
import org.geotools.api.filter.FilterFactory;
import org.geotools.data.geojson.GeoJSONWriter;
import org.geotools.data.simple.SimpleFeatureCollection;
import org.geotools.data.simple.SimpleFeatureIterator;
import org.geotools.factory.CommonFactoryFinder;
import org.geotools.filter.text.cql2.CQLException;
import org.geotools.filter.text.ecql.ECQL;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.geoserver.mcp.tool.Keys;

public class DataTools {
    private static final String HEIGHT = "height";
    private static final String IMAGE_PREFIX = "image/";
    private static final String WIDTH = "width";
    private static final int MAX_IMAGE_DIMENSION = 8192;
    private static final String BAD_BBOX = "bbox needs exactly 4 numbers";
    private static final String BBOX_PARAM = "&BBOX=";
    static final int MAX_GEOJSON_CHARS = 40_000;

    private final Catalog catalog;
    private final GeoServerHttpClient http;

    public DataTools(Catalog catalog, GeoServerHttpClient http) {
        this.catalog = catalog;
        this.http = http;
    }

    public List<GsTool> tools() {
        return List.of(queryFeatures(), getMap(), checkLayer());
    }

    GsTool queryFeatures() {
        return new GsTool() {
            public McpSchema.Tool definition() {
                return McpSchema.Tool.builder("gs_query_features", Schema.object()
                        .string(Keys.LAYER, Keys.DESC_LAYER_NAME, true)
                        .string("cql", "ECQL filter, e.g. NAME = 'Blue Lake' or POPULATION > 1000", false)
                        .numberArray("bbox", "minx, miny, maxx, maxy in the layer's declared SRS", false)
                        .stringArray("properties", "Attributes to return; omit for all", false)
                        .integer("maxFeatures", "Maximum features, default 20, capped by the server maxFeatures setting", false).build())
                        .description("Query a vector layer in process and return GeoJSON in EPSG:4326 (lon/lat). "
                                + "Output stops at maxFeatures or " + MAX_GEOJSON_CHARS + " chars; returned counts features, truncated:true means more matched").build();
            }
            public Object call(ToolContext ctx, Args args) throws Exception {
                LayerInfo layer = Lookups.layer(catalog, args.string(Keys.LAYER));
                if (!(layer.getResource() instanceof FeatureTypeInfo ft)) {
                    throw new ToolException(layerMessage(layer, "is not a vector layer"));
                }
                int max = Math.max(1, Math.min(ctx.settings().maxFeatures(), args.integer("maxFeatures", 20)));
                FilterFactory ff = CommonFactoryFinder.getFilterFactory();
                List<Double> bbox = args.numbers("bbox");
                List<String> props = args.strings("properties");
                Filter filter = and(ff, cqlFilter(args, ff), bboxFilter(ft, bbox, ff));
                ctx.trace().curl("GET", wfsQuery(layer, max, args, bbox, props), null, null);

                FeatureSource<?, ?> fs = ft.getFeatureSource(null, null);
                Query query = new Query(fs.getSchema().getName().getLocalPart(), filter);
                query.setMaxFeatures(max + 1);
                if (!props.isEmpty()) query.setPropertyNames(props);
                Object fc = fs.getFeatures(query);
                if (!(fc instanceof SimpleFeatureCollection sfc)) {
                    throw new ToolException(layerMessage(layer, "is not a simple feature layer; use WFS directly"));
                }
                return new Payloads.RawJson(featureCollection(sfc, max, MAX_GEOJSON_CHARS));
            }
        };
    }

    static String featureCollection(SimpleFeatureCollection sfc, int max, int budget) {
        StringBuilder sb = new StringBuilder("{\"type\":\"FeatureCollection\",\"features\":[");
        int returned = 0;
        boolean truncated = false;
        try (SimpleFeatureIterator it = sfc.features()) {
            while (it.hasNext() && !truncated) {
                String feature = GeoJSONWriter.toGeoJSON(it.next());
                truncated = returned == max || sb.length() + feature.length() > budget;
                if (!truncated) {
                    if (returned > 0) sb.append(',');
                    sb.append(feature);
                    returned++;
                }
            }
        }
        sb.append("],\"returned\":").append(returned);
        if (truncated) sb.append(",\"truncated\":true");
        return sb.append('}').toString();
    }

    static Filter cqlFilter(Args args, FilterFactory ff) {
        if (!args.has("cql")) return Filter.INCLUDE;
        try {
            return ECQL.toFilter(args.string("cql"), ff);
        } catch (CQLException e) {
            throw new ToolException("Invalid CQL: " + e.getMessage());
        }
    }

    static Filter bboxFilter(FeatureTypeInfo ft, List<Double> bbox, FilterFactory ff) throws java.io.IOException {
        if (bbox.isEmpty()) return Filter.INCLUDE;
        if (bbox.size() != 4) throw new ToolException(BAD_BBOX);
        var gd = ft.getFeatureType().getGeometryDescriptor();
        return ff.bbox(ff.property(gd == null ? null : gd.getLocalName()), bbox.get(0), bbox.get(1), bbox.get(2), bbox.get(3), ft.getSRS());
    }

    static Filter and(FilterFactory ff, Filter a, Filter b) {
        if (a == Filter.INCLUDE) return b;
        if (b == Filter.INCLUDE) return a;
        return ff.and(a, b);
    }

    static String wfsQuery(LayerInfo layer, int max, Args args, List<Double> bbox, List<String> props) {
        StringBuilder q = new StringBuilder("/wfs?SERVICE=WFS&VERSION=2.0.0&REQUEST=GetFeature&OUTPUTFORMAT=application/json&COUNT=" + max
                + "&TYPENAMES=" + enc(layer.prefixedName()));
        if (args.has("cql")) q.append("&CQL_FILTER=").append(enc(args.string("cql")));
        if (!bbox.isEmpty()) q.append(BBOX_PARAM).append(bbox.stream().map(String::valueOf).collect(Collectors.joining(",")));
        if (!props.isEmpty()) q.append("&PROPERTYNAME=").append(enc(String.join(",", props)));
        return q.toString();
    }

    GsTool getMap() {
        return new GsTool() {
            public McpSchema.Tool definition() {
                return McpSchema.Tool.builder("gs_get_map", Schema.object()
                        .stringArray(Keys.LAYERS, "Layer or layer group names", true)
                        .numberArray("bbox", "minx, miny, maxx, maxy", true)
                        .integer(WIDTH, "Pixels", true)
                        .integer(HEIGHT, "Pixels", true)
                        .string("srs", "CRS of the bbox, default EPSG:4326", false)
                        .stringArray(Keys.STYLES, "Style per layer; empty string for default", false)
                        .string(Keys.FORMAT, "Image MIME type, default image/png", false)
                        .bool("transparent", null, false).build())
                        .description("Render a WMS GetMap and return the image so you can look at it").build();
            }
            public Object call(ToolContext ctx, Args args) {
                List<Double> bbox = args.numbers("bbox");
                if (bbox.size() != 4) throw new ToolException(BAD_BBOX);
                String layers = String.join(",", args.strings(Keys.LAYERS));
                if (layers.isBlank()) throw new ToolException("At least one layer is required");
                String styles = String.join(",", args.strings(Keys.STYLES));
                String format = args.string(Keys.FORMAT, "image/png");
                String path = "/wms?SERVICE=WMS&VERSION=1.1.1&REQUEST=GetMap"
                        + "&LAYERS=" + enc(layers) + "&STYLES=" + enc(styles)
                        + BBOX_PARAM + bbox.stream().map(String::valueOf).collect(Collectors.joining(","))
                        + "&SRS=" + enc(args.string("srs", "EPSG:4326"))
                        + "&WIDTH=" + dimension(args, WIDTH, 512) + "&HEIGHT=" + dimension(args, HEIGHT, 512)
                        + "&FORMAT=" + enc(format) + "&TRANSPARENT=" + args.bool("transparent", false);
                GeoServerHttpClient.Response r = http.get(ctx, path);
                if (r.status() != 200 || !r.contentType().startsWith(IMAGE_PREFIX)) {
                    String text = r.text().replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
                    throw new ToolException("GetMap failed (HTTP " + r.status() + ", " + r.contentType() + "): " + text);
                }
                return new Payloads.ImagePayload(r.body(), r.contentType().split(";")[0].trim());
            }
        };
    }

    GsTool checkLayer() {
        return new GsTool() {
            public McpSchema.Tool definition() {
                return McpSchema.Tool.builder("gs_check_layer", Schema.object()
                        .string("name", Keys.DESC_LAYER_NAME, true)
                        .numberArray("bbox", "minx, miny, maxx, maxy in EPSG:4326; default the layer's lat/lon bounds", false)
                        .integer(WIDTH, "Pixels, default 256", false)
                        .integer(HEIGHT, "Pixels, default 256", false).build())
                        .description("Text-only GetMap check of one layer: status, content type, ServiceException, painted pixel ratio, elapsed ms. Prefer over gs_get_map to verify rendering").build();
            }
            public Object call(ToolContext ctx, Args args) throws Exception {
                LayerInfo layer = Lookups.layer(catalog, args.string("name"));
                List<Double> bbox = args.numbers("bbox");
                if (bbox.isEmpty()) {
                    ReferencedEnvelope ll = layer.getResource().getLatLonBoundingBox();
                    if (ll == null) throw new ToolException(layerMessage(layer, "has no lat/lon bounds; pass bbox"));
                    bbox = List.of(ll.getMinX(), ll.getMinY(), ll.getMaxX(), ll.getMaxY());
                } else if (bbox.size() != 4) {
                    throw new ToolException(BAD_BBOX);
                }
                int width = dimension(args, WIDTH, 256);
                int height = dimension(args, HEIGHT, 256);
                String path = "/wms?SERVICE=WMS&VERSION=1.1.1&REQUEST=GetMap&LAYERS=" + enc(layer.prefixedName()) + "&STYLES="
                        + BBOX_PARAM + bbox.stream().map(String::valueOf).collect(Collectors.joining(","))
                        + "&SRS=EPSG:4326&WIDTH=" + width + "&HEIGHT=" + height
                        + "&FORMAT=" + enc("image/png") + "&TRANSPARENT=true&EXCEPTIONS=" + enc("application/vnd.ogc.se_xml");
                long start = System.nanoTime();
                GeoServerHttpClient.Response r = http.get(ctx, path);
                long elapsedMs = (System.nanoTime() - start) / 1_000_000;
                String contentType = r.contentType().split(";")[0].trim();
                BufferedImage img = r.status() == 200 && contentType.startsWith(IMAGE_PREFIX) ? ImageIO.read(new ByteArrayInputStream(r.body())) : null;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("ok", img != null);
                m.put(Keys.LAYER, layer.prefixedName());
                m.put("bbox", bbox);
                m.put(WIDTH, width);
                m.put(HEIGHT, height);
                m.put("status", r.status());
                m.put("contentType", contentType);
                m.put("elapsedMs", elapsedMs);
                if (img != null) {
                    m.put("paintedRatio", paintedRatio(img));
                } else {
                    String text = contentType.startsWith(IMAGE_PREFIX) ? "Undecodable image" : r.text().replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
                    m.put("serviceException", text);
                }
                return m;
            }
        };
    }

    static double paintedRatio(BufferedImage img) {
        boolean alpha = img.getColorModel().hasAlpha();
        long painted = 0;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int argb = img.getRGB(x, y);
                if (alpha ? (argb >>> 24) != 0 : (argb & 0xffffff) != 0xffffff) painted++;
            }
        }
        return Math.round(10000.0 * painted / ((long) img.getWidth() * img.getHeight())) / 10000.0;
    }

    static int dimension(Args args, String key, int dflt) {
        int v = args.integer(key, dflt);
        if (v < 1 || v > MAX_IMAGE_DIMENSION) throw new ToolException(key + " must be between 1 and " + MAX_IMAGE_DIMENSION);
        return v;
    }

    static String layerMessage(LayerInfo layer, String problem) {
        return "Layer '" + layer.prefixedName() + "' " + problem;
    }

    static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20").replace("%3A", ":").replace("%2C", ",");
    }
}
