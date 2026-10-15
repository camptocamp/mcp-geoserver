package org.geoserver.mcp.tools.catalog;

import org.geoserver.catalog.CatalogBuilder;
import org.geoserver.catalog.CoverageInfo;
import org.geoserver.catalog.CoverageStoreInfo;
import org.geoserver.catalog.DataStoreInfo;
import org.geoserver.catalog.FeatureTypeInfo;
import org.geoserver.catalog.ProjectionPolicy;
import org.geoserver.catalog.ResourceInfo;
import org.geoserver.catalog.StoreInfo;
import org.geoserver.catalog.StyleInfo;
import org.geoserver.catalog.WorkspaceInfo;
import java.io.IOException;
import org.geotools.api.feature.type.FeatureType;
import org.geotools.api.feature.type.GeometryDescriptor;
import org.geotools.api.feature.type.PropertyDescriptor;
import org.geotools.feature.NameImpl;
import org.geoserver.mcp.tool.ToolException;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.geoserver.catalog.Catalog;
import org.geoserver.catalog.LayerInfo;
import org.geoserver.mcp.tool.Args;
import org.geoserver.mcp.tool.GsTool;
import org.geoserver.mcp.tool.Schema;
import org.geoserver.mcp.tool.ToolContext;

public class LayerTools {
    protected final Catalog catalog;

    public LayerTools(Catalog catalog) {
        this.catalog = catalog;
    }

    public List<GsTool> tools() {
        return List.of(list(), get(), publish(), publishMany(), update());
    }

    GsTool list() {
        return new GsTool() {
            public McpSchema.Tool definition() {
                return McpSchema.Tool.builder("gs_list_layers", Schema.object().string("workspace", "Restrict to this workspace", false).build())
                        .description("List published layers as workspace:name with type and title").build();
            }
            public Object call(ToolContext ctx, Args args) {
                String ws = args.string("workspace", null);
                ctx.trace().curl("GET", ws == null ? "/rest/layers.json" : "/rest/workspaces/" + ws + "/layers.json", null, null);
                if (ws != null) Lookups.workspace(catalog, ws);
                List<Map<String, Object>> out = new ArrayList<>();
                for (LayerInfo l : catalog.getLayers()) {
                    String lws = l.getResource().getStore().getWorkspace().getName();
                    if (ws == null || ws.equals(lws)) out.add(Views.layerSummary(l));
                }
                return Map.of("layers", out);
            }
        };
    }

    GsTool get() {
        return new GsTool() {
            public McpSchema.Tool definition() {
                return McpSchema.Tool.builder("gs_get_layer", Schema.object().string("name", "Layer name, workspace:name or plain name", true).build())
                        .description("Describe a layer: store, native name, SRS, bounds, styles, attributes").build();
            }
            public Object call(ToolContext ctx, Args args) {
                LayerInfo l = Lookups.layer(catalog, args.string("name"));
                ctx.trace().curl("GET", "/rest/layers/" + l.prefixedName() + ".json", null, null);
                return Views.layer(l);
            }
        };
    }

    GsTool publish() {
        return new GsTool() {
            public McpSchema.Tool definition() {
                return McpSchema.Tool.builder("gs_publish_layer", Schema.object()
                        .string("workspace", "Workspace name", true)
                        .string("store", "Store name", true)
                        .string("nativeName", "Feature type or coverage name inside the store. Omit for single-coverage raster stores.", false)
                        .string("name", "Published layer name, defaults to nativeName", false)
                        .string("title", "Layer title", false)
                        .string("srs", "Declared SRS such as EPSG:3857; forces the declared CRS", false).build())
                        .description("Publish a layer from an existing store. Computes bounds and looks up the SRS. Returns a compact summary; gs_get_layer has the attributes.").build();
            }
            public Object call(ToolContext ctx, Args args) throws Exception {
                WorkspaceInfo ws = Lookups.workspace(catalog, args.string("workspace"));
                StoreInfo store = Lookups.store(catalog, ws.getName(), args.string("store"));
                if (store instanceof DataStoreInfo ds) {
                    return publishVector(ctx, ds, args.string("nativeName"), args.string("name", null), args.string("title", null), args.string("srs", null), true);
                }
                if (!(store instanceof CoverageStoreInfo)) {
                    throw new ToolException("Store '" + store.getName() + "' is a " + Views.storeKind(store) + " store; only vector and raster stores are supported");
                }
                CatalogBuilder builder = new CatalogBuilder(catalog);
                builder.setWorkspace(ws);
                builder.setStore(store);
                CoverageInfo ci = args.has("nativeName") ? builder.buildCoverage(args.string("nativeName")) : builder.buildCoverage();
                ctx.trace().curl("POST", "/rest/workspaces/" + ws.getName() + "/coveragestores/" + store.getName() + "/coverages",
                        "application/json", "{\"coverage\":{\"name\":\"" + args.string("name", ci.getNativeName()) + "\",\"nativeName\":\"" + ci.getNativeName() + "\"}}");
                if (args.has("name")) ci.setName(args.string("name"));
                if (args.has("title")) ci.setTitle(args.string("title"));
                if (args.has("srs")) {
                    ci.setSRS(args.string("srs"));
                    ci.setProjectionPolicy(ProjectionPolicy.FORCE_DECLARED);
                }
                builder.setupBounds(ci);
                LayerInfo existing = catalog.getLayerByName(ws.getName() + ":" + ci.getName());
                if (existing != null) return Views.published(existing, false, List.of());
                LayerInfo layer = builder.buildLayer(ci);
                catalog.add(ci);
                catalog.add(layer);
                return Views.published(catalog.getLayerByName(ws.getName() + ":" + ci.getName()), true, List.of());
            }
        };
    }

