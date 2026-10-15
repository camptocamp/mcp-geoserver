package org.geoserver.mcp.tools.gwc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.geoserver.mcp.http.GeoServerHttpClient;
import org.geoserver.mcp.tool.Args;
import org.geoserver.mcp.tool.GsTool;
import org.geoserver.mcp.tool.Schema;
import org.geoserver.mcp.tool.ToolContext;
import org.geoserver.mcp.tool.ToolException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.geoserver.mcp.tool.Keys;
import org.geoserver.mcp.tool.SafeXml;

public class GwcTools {
    private static final String DESC_TILE_LAYER = "Tile layer name, workspace:name";
    private static final String EXPIRE_CACHE = "expireCache";
    private static final String EXPIRE_CLIENTS = "expireClients";
    private static final String FORMATS = "formats";
    private static final String GRIDSETS = "gridsets";
    private static final String GRID_SET_NAME = "gridSetName";
    private static final String GRID_SUBSETS = "gridSubsets";
    private static final String GWC_SEED_PATH = "/gwc/rest/seed/";
    private static final String META_WIDTH_HEIGHT = "metaWidthHeight";
    private static final String MIME_FORMATS = "mimeFormats";
    private static final String OPERATION = "operation";
    private static final String WHICH = "which";
    private static final String ZOOM_START = "zoomStart";
    private static final String ZOOM_STOP = "zoomStop";
    private static final int MAX_ZOOM = 31;
    private static final int MAX_SEED_THREADS = 16;

    private final GeoServerHttpClient http;
    private final ObjectMapper json;

    public GwcTools(GeoServerHttpClient http, ObjectMapper json) {
        this.http = http;
        this.json = json;
    }

    public List<GsTool> tools() {
        return List.of(listTileLayers(), getTileLayer(), setTileLayer(), listGridsets(), seed(), listTasks(), killTasks());
    }

    JsonNode getJson(ToolContext ctx, String path) {
        GeoServerHttpClient.Response r = http.get(ctx, path);
        if (r.status() != 200) throw new ToolException("GWC " + path + " returned HTTP " + r.status() + ": " + r.text().trim());
        try {
            return json.readTree(r.body());
        } catch (Exception e) {
            throw new ToolException("GWC " + path + " returned unparseable JSON: " + e.getMessage());
        }
    }

    void expectOk(GeoServerHttpClient.Response r, String what) {
        if (r.status() < 200 || r.status() >= 300) {
            throw new ToolException(what + " failed with HTTP " + r.status() + ": " + r.text().trim());
        }
    }

    // A tile layer name (workspace:name) going into a REST path segment: block / ? # so a
    // crafted value cannot traverse or alter the request path. Keep ':' which is legitimate.
    static String seg(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20").replace("%3A", ":");
    }

