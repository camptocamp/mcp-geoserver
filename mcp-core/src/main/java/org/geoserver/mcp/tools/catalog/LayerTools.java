package org.geoserver.mcp.tools.catalog;

import org.geoserver.catalog.CatalogBuilder;
import org.geoserver.catalog.CoverageInfo;
import org.geoserver.catalog.CoverageStoreInfo;
import org.geoserver.catalog.DataStoreInfo;
import org.geoserver.catalog.FeatureTypeInfo;
import org.geoserver.catalog.ProjectionPolicy;
import org.geoserver.catalog.ResourceInfo;
import org.geoserver.catalog.StoreInfo;
import org.geoserver.catalog.WorkspaceInfo;
import java.io.IOException;
import org.geotools.api.feature.type.FeatureType;
import org.geotools.api.feature.type.GeometryDescriptor;
import org.geotools.api.feature.type.PropertyDescriptor;
import org.geotools.feature.NameImpl;
import org.geotools.geometry.jts.Geometries;
import org.geotools.jdbc.JDBCDataStore;
import org.geotools.jdbc.VirtualTable;
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
import org.geoserver.mcp.tool.Keys;

public class LayerTools {
    private static final String GEOMETRY = "geometry";
    private static final String GEOMETRY_TYPE = "geometryType";
    private static final String KEY_COLUMN = "keyColumn";
    private static final String SKIPPED = "skipped";

    protected final Catalog catalog;

    public LayerTools(Catalog catalog) {
        this.catalog = catalog;
    }

    public List<GsTool> tools() {
        return List.of(list(), get(), publish(), publishMany(), createSqlView(), update());
    }

    GsTool list() {
        return new GsTool() {
            public McpSchema.Tool definition() {
                return McpSchema.Tool.builder("gs_list_layers", Schema.object().string(Keys.WORKSPACE, "Restrict to this workspace", false).build())
                        .description("List published layers as workspace:name with type and title; enabled appears only when false").build();
            }
            public Object call(ToolContext ctx, Args args) {
                String ws = args.string(Keys.WORKSPACE, null);
                ctx.trace().curl("GET", ws == null ? "/rest/layers.json" : Keys.REST_WORKSPACES + ws + "/layers.json", null, null);
                if (ws != null) Lookups.workspace(catalog, ws);
                List<Map<String, Object>> out = new ArrayList<>();
                for (LayerInfo l : catalog.getLayers()) {
                    String lws = l.getResource().getStore().getWorkspace().getName();
                    if (ws == null || ws.equals(lws)) out.add(Views.listRow(Views.layerSummary(l), false));
                }
                return Map.of(Keys.LAYERS, out);
            }
        };
    }

    GsTool get() {
        return new GsTool() {
            public McpSchema.Tool definition() {
                return McpSchema.Tool.builder("gs_get_layer", Schema.object().string("name", Keys.DESC_LAYER_NAME, true).build())
                        .description("Describe a layer: store, native name, SRS, bounds, styles, attributes").build();
            }
            public Object call(ToolContext ctx, Args args) {
                LayerInfo l = Lookups.layer(catalog, args.string("name"));
                ctx.trace().curl("GET", "/rest/layers/" + l.prefixedName() + Keys.JSON_EXT, null, null);
                return Views.layer(l);
            }
        };
    }