    static final int MAX_BULK = 50;

    GsTool publishMany() {
        return new GsTool() {
            public McpSchema.Tool definition() {
                return McpSchema.Tool.builder("gs_publish_layers", Schema.object()
                        .string("workspace", "Workspace name", true)
                        .string("store", "Vector store name", true)
                        .stringArray("names", "Feature type names to publish; see gs_get_store available", false)
                        .bool("all", "Publish every unpublished feature type of the store", false)
                        .bool("includeNonSpatial", "Also publish tables without a geometry column, default false", false).build())
                        .description("Publish up to " + MAX_BULK + " feature types of one store in one call. Each item succeeds or fails on its own; "
                                + "geometryless tables are skipped unless includeNonSpatial.").build();
            }
            public Object call(ToolContext ctx, Args args) throws Exception {
                WorkspaceInfo ws = Lookups.workspace(catalog, args.string("workspace"));
                StoreInfo store = Lookups.store(catalog, ws.getName(), args.string("store"));
                if (!(store instanceof DataStoreInfo ds)) throw new ToolException("gs_publish_layers works on vector stores; use gs_publish_layer");
                List<String> names = args.strings("names");
                if (names.isEmpty() && !args.bool("all", false)) throw new ToolException("Pass names or all: true");
                if (names.isEmpty()) names = StoreTools.available(catalog, ds);
                if (names.size() > MAX_BULK) throw new ToolException(names.size() + " feature types; publish at most " + MAX_BULK + " per call, pass names");
                boolean nonSpatial = args.bool("includeNonSpatial", false);
                List<Map<String, Object>> items = new ArrayList<>();
                int published = 0, skipped = 0, failed = 0;
                for (String name : names) {
                    long start = System.nanoTime();
                    Map<String, Object> item;
                    try {
                        item = publishVector(ctx, ds, name, null, null, null, nonSpatial);
                        if (Boolean.TRUE.equals(item.get("created"))) published++;
                    } catch (Skipped e) {
                        item = new java.util.LinkedHashMap<>(Map.of("name", name, "skipped", true, "warnings", List.of(e.getMessage())));
                        skipped++;
                    } catch (Exception e) {
                        item = new java.util.LinkedHashMap<>(Map.of("name", name, "error", JdbcConnectionCheck.rootMessage(e)));
                        failed++;
                    }
                    item.put("ms", (System.nanoTime() - start) / 1_000_000);
                    items.add(item);
                }
                Map<String, Object> m = new java.util.LinkedHashMap<>();
                m.put("store", ws.getName() + ":" + ds.getName());
                m.put("published", published);
                m.put("skipped", skipped);
                m.put("failed", failed);
                m.put("items", items);
                return m;
            }
        };
    }

    static class Skipped extends ToolException {
        Skipped(String message) {
            super(message);
        }
    }

