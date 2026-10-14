package org.geoserver.mcp.tools.data;

import io.modelcontextprotocol.spec.McpSchema;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;
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
import org.geotools.factory.CommonFactoryFinder;
import org.geotools.filter.text.cql2.CQLException;
import org.geotools.filter.text.ecql.ECQL;

public class DataTools {
    private final Catalog catalog;
    private final GeoServerHttpClient http;

    public DataTools(Catalog catalog, GeoServerHttpClient http) {
        this.catalog = catalog;
        this.http = http;
    }

    public List<GsTool> tools() {
        return List.of(queryFeatures(), getMap());
    }

    GsTool queryFeatures() {
        return new GsTool() {
            public McpSchema.Tool definition() {
                return McpSchema.Tool.builder("gs_query_features", Schema.object()
                        .string("layer", "Vector layer, workspace:name", true)
                        .string("cql", "ECQL filter, e.g. NAME = 'Blue Lake' or POPULATION > 1000", false)
                        .numberArray("bbox", "minx, miny, maxx, maxy in the layer's declared SRS", false)
                        .stringArray("properties", "Attributes to return; omit for all", false)
                        .integer("maxFeatures", "Maximum features, default 100", false).build())
                        .description("Query a vector layer in process and return GeoJSON").build();
            }
            public Object call(ToolContext ctx, Args args) throws Exception {
                LayerInfo layer = Lookups.layer(catalog, args.string("layer"));
                if (!(layer.getResource() instanceof FeatureTypeInfo ft)) {
                    throw new ToolException("Layer '" + layer.prefixedName() + "' is not a vector layer");
                }
                int max = args.integer("maxFeatures", 100);
                FilterFactory ff = CommonFactoryFinder.getFilterFactory();
                Filter filter = Filter.INCLUDE;
                if (args.has("cql")) {
                    try {
                        filter = ECQL.toFilter(args.string("cql"), ff);
                    } catch (CQLException e) {
                        throw new ToolException("Invalid CQL: " + e.getMessage());
                    }
                }
                StringBuilder q = new StringBuilder("/wfs?SERVICE=WFS&VERSION=2.0.0&REQUEST=GetFeature&OUTPUTFORMAT=application/json&COUNT=" + max
                        + "&TYPENAMES=" + enc(layer.prefixedName()));
                if (args.has("cql")) q.append("&CQL_FILTER=").append(enc(args.string("cql")));
                List<Double> bbox = args.numbers("bbox");
                if (!bbox.isEmpty()) {
                    if (bbox.size() != 4) throw new ToolException("bbox needs exactly 4 numbers");
                    String geom = ft.getFeatureType().getGeometryDescriptor() == null ? null : ft.getFeatureType().getGeometryDescriptor().getLocalName();
                    Filter bb = ff.bbox(ff.property(geom), bbox.get(0), bbox.get(1), bbox.get(2), bbox.get(3), ft.getSRS());
                    filter = filter == Filter.INCLUDE ? bb : ff.and(filter, bb);
                    q.append("&BBOX=").append(bbox.stream().map(String::valueOf).collect(Collectors.joining(",")));
                }
                List<String> props = args.strings("properties");
                if (!props.isEmpty()) q.append("&PROPERTYNAME=").append(enc(String.join(",", props)));
                ctx.trace().curl("GET", q.toString(), null, null);

                FeatureSource<?, ?> fs = ft.getFeatureSource(null, null);
                Query query = new Query(fs.getSchema().getName().getLocalPart(), filter);
                query.setMaxFeatures(max);
                if (!props.isEmpty()) query.setPropertyNames(props);
                Object fc = fs.getFeatures(query);
                if (!(fc instanceof SimpleFeatureCollection sfc)) {
                    throw new ToolException("Layer '" + layer.prefixedName() + "' is not a simple feature layer; use WFS directly");
                }
                return new Payloads.RawJson(GeoJSONWriter.toGeoJSON(sfc));
            }
        };
    }

    GsTool getMap() {
        return new GsTool() {
            public McpSchema.Tool definition() {
                return McpSchema.Tool.builder("gs_get_map", Schema.object()
                        .stringArray("layers", "Layer or layer group names", true)
                        .numberArray("bbox", "minx, miny, maxx, maxy", true)
                        .integer("width", "Image width in pixels", true)
                        .integer("height", "Image height in pixels", true)
                        .string("srs", "CRS of the bbox, default EPSG:4326", false)
                        .stringArray("styles", "Style per layer; empty string for default", false)
                        .string("format", "Image MIME type, default image/png", false)
                        .bool("transparent", "Transparent background", false).build())
                        .description("Render a WMS GetMap and return the image so you can look at it").build();
            }
            public Object call(ToolContext ctx, Args args) {
                List<Double> bbox = args.numbers("bbox");
                if (bbox.size() != 4) throw new ToolException("bbox needs exactly 4 numbers");
                String layers = String.join(",", args.strings("layers"));
                if (layers.isBlank()) throw new ToolException("At least one layer is required");
                String styles = String.join(",", args.strings("styles"));
                String format = args.string("format", "image/png");
                String path = "/wms?SERVICE=WMS&VERSION=1.1.1&REQUEST=GetMap"
                        + "&LAYERS=" + enc(layers) + "&STYLES=" + enc(styles)
                        + "&BBOX=" + bbox.stream().map(String::valueOf).collect(Collectors.joining(","))
                        + "&SRS=" + enc(args.string("srs", "EPSG:4326"))
                        + "&WIDTH=" + args.integer("width", 512) + "&HEIGHT=" + args.integer("height", 512)
                        + "&FORMAT=" + enc(format) + "&TRANSPARENT=" + args.bool("transparent", false);
                GeoServerHttpClient.Response r = http.get(ctx, path);
                if (r.status() != 200 || !r.contentType().startsWith("image/")) {
                    String text = r.text().replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
                    throw new ToolException("GetMap failed (HTTP " + r.status() + ", " + r.contentType() + "): " + text);
                }
                return new Payloads.ImagePayload(r.body(), r.contentType().split(";")[0].trim());
            }
        };
    }

    static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20").replace("%3A", ":").replace("%2C", ",");
    }
}
