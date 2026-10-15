package org.geoserver.mcp.tools.gwc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.geoserver.mcp.http.GeoServerHttpClient;
import org.geoserver.mcp.tool.Args;
import org.geoserver.mcp.tool.GsTool;
import org.geoserver.mcp.tool.Schema;
import org.geoserver.mcp.tool.ToolContext;
import org.geoserver.mcp.tool.ToolException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

public class GwcTools {
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
        m.put("enabled", l.path("enabled").asBoolean(true));
        m.put("mimeFormats", strings(l.get("mimeFormats")));
        List<Map<String, Object>> subsets = new ArrayList<>();
        if (l.has("gridSubsets")) {
            for (JsonNode s : l.get("gridSubsets")) {
                Map<String, Object> sm = new LinkedHashMap<>();
                sm.put("gridSetName", s.path("gridSetName").asText(null));
                if (s.has("zoomStart")) sm.put("zoomStart", s.get("zoomStart").asInt());
                if (s.has("zoomStop")) sm.put("zoomStop", s.get("zoomStop").asInt());
                subsets.add(sm);
            }
        }
        m.put("gridSubsets", subsets);
        if (l.has("metaWidthHeight")) m.put("metaWidthHeight", strings(l.get("metaWidthHeight")));
        if (l.has("expireCache")) m.put("expireCache", l.get("expireCache").asInt());
        if (l.has("expireClients")) m.put("expireClients", l.get("expireClients").asInt());
        return m;
    }

    GsTool getTileLayer() {
        return new GsTool() {
            public McpSchema.Tool definition() {
                return McpSchema.Tool.builder("gs_gwc_get_tile_layer", Schema.object().string("layer", "Tile layer name, workspace:name", true).build())
                        .description("Tile layer configuration: enabled flag, formats, gridsets with zoom ranges").build();
            }
            public Object call(ToolContext ctx, Args args) {
                return tileLayerView(getJson(ctx, "/gwc/rest/layers/" + args.string("layer") + ".json"));
            }
        };
    }

    GsTool setTileLayer() {
        return new GsTool() {
            public McpSchema.Tool definition() {
                return McpSchema.Tool.builder("gs_gwc_set_tile_layer", Schema.object()
                        .string("layer", "Tile layer name, workspace:name", true)
                        .bool("enabled", "Enable or disable tile caching", false)
                        .stringArray("gridsets", "Gridset names to cache, replaces the list", false)
                        .stringArray("formats", "MIME formats to cache, replaces the list", false).build())
                        .description("Update a tile layer's enabled flag, gridsets and formats").build();
            }
            public Object call(ToolContext ctx, Args args) throws Exception {
                String layer = args.string("layer");
                String path = "/gwc/rest/layers/" + layer + ".xml";
                GeoServerHttpClient.Response current = http.get(ctx, path);
                if (current.status() != 200) throw new ToolException("GWC " + path + " returned HTTP " + current.status() + ": " + current.text().trim());
                Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new ByteArrayInputStream(current.body()));
                Element root = doc.getDocumentElement();
                if (args.has("enabled")) child(root, "enabled").setTextContent(String.valueOf(args.bool("enabled", true)));
                if (args.has("formats")) {
                    Element f = replaceChild(doc, root, "mimeFormats");
                    for (String m : args.strings("formats")) f.appendChild(doc.createElement("string")).setTextContent(m);
                }
                if (args.has("gridsets")) {
                    Element g = replaceChild(doc, root, "gridSubsets");
                    for (String name : args.strings("gridsets")) {
                        Element sub = (Element) g.appendChild(doc.createElement("gridSubset"));
                        sub.appendChild(doc.createElement("gridSetName")).setTextContent(name);
                    }
                }
                StringWriter xml = new StringWriter();
                TransformerFactory.newInstance().newTransformer().transform(new DOMSource(doc), new StreamResult(xml));
                expectOk(http.send(ctx, "PUT", path, "text/xml", xml.toString().getBytes(StandardCharsets.UTF_8)), "Tile layer update");
                ObjectNode view = json.createObjectNode();
                view.put("name", root.getElementsByTagName("name").item(0).getTextContent());
                view.put("enabled", Boolean.parseBoolean(child(root, "enabled").getTextContent()));
                ArrayNode formats = view.putArray("mimeFormats");
                NodeList strings = child(root, "mimeFormats").getElementsByTagName("string");
                for (int i = 0; i < strings.getLength(); i++) formats.add(strings.item(i).getTextContent());
                ArrayNode subsets = view.putArray("gridSubsets");
                NodeList names = child(root, "gridSubsets").getElementsByTagName("gridSetName");
                for (int i = 0; i < names.getLength(); i++) subsets.addObject().put("gridSetName", names.item(i).getTextContent());
                Map<String, Object> m = new LinkedHashMap<>(tileLayerView(view));
                m.put("updated", true);
                return m;
            }
        };
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
                return Map.of("gridsets", strings(getJson(ctx, "/gwc/rest/gridsets.json")));
            }
        };
    }

    GsTool seed() {
        return new GsTool() {
            public McpSchema.Tool definition() {
                return McpSchema.Tool.builder("gs_gwc_seed", Schema.object()
                        .string("layer", "Tile layer name, workspace:name", true)
                        .string("gridset", "Gridset name, e.g. EPSG:4326 or EPSG:900913", true)
                        .integer("zoomStart", "First zoom level", true)
                        .integer("zoomStop", "Last zoom level", true)
                        .string("format", "Tile MIME type, e.g. image/png", true)
                        .enumeration("operation", "seed, reseed or truncate", false, "seed", "reseed", "truncate")
                        .integer("threads", "Thread count, default 1", false)
                        .numberArray("bbox", "minx, miny, maxx, maxy in the gridset CRS; omit for the whole layer", false).build())
                        .description("Submit a seed, reseed or truncate job. Returns immediately; poll gs_gwc_list_tasks.").build();
            }
            public Object call(ToolContext ctx, Args args) throws Exception {
                String layer = args.string("layer");
                ObjectNode req = json.createObjectNode();
                req.put("name", layer);
                req.put("gridSetId", args.string("gridset"));
                req.put("zoomStart", args.integer("zoomStart", 0));
                req.put("zoomStop", args.integer("zoomStop", 0));
                req.put("format", args.string("format"));
                req.put("type", args.string("operation", "seed"));
                req.put("threadCount", args.integer("threads", 1));
                List<Double> bbox = args.numbers("bbox");
                if (!bbox.isEmpty()) {
                    if (bbox.size() != 4) throw new ToolException("bbox needs exactly 4 numbers");
                    ArrayNode coords = req.putObject("bounds").putObject("coords").putArray("double");
                    bbox.forEach(coords::add);
                }
                ObjectNode wrapper = json.createObjectNode();
                wrapper.set("seedRequest", req);
                expectOk(http.send(ctx, "POST", "/gwc/rest/seed/" + layer + ".json", "application/json", json.writeValueAsBytes(wrapper)), "Seed request");
                return Map.of("submitted", true, "layer", layer, "operation", req.get("type").asText());
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
                return McpSchema.Tool.builder("gs_gwc_list_tasks", Schema.object().string("layer", "Restrict to this tile layer", false).build())
                        .description("Running and pending seed tasks with progress").build();
            }
            public Object call(ToolContext ctx, Args args) {
                String path = args.has("layer") ? "/gwc/rest/seed/" + args.string("layer") + ".json" : "/gwc/rest/seed.json";
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
                        .string("layer", "Only tasks of this tile layer; omit for all", false)
                        .enumeration("which", "running, pending or all (default)", false, "running", "pending", "all").build())
                        .description("Stop seed tasks").build();
            }
            public Object call(ToolContext ctx, Args args) {
                String path = args.has("layer") ? "/gwc/rest/seed/" + args.string("layer") : "/gwc/rest/seed";
                String body = "kill_all=" + args.string("which", "all");
                expectOk(http.send(ctx, "POST", path, "application/x-www-form-urlencoded", body.getBytes(StandardCharsets.UTF_8)), "Kill tasks");
                return Map.of("killed", args.string("which", "all"));
            }
        };
    }
}