    static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        if (array != null && array.isArray()) array.forEach(n -> out.add(n.asText()));
        return out;
    }

    GsTool listTileLayers() {
        return new GsTool() {
            public McpSchema.Tool definition() {
                return McpSchema.Tool.builder("gs_gwc_list_tile_layers", Schema.object().build())
                        .description("List GeoWebCache tile layer names").build();
            }
            public Object call(ToolContext ctx, Args args) {
                return Map.of("tileLayers", strings(getJson(ctx, "/gwc/rest/layers.json")));
            }
        };
    }

    Map<String, Object> tileLayerView(JsonNode doc) {
        JsonNode l = doc.has("GeoServerLayer") ? doc.get("GeoServerLayer") : doc;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", l.path("name").asText(null));
        m.put(Keys.ENABLED, l.path(Keys.ENABLED).asBoolean(true));
        m.put(MIME_FORMATS, strings(l.get(MIME_FORMATS)));
        List<Map<String, Object>> subsets = new ArrayList<>();
        if (l.has(GRID_SUBSETS)) {
            for (JsonNode s : l.get(GRID_SUBSETS)) {
                Map<String, Object> sm = new LinkedHashMap<>();
                sm.put(GRID_SET_NAME, s.path(GRID_SET_NAME).asText(null));
                if (s.has(ZOOM_START)) sm.put(ZOOM_START, s.get(ZOOM_START).asInt());
                if (s.has(ZOOM_STOP)) sm.put(ZOOM_STOP, s.get(ZOOM_STOP).asInt());
                subsets.add(sm);
            }
        }
        m.put(GRID_SUBSETS, subsets);
        if (l.has(META_WIDTH_HEIGHT)) m.put(META_WIDTH_HEIGHT, strings(l.get(META_WIDTH_HEIGHT)));
        if (l.has(EXPIRE_CACHE)) m.put(EXPIRE_CACHE, l.get(EXPIRE_CACHE).asInt());
        if (l.has(EXPIRE_CLIENTS)) m.put(EXPIRE_CLIENTS, l.get(EXPIRE_CLIENTS).asInt());
        return m;
    }

    GsTool getTileLayer() {
        return new GsTool() {
            public McpSchema.Tool definition() {
                return McpSchema.Tool.builder("gs_gwc_get_tile_layer", Schema.object().string(Keys.LAYER, DESC_TILE_LAYER, true).build())
                        .description("Tile layer configuration: enabled flag, formats, gridsets with zoom ranges").build();
            }
            public Object call(ToolContext ctx, Args args) {
                return tileLayerView(getJson(ctx, "/gwc/rest/layers/" + seg(args.string(Keys.LAYER)) + Keys.JSON_EXT));
            }
        };
    }

    GsTool setTileLayer() {
        return new GsTool() {
            public McpSchema.Tool definition() {
                return McpSchema.Tool.builder("gs_gwc_set_tile_layer", Schema.object()
                        .string(Keys.LAYER, DESC_TILE_LAYER, true)
                        .bool(Keys.ENABLED, "Enable or disable tile caching", false)
                        .stringArray(GRIDSETS, "Gridset names to cache, replaces the list", false)
                        .stringArray(FORMATS, "MIME formats to cache, replaces the list", false).build())
                        .description("Update a tile layer's enabled flag, gridsets and formats").build();
            }
            public Object call(ToolContext ctx, Args args) throws Exception {
                String layer = args.string(Keys.LAYER);
                String path = "/gwc/rest/layers/" + seg(layer) + ".xml";
                GeoServerHttpClient.Response current = http.get(ctx, path);
                if (current.status() != 200) throw new ToolException("GWC " + path + " returned HTTP " + current.status() + ": " + current.text().trim());
                Document doc = SafeXml.parse(current.body());
                Element root = doc.getDocumentElement();
                applyChanges(doc, root, args);
                expectOk(http.send(ctx, "PUT", path, "text/xml", SafeXml.write(doc).getBytes(StandardCharsets.UTF_8)), "Tile layer update");
                ObjectNode view = xmlView(root);
                Map<String, Object> m = new LinkedHashMap<>(tileLayerView(view));
                m.put("updated", true);
                return m;
            }
        };
    }

    static void applyChanges(Document doc, Element root, Args args) {
        if (args.has(Keys.ENABLED)) child(root, Keys.ENABLED).setTextContent(String.valueOf(args.bool(Keys.ENABLED, true)));
        if (args.has(FORMATS)) {
            Element f = replaceChild(doc, root, MIME_FORMATS);
            for (String m : args.strings(FORMATS)) f.appendChild(doc.createElement("string")).setTextContent(m);
        }
        if (args.has(GRIDSETS)) {
            Element g = replaceChild(doc, root, GRID_SUBSETS);
            for (String name : args.strings(GRIDSETS)) {
                Element sub = (Element) g.appendChild(doc.createElement("gridSubset"));
                sub.appendChild(doc.createElement(GRID_SET_NAME)).setTextContent(name);
            }
        }
    }

    ObjectNode xmlView(Element root) {
        ObjectNode view = json.createObjectNode();
        view.put("name", root.getElementsByTagName("name").item(0).getTextContent());
        view.put(Keys.ENABLED, Boolean.parseBoolean(child(root, Keys.ENABLED).getTextContent()));
        ArrayNode formats = view.putArray(MIME_FORMATS);
        NodeList strings = child(root, MIME_FORMATS).getElementsByTagName("string");
        for (int i = 0; i < strings.getLength(); i++) formats.add(strings.item(i).getTextContent());
        ArrayNode subsets = view.putArray(GRID_SUBSETS);
        NodeList names = child(root, GRID_SUBSETS).getElementsByTagName(GRID_SET_NAME);
        for (int i = 0; i < names.getLength(); i++) subsets.addObject().put(GRID_SET_NAME, names.item(i).getTextContent());
        return view;
    }

    static Element child(Element parent, String name) {
        NodeList n = parent.getElementsByTagName(name);
        if (n.getLength() == 0) throw new ToolException("Tile layer document has no <" + name + ">");
        return (Element) n.item(0);
    }

    static Element replaceChild(Document doc, Element root, String name) {
        Element fresh = doc.createElement(name);
        NodeList old = root.getElementsByTagName(name);
        if (old.getLength() > 0) root.replaceChild(fresh, old.item(0));
        else root.appendChild(fresh);
        return fresh;
    }

    GsTool listGridsets() {
        return new GsTool() {
            public McpSchema.Tool definition() {
                return McpSchema.Tool.builder("gs_gwc_list_gridsets", Schema.object().build())
                        .description("List GeoWebCache gridset names").build();
            }
            public Object call(ToolContext ctx, Args args) {
                return Map.of(GRIDSETS, strings(getJson(ctx, "/gwc/rest/gridsets.json")));
            }
        };
    }

    GsTool seed() {
        return new GsTool() {
            public McpSchema.Tool definition() {
                return McpSchema.Tool.builder("gs_gwc_seed", Schema.object()
                        .string(Keys.LAYER, DESC_TILE_LAYER, true)
                        .string("gridset", "Gridset name, e.g. EPSG:4326 or EPSG:900913", true)
                        .integer(ZOOM_START, "First zoom level", true)
                        .integer(ZOOM_STOP, "Last zoom level", true)
                        .string(Keys.FORMAT, "Tile MIME type, e.g. image/png", true)
                        .enumeration(OPERATION, "seed, reseed or truncate", false, "seed", "reseed", "truncate")
                        .integer("threads", "Thread count, default 1", false)
                        .numberArray("bbox", "minx, miny, maxx, maxy in the gridset CRS; omit for the whole layer", false).build())
                        .description("Submit a seed, reseed or truncate job. Returns immediately; poll gs_gwc_list_tasks.").build();
            }
            public Object call(ToolContext ctx, Args args) throws Exception {
                String layer = args.string(Keys.LAYER);
                int zoomStart = args.integer(ZOOM_START, 0);
                int zoomStop = args.integer(ZOOM_STOP, 0);
                if (zoomStart < 0 || zoomStop > MAX_ZOOM || zoomStop < zoomStart) {
                    throw new ToolException(ZOOM_START + "/" + ZOOM_STOP + " must satisfy 0 <= " + ZOOM_START + " <= " + ZOOM_STOP + " <= " + MAX_ZOOM);
                }
                int threads = args.integer("threads", 1);
                if (threads < 1 || threads > MAX_SEED_THREADS) throw new ToolException("threads must be between 1 and " + MAX_SEED_THREADS);
                ObjectNode req = json.createObjectNode();
                req.put("name", layer);
                req.put("gridSetId", args.string("gridset"));
                req.put(ZOOM_START, zoomStart);
                req.put(ZOOM_STOP, zoomStop);
                req.put(Keys.FORMAT, args.string(Keys.FORMAT));
                req.put("type", args.string(OPERATION, "seed"));
                req.put("threadCount", threads);
                List<Double> bbox = args.numbers("bbox");
                if (!bbox.isEmpty()) {
                    if (bbox.size() != 4) throw new ToolException("bbox needs exactly 4 numbers");
                    ArrayNode coords = req.putObject("bounds").putObject("coords").putArray("double");
                    bbox.forEach(coords::add);
                }
                ObjectNode wrapper = json.createObjectNode();
                wrapper.set("seedRequest", req);
                expectOk(http.send(ctx, "POST", GWC_SEED_PATH + seg(layer) + Keys.JSON_EXT, Keys.APPLICATION_JSON, json.writeValueAsBytes(wrapper)), "Seed request");
                return Map.of("submitted", true, Keys.LAYER, layer, OPERATION, req.get("type").asText());
            }
        };
    }

    static String status(long code) {
        if (code == -1) return "ABORTED";
        if (code == 0) return "PENDING";
        if (code == 1) return "RUNNING";
        if (code == 2) return "DONE";
        return "UNKNOWN";
    }

    GsTool listTasks() {
        return new GsTool() {
            public McpSchema.Tool definition() {
                return McpSchema.Tool.builder("gs_gwc_list_tasks", Schema.object().string(Keys.LAYER, "Restrict to this tile layer", false).build())
                        .description("Running and pending seed tasks with progress").build();
            }
            public Object call(ToolContext ctx, Args args) {
                String path = args.has(Keys.LAYER) ? GWC_SEED_PATH + seg(args.string(Keys.LAYER)) + Keys.JSON_EXT : "/gwc/rest/seed.json";
                JsonNode doc = getJson(ctx, path);
                List<Map<String, Object>> tasks = new ArrayList<>();
                for (JsonNode row : doc.path("long-array-array")) {
                    if (row.size() < 5) continue;
                    Map<String, Object> t = new LinkedHashMap<>();
                    t.put("tilesDone", row.get(0).asLong());
                    t.put("tilesTotal", row.get(1).asLong());
                    t.put("remainingSeconds", row.get(2).asLong());
                    t.put("taskId", row.get(3).asLong());
                    t.put("status", status(row.get(4).asLong()));
                    tasks.add(t);
                }
                return Map.of("tasks", tasks);
            }
        };
    }

    GsTool killTasks() {
        return new GsTool() {
            public McpSchema.Tool definition() {
                return McpSchema.Tool.builder("gs_gwc_kill_tasks", Schema.object()
                        .string(Keys.LAYER, "Only tasks of this tile layer; omit for all", false)
                        .enumeration(WHICH, "running, pending or all (default)", false, "running", "pending", "all").build())
                        .description("Stop seed tasks").build();
            }
            public Object call(ToolContext ctx, Args args) {
                String path = args.has(Keys.LAYER) ? GWC_SEED_PATH + seg(args.string(Keys.LAYER)) : "/gwc/rest/seed";
                String body = "kill_all=" + args.string(WHICH, "all");
                expectOk(http.send(ctx, "POST", path, "application/x-www-form-urlencoded", body.getBytes(StandardCharsets.UTF_8)), "Kill tasks");
                return Map.of("killed", args.string(WHICH, "all"));
            }
        };
    }
}