    GsTool publish() {
        return new GsTool() {
            public McpSchema.Tool definition() {
                return McpSchema.Tool.builder("gs_publish_layer", Schema.object()
                        .string(Keys.WORKSPACE, Keys.DESC_WORKSPACE_NAME, true)
                        .string(Keys.STORE, "Store name", true)
                        .string(Keys.NATIVE_NAME, "Feature type or coverage name inside the store. Omit for single-coverage raster stores.", false)
                        .string("name", "Published layer name, defaults to nativeName", false)
                        .string(Keys.TITLE, "Layer title", false)
                        .string("srs", "Declared SRS such as EPSG:3857; forces the declared CRS", false).build())
                        .description("Publish a layer from an existing store. Computes bounds and looks up the SRS. Returns a compact summary; gs_get_layer has the attributes.").build();
            }
            public Object call(ToolContext ctx, Args args) throws Exception {
                WorkspaceInfo ws = Lookups.workspace(catalog, args.string(Keys.WORKSPACE));
                StoreInfo store = Lookups.store(catalog, ws.getName(), args.string(Keys.STORE));
                if (store instanceof DataStoreInfo ds) {
                    return publishVector(ctx, ds, new VectorRequest(args.string(Keys.NATIVE_NAME), args.string("name", null), args.string(Keys.TITLE, null), args.string("srs", null), true, null));
                }
                if (!(store instanceof CoverageStoreInfo)) {
                    throw new ToolException("Store '" + store.getName() + "' is a " + Views.storeKind(store) + " store; only vector and raster stores are supported");
                }
                CatalogBuilder builder = new CatalogBuilder(catalog);
                builder.setWorkspace(ws);
                builder.setStore(store);
                CoverageInfo ci = args.has(Keys.NATIVE_NAME) ? builder.buildCoverage(args.string(Keys.NATIVE_NAME)) : builder.buildCoverage();
                ctx.trace().curl("POST", Keys.REST_WORKSPACES + ws.getName() + "/coveragestores/" + store.getName() + "/coverages",
                        Keys.APPLICATION_JSON, "{\"coverage\":{\"name\":\"" + args.string("name", ci.getNativeName()) + "\",\"nativeName\":\"" + ci.getNativeName() + "\"}}");
                if (args.has("name")) ci.setName(args.string("name"));
                if (args.has(Keys.TITLE)) ci.setTitle(args.string(Keys.TITLE));
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
                        .string(Keys.WORKSPACE, Keys.DESC_WORKSPACE_NAME, true)
                        .string(Keys.STORE, "Vector store name", true)
                        .stringArray("names", "Feature type names to publish; see gs_get_store available", false)
                        .bool("all", "Publish every unpublished feature type of the store", false)
                        .bool("includeNonSpatial", "Also publish tables without a geometry column, default false", false).build())
                        .description("Publish up to " + MAX_BULK + " feature types of one store in one call. Each item succeeds or fails on its own; "
                                + "geometryless tables are skipped unless includeNonSpatial.").build();
            }
            public Object call(ToolContext ctx, Args args) throws Exception {
                WorkspaceInfo ws = Lookups.workspace(catalog, args.string(Keys.WORKSPACE));
                StoreInfo store = Lookups.store(catalog, ws.getName(), args.string(Keys.STORE));
                if (!(store instanceof DataStoreInfo ds)) throw new ToolException("gs_publish_layers works on vector stores; use gs_publish_layer");
                boolean nonSpatial = args.bool("includeNonSpatial", false);
                List<Map<String, Object>> items = new ArrayList<>();
                for (String name : bulkNames(args, ds)) items.add(publishItem(ctx, ds, name, nonSpatial));
                Map<String, Object> m = new java.util.LinkedHashMap<>();
                m.put(Keys.STORE, ws.getName() + ":" + ds.getName());
                m.put("published", items.stream().filter(i -> Boolean.TRUE.equals(i.get(Keys.CREATED))).count());
                m.put(SKIPPED, items.stream().filter(i -> i.containsKey(SKIPPED)).count());
                m.put("failed", items.stream().filter(i -> i.containsKey("error")).count());
                m.put("items", items);
                return m;
            }
        };
    }

    List<String> bulkNames(Args args, DataStoreInfo ds) throws IOException {
        List<String> names = args.strings("names");
        if (names.isEmpty() && !args.bool("all", false)) throw new ToolException("Pass names or all: true");
        if (names.isEmpty()) names = StoreTools.available(catalog, ds);
        if (names.size() > MAX_BULK) throw new ToolException(names.size() + " feature types; publish at most " + MAX_BULK + " per call, pass names");
        return names;
    }

    Map<String, Object> publishItem(ToolContext ctx, DataStoreInfo ds, String name, boolean nonSpatial) {
        long start = System.nanoTime();
        Map<String, Object> item;
        try {
            item = publishVector(ctx, ds, new VectorRequest(name, null, null, null, nonSpatial, null));
        } catch (Skipped e) {
            item = new java.util.LinkedHashMap<>(Map.of("name", name, SKIPPED, true, "warnings", List.of(e.getMessage())));
        } catch (Exception e) {
            item = new java.util.LinkedHashMap<>(Map.of("name", name, "error", JdbcConnectionCheck.rootMessage(e)));
        }
        item.put("ms", (System.nanoTime() - start) / 1_000_000);
        return item;
    }

    GsTool createSqlView() {
        return new GsTool() {
            public McpSchema.Tool definition() {
                return McpSchema.Tool.builder("gs_create_sql_view", Schema.object()
                        .string(Keys.WORKSPACE, Keys.DESC_WORKSPACE_NAME, true)
                        .string(Keys.STORE, "JDBC store name (PostGIS and the like)", true)
                        .string("name", "View and layer name", true)
                        .string("sql", "SELECT statement; pick one geometry column here to fix multi-geometry tables", true)
                        .string(GEOMETRY, "Geometry column name in the SELECT", true)
                        .string(GEOMETRY_TYPE, "Point, LineString, Polygon, MultiPoint, MultiLineString, MultiPolygon or Geometry", true)
                        .integer("srid", "EPSG code of the geometry column", true)
                        .string(KEY_COLUMN, "Column used as feature id; omit for generated ids", false).build())
                        .description("Create a SQL view on a JDBC store and publish it as a layer").build();
            }
            public Object call(ToolContext ctx, Args args) throws Exception {
                WorkspaceInfo ws = Lookups.workspace(catalog, args.string(Keys.WORKSPACE));
                StoreInfo store = Lookups.store(catalog, ws.getName(), args.string(Keys.STORE));
                String name = args.string("name");
                Geometries type = Geometries.getForName(args.string(GEOMETRY_TYPE));
                if (type == null) throw new ToolException("Unknown geometryType '" + args.string(GEOMETRY_TYPE) + "'");
                if (!(store instanceof DataStoreInfo ds) || !(catalog.getResourcePool().getDataStore(ds) instanceof JDBCDataStore jdbc)) {
                    throw new ToolException("Store '" + store.getName() + "' is not a JDBC store");
                }
                VirtualTable view = new VirtualTable(name, args.string("sql"));
                view.addGeometryMetadatata(args.string(GEOMETRY), type.getBinding(), args.integer("srid", 0));
                if (args.has(KEY_COLUMN)) view.setPrimaryKeyColumns(List.of(args.string(KEY_COLUMN)));
                ctx.trace().curl("POST", Keys.REST_WORKSPACES + ws.getName() + "/datastores/" + ds.getName() + "/featuretypes", Keys.APPLICATION_JSON,
                        Views.json(Map.of("featureType", Map.of("name", name, Keys.NATIVE_NAME, name, "metadata", Map.of("entry", Map.of("@key", "JDBC_VIRTUAL_TABLE",
                                "virtualTable", Map.of("name", name, "sql", args.string("sql"), GEOMETRY, Map.of("name", args.string(GEOMETRY), "type", type.getName(), "srid", args.integer("srid", 0)))))))));
                jdbc.createVirtualTable(view);
                try {
                    return publishVector(ctx, ds, new VectorRequest(name, null, null, null, true, view));
                } catch (Exception e) {
                    jdbc.dropVirtualTable(name);
                    throw e;
                }
            }
        };
    }

    static class Skipped extends ToolException {
        Skipped(String message) {
            super(message);
        }
    }

    record VectorRequest(String nativeName, String name, String title, String srs, boolean includeNonSpatial, VirtualTable view) {}

    Map<String, Object> publishVector(ToolContext ctx, DataStoreInfo store, VectorRequest req) throws Exception {
        WorkspaceInfo ws = store.getWorkspace();
        String layerName = req.name() == null ? req.nativeName() : req.name();
        LayerInfo existing = catalog.getLayerByName(ws.getName() + ":" + layerName);
        if (existing != null) return Views.published(existing, false, List.of());
        ctx.trace().curl("POST", Keys.REST_WORKSPACES + ws.getName() + "/datastores/" + store.getName() + "/featuretypes",
                Keys.APPLICATION_JSON, "{\"featureType\":{\"name\":\"" + layerName + "\",\"nativeName\":\"" + req.nativeName() + "\"}}");
        CatalogBuilder builder = new CatalogBuilder(catalog);
        builder.setWorkspace(ws);
        builder.setStore(store);
        List<String> warnings = new ArrayList<>();
        FeatureTypeInfo ft = catalog.getFeatureTypeByDataStore(store, layerName);
        boolean reused = ft != null;
        if (reused) {
            warnings.add("Reused feature type '" + layerName + "' that had no layer");
        } else {
            ft = newFeatureType(builder, store, req.nativeName(), req.includeNonSpatial(), req.view(), warnings);
            if (req.name() != null) ft.setName(req.name());
            if (req.title() != null) ft.setTitle(req.title());
            setupSrsAndBounds(builder, ft, req.srs(), warnings);
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

    FeatureTypeInfo newFeatureType(CatalogBuilder builder, DataStoreInfo store, String nativeName, boolean includeNonSpatial,
            VirtualTable view, List<String> warnings) throws Exception {
        FeatureType schema;
        try {
            schema = catalog.getResourcePool().getDataStore(store).getSchema(new NameImpl(nativeName));
        } catch (IOException e) {
            throw new ToolException("Feature type '" + nativeName + "' not found in store '" + store.getName() + "': " + e.getMessage());
        }
        geometryWarnings(schema, includeNonSpatial, warnings);
        FeatureTypeInfo ft = builder.buildFeatureType(new NameImpl(nativeName));
        if (view != null) ft.getMetadata().put(FeatureTypeInfo.JDBC_VIRTUAL_TABLE, view);
        return ft;
    }

    static void geometryWarnings(FeatureType schema, boolean includeNonSpatial, List<String> warnings) {
        List<String> geoms = new ArrayList<>();
        for (PropertyDescriptor d : schema.getDescriptors()) if (d instanceof GeometryDescriptor) geoms.add(d.getName().getLocalPart());
        if (geoms.isEmpty()) {
            if (!includeNonSpatial) throw new Skipped("No geometry column; pass includeNonSpatial to publish anyway");
            warnings.add("No geometry column: WMS cannot draw this layer");
        } else if (geoms.size() > 1) {
            warnings.add("Several geometry columns " + geoms + "; GeoServer uses '" + schema.getGeometryDescriptor().getLocalName()
                    + "' and bounds may mix them; publish a SQL view to pick one");
        }
    }

    static void setupSrsAndBounds(CatalogBuilder builder, FeatureTypeInfo ft, String srs, List<String> warnings) throws IOException {
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

    GsTool update() {
        return new GsTool() {
            public McpSchema.Tool definition() {
                return McpSchema.Tool.builder("gs_update_layer", Schema.object()
                        .string("name", Keys.DESC_LAYER_NAME, true)
                        .string(Keys.TITLE, "New title", false)
                        .string(Keys.ABSTRACT, "New abstract", false)
                        .string(Keys.DEFAULT_STYLE, "Style name, optionally workspace:name", false)
                        .stringArray(Keys.STYLES, "Additional style names, replaces the list", false)
                        .bool(Keys.ENABLED, "Enable or disable", false)
                        .bool(Keys.ADVERTISED, "Advertise in capabilities", false).build())
                        .description("Update layer metadata, default style, additional styles, enabled and advertised flags").build();
            }
            public Object call(ToolContext ctx, Args args) {
                LayerInfo layer = Lookups.layer(catalog, args.string("name"));
                ResourceInfo resource = layer.getResource();
                applyUpdate(layer, resource, args);
                ctx.trace().curl("PUT", "/rest/layers/" + layer.prefixedName() + Keys.JSON_EXT, Keys.APPLICATION_JSON,
                        "{\"layer\":{\"defaultStyle\":{\"name\":\"" + (layer.getDefaultStyle() == null ? "" : layer.getDefaultStyle().prefixedName()) + "\"},\"enabled\":" + layer.isEnabled() + "}}");
                ctx.trace().curl("PUT", Keys.REST_WORKSPACES + resource.getStore().getWorkspace().getName() + "/"
                        + (resource instanceof FeatureTypeInfo ? "datastores/" + resource.getStore().getName() + "/featuretypes/" : "coveragestores/" + resource.getStore().getName() + "/coverages/")
                        + resource.getName() + Keys.JSON_EXT, Keys.APPLICATION_JSON,
                        "{\"" + (resource instanceof FeatureTypeInfo ? "featureType" : "coverage") + "\":{\"title\":\"" + resource.getTitle() + "\"}}");
                catalog.save(resource);
                catalog.save(layer);
                return Views.layer(catalog.getLayer(layer.getId()));
            }
        };
    }

    void applyUpdate(LayerInfo layer, ResourceInfo resource, Args args) {
        if (args.has(Keys.TITLE)) resource.setTitle(args.string(Keys.TITLE));
        if (args.has(Keys.ABSTRACT)) resource.setAbstract(args.string(Keys.ABSTRACT));
        if (args.has(Keys.ENABLED)) {
            boolean enabled = args.bool(Keys.ENABLED, true);
            resource.setEnabled(enabled);
            layer.setEnabled(enabled);
        }
        if (args.has(Keys.ADVERTISED)) layer.setAdvertised(args.bool(Keys.ADVERTISED, true));
        if (args.has(Keys.DEFAULT_STYLE)) layer.setDefaultStyle(Lookups.style(catalog, null, args.string(Keys.DEFAULT_STYLE)));
        if (args.has(Keys.STYLES)) {
            layer.getStyles().clear();
            for (String s : args.strings(Keys.STYLES)) layer.getStyles().add(Lookups.style(catalog, null, s));
        }
    }
}