    Map<String, Object> publishVector(ToolContext ctx, DataStoreInfo store, String nativeName, String name, String title, String srs, boolean includeNonSpatial) throws Exception {
        WorkspaceInfo ws = store.getWorkspace();
        String layerName = name == null ? nativeName : name;
        LayerInfo existing = catalog.getLayerByName(ws.getName() + ":" + layerName);
        if (existing != null) return Views.published(existing, false, List.of());
        ctx.trace().curl("POST", "/rest/workspaces/" + ws.getName() + "/datastores/" + store.getName() + "/featuretypes",
                "application/json", "{\"featureType\":{\"name\":\"" + layerName + "\",\"nativeName\":\"" + nativeName + "\"}}");
        CatalogBuilder builder = new CatalogBuilder(catalog);
        builder.setWorkspace(ws);
        builder.setStore(store);
        List<String> warnings = new ArrayList<>();
        FeatureTypeInfo ft = catalog.getFeatureTypeByDataStore(store, layerName);
        boolean reused = ft != null;
        if (reused) {
            warnings.add("Reused feature type '" + layerName + "' that had no layer");
        } else {
            FeatureType schema;
            try {
                schema = catalog.getResourcePool().getDataStore(store).getSchema(new NameImpl(nativeName));
            } catch (IOException e) {
                throw new ToolException("Feature type '" + nativeName + "' not found in store '" + store.getName() + "': " + e.getMessage());
            }
            List<String> geoms = new ArrayList<>();
            for (PropertyDescriptor d : schema.getDescriptors()) if (d instanceof GeometryDescriptor) geoms.add(d.getName().getLocalPart());
            if (geoms.isEmpty()) {
                if (!includeNonSpatial) throw new Skipped("No geometry column; pass includeNonSpatial to publish anyway");
                warnings.add("No geometry column: WMS cannot draw this layer");
            } else if (geoms.size() > 1) {
                warnings.add("Several geometry columns " + geoms + "; GeoServer uses '" + schema.getGeometryDescriptor().getLocalName()
                        + "' and bounds may mix them; publish a SQL view to pick one");
            }
            ft = builder.buildFeatureType(new NameImpl(nativeName));
            if (name != null) ft.setName(name);
            if (title != null) ft.setTitle(title);
            if (srs != null) {
                ft.setSRS(srs);
                ft.setProjectionPolicy(ProjectionPolicy.FORCE_DECLARED);
            } else if (ft.getSRS() == null) {
                builder.lookupSRS(ft, true);
            }
            if (ft.getSRS() == null || "EPSG:404000".equals(ft.getSRS())) warnings.add("No SRS could be determined (" + ft.getSRS() + "); pass srs");
            try {
                builder.setupBounds(ft);
            } catch (Exception e) {
                warnings.add("Bounds not computed: " + JdbcConnectionCheck.rootMessage(e));
            }
        }
        LayerInfo layer = builder.buildLayer(ft);
        if (!reused) catalog.add(ft);
        try {
            catalog.add(layer);
        } catch (RuntimeException e) {
            if (!reused) catalog.remove(ft);
            throw e;
        }
        return Views.published(catalog.getLayerByName(ws.getName() + ":" + layerName), true, warnings);
    }

    GsTool update() {
        return new GsTool() {
            public McpSchema.Tool definition() {
                return McpSchema.Tool.builder("gs_update_layer", Schema.object()
                        .string("name", "Layer name, workspace:name", true)
                        .string("title", "New title", false)
                        .string("abstract", "New abstract", false)
                        .string("defaultStyle", "Style name, optionally workspace:name", false)
                        .stringArray("styles", "Additional style names, replaces the list", false)
                        .bool("enabled", "Enable or disable", false)
                        .bool("advertised", "Advertise in capabilities", false).build())
                        .description("Update layer metadata, default style, additional styles, enabled and advertised flags").build();
            }
            public Object call(ToolContext ctx, Args args) {
                LayerInfo layer = Lookups.layer(catalog, args.string("name"));
                ResourceInfo resource = layer.getResource();
                if (args.has("title")) resource.setTitle(args.string("title"));
                if (args.has("abstract")) resource.setAbstract(args.string("abstract"));
                if (args.has("enabled")) {
                    boolean enabled = args.bool("enabled", true);
                    resource.setEnabled(enabled);
                    layer.setEnabled(enabled);
                }
                if (args.has("advertised")) layer.setAdvertised(args.bool("advertised", true));
                if (args.has("defaultStyle")) layer.setDefaultStyle(findStyle(args.string("defaultStyle")));
                if (args.has("styles")) {
                    layer.getStyles().clear();
                    for (String s : args.strings("styles")) layer.getStyles().add(findStyle(s));
                }
                ctx.trace().curl("PUT", "/rest/layers/" + layer.prefixedName() + ".json", "application/json",
                        "{\"layer\":{\"defaultStyle\":{\"name\":\"" + (layer.getDefaultStyle() == null ? "" : layer.getDefaultStyle().prefixedName()) + "\"},\"enabled\":" + layer.isEnabled() + "}}");
                ctx.trace().curl("PUT", "/rest/workspaces/" + resource.getStore().getWorkspace().getName() + "/"
                        + (resource instanceof FeatureTypeInfo ? "datastores/" + resource.getStore().getName() + "/featuretypes/" : "coveragestores/" + resource.getStore().getName() + "/coverages/")
                        + resource.getName() + ".json", "application/json",
                        "{\"" + (resource instanceof FeatureTypeInfo ? "featureType" : "coverage") + "\":{\"title\":\"" + resource.getTitle() + "\"}}");
                catalog.save(resource);
                catalog.save(layer);
                return Views.layer(catalog.getLayer(layer.getId()));
            }
        };
    }

    StyleInfo findStyle(String name) {
        StyleInfo s = catalog.getStyleByName(name);
        if (s == null) throw new ToolException("Style '" + name + "' not found");
        return s;
    }
}
