package org.geoserver.mcp.tools.catalog;

import java.io.Serializable;
import java.util.LinkedHashMap;
import org.geoserver.catalog.LayerInfo;
import java.util.function.BiFunction;
import org.geoserver.catalog.ResourcePool;
import java.util.Set;
import org.geoserver.catalog.CatalogBuilder;
import org.geoserver.catalog.CoverageStoreInfo;
import org.geoserver.catalog.DataStoreInfo;
import org.geoserver.mcp.tool.ToolException;
import org.geoserver.mcp.tools.discovery.DiscoveryTools;
import org.geotools.api.data.DataAccessFactory;
import org.geotools.coverage.grid.io.AbstractGridFormat;
import org.geotools.api.feature.type.GeometryDescriptor;
import org.geotools.api.feature.type.Name;
import org.geotools.api.feature.type.PropertyDescriptor;
import org.geotools.feature.NameImpl;
import org.geotools.referencing.CRS;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.geoserver.catalog.Catalog;
import org.geoserver.catalog.ResourceInfo;
import org.geoserver.catalog.StoreInfo;
import org.geoserver.catalog.WorkspaceInfo;
import org.geoserver.mcp.tool.Args;
import org.geoserver.mcp.tool.GsTool;
import org.geoserver.mcp.tool.Schema;
import org.geoserver.mcp.tool.ToolContext;
import org.geoserver.mcp.tool.Keys;

public class StoreTools {
    private static final String NAMESPACE = "namespace";
    private static final String DBTYPE = "dbtype";

    protected final Catalog catalog;
    private final BiFunction<ToolContext, LayerInfo, Map<String, Object>> render;

    public StoreTools(Catalog catalog) {
        this(catalog, null);
    }

    public StoreTools(Catalog catalog, BiFunction<ToolContext, LayerInfo, Map<String, Object>> render) {
        this.catalog = catalog;
        this.render = render;
    }

    public List<GsTool> tools() {
        return List.of(list(), get(), create());
    }

    GsTool list() {
        return new GsTool() {
            public McpSchema.Tool definition() {
                return McpSchema.Tool.builder("gs_list_stores", Schema.object().string(Keys.WORKSPACE, null, false).build())
                        .description("List vector, raster, WMS and WMTS stores; enabled appears only when false").build();
            }
            public Object call(ToolContext ctx, Args args) {
                List<StoreInfo> stores;
                if (args.has(Keys.WORKSPACE)) {
                    WorkspaceInfo ws = Lookups.workspace(catalog, args.string(Keys.WORKSPACE));
                    ctx.trace().curl("GET", Keys.REST_WORKSPACES + ws.getName() + "/datastores.json", null, null);
                    ctx.trace().curl("GET", Keys.REST_WORKSPACES + ws.getName() + "/coveragestores.json", null, null);
                    stores = catalog.getStoresByWorkspace(ws, StoreInfo.class);
                } else {
                    for (WorkspaceInfo ws : catalog.getWorkspaces()) {
                        ctx.trace().curl("GET", Keys.REST_WORKSPACES + ws.getName() + "/datastores.json", null, null);
                        ctx.trace().curl("GET", Keys.REST_WORKSPACES + ws.getName() + "/coveragestores.json", null, null);
                    }
                    stores = catalog.getStores(StoreInfo.class);
                }
                List<Map<String, Object>> out = new ArrayList<>();
                for (StoreInfo s : stores) {
                    Map<String, Object> v = Views.store(s);
                    v.remove("connectionParameters");
                    out.add(Views.listRow(v, args.has(Keys.WORKSPACE)));
                }
                return Map.of("stores", out);
            }
        };
    }

    GsTool get() {
        return new GsTool() {
            public McpSchema.Tool definition() {
                return McpSchema.Tool.builder("gs_get_store", Schema.object()
                        .string(Keys.WORKSPACE, null, true)
                        .string("name", null, true)
                        .stringArray("names", "Vector stores: describe these feature types (geometry columns, types, SRIDs)", false)
                        .bool("detail", "Vector stores: describe every published and available feature type; at most " + LayerTools.MAX_BULK, false).build())
                        .description("Describe a store: type, masked connection parameters, published and available feature types; names or detail add geometry columns").build();
            }
            public Object call(ToolContext ctx, Args args) throws Exception {
                StoreInfo s = Lookups.store(catalog, args.string(Keys.WORKSPACE), args.string("name"));
                String kind = Views.storeKind(s).equals(Keys.RASTER) ? "coveragestores" : "datastores";
                ctx.trace().curl("GET", Keys.REST_WORKSPACES + s.getWorkspace().getName() + "/" + kind + "/" + s.getName() + Keys.JSON_EXT, null, null);
                Map<String, Object> m = Views.store(s);
                List<String> resources = new ArrayList<>();
                for (ResourceInfo r : catalog.getResourcesByStore(s, ResourceInfo.class)) resources.add(r.getName());
                m.put("resources", resources);
                if (!(s instanceof DataStoreInfo ds)) return m;
                ctx.trace().curl("GET", Keys.REST_WORKSPACES + s.getWorkspace().getName() + "/datastores/" + s.getName() + "/featuretypes.json?list=available", null, null);
                List<String> available;
                try {
                    available = available(catalog, ds);
                } catch (Exception e) {
                    m.put("availableError", JdbcConnectionCheck.rootMessage(e));
                    return m;
                }
                m.put("available", available);
                List<String> names = args.strings("names");
                if (names.isEmpty() && args.bool("detail", false)) {
                    names = new ArrayList<>(resources);
                    names.addAll(available);
                }
                if (names.size() > LayerTools.MAX_BULK) throw new ToolException(names.size() + " feature types; pass names, at most " + LayerTools.MAX_BULK);
                if (!names.isEmpty()) m.put("tables", tables(catalog, ds, names));
                return m;
            }
        };
    }

    static List<String> available(Catalog catalog, DataStoreInfo ds) throws java.io.IOException {
        Set<String> published = new java.util.HashSet<>();
        for (ResourceInfo r : catalog.getResourcesByStore(ds, ResourceInfo.class)) published.add(r.getNativeName());
        List<String> out = new ArrayList<>();
        for (Name n : catalog.getResourcePool().getDataStore(ds).getNames()) if (!published.contains(n.getLocalPart())) out.add(n.getLocalPart());
        java.util.Collections.sort(out);
        return out;
    }

    static List<Map<String, Object>> tables(Catalog catalog, DataStoreInfo ds, List<String> names) throws java.io.IOException {
        var da = catalog.getResourcePool().getDataStore(ds);
        List<Map<String, Object>> out = new ArrayList<>();
        for (String name : names) {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("name", name);
            t.put("published", catalog.getFeatureTypeByDataStore(ds, name) != null);
            try {
                List<Map<String, Object>> geoms = new ArrayList<>();
                for (PropertyDescriptor d : da.getSchema(new NameImpl(name)).getDescriptors()) {
                    if (!(d instanceof GeometryDescriptor g)) continue;
                    Map<String, Object> geom = new LinkedHashMap<>();
                    geom.put("column", g.getLocalName());
                    geom.put("type", g.getType().getBinding().getSimpleName());
                    geom.put("srid", srid(g));
                    geoms.add(geom);
                }
                t.put("geometries", geoms);
            } catch (Exception e) {
                t.put("error", JdbcConnectionCheck.rootMessage(e));
            }
            out.add(t);
        }
        return out;
    }

    static Integer srid(GeometryDescriptor g) {
        if (g.getCoordinateReferenceSystem() == null) return null;
        try {
            return CRS.lookupEpsgCode(g.getCoordinateReferenceSystem(), false);
        } catch (Exception e) {
            return null;
        }
    }

    GsTool create() {
        return new GsTool() {
            public McpSchema.Tool definition() {
                return McpSchema.Tool.builder("gs_create_store", Schema.object()
                        .string(Keys.WORKSPACE, null, true)
                        .string("name", null, true)
                        .string("type", "Shapefile, Directory of spatial files (shapefiles), PostGIS, GeoPackage, GeoTIFF, ImageMosaic, or another name from gs_list_store_types", true)
                        .map("params", "Shapefile, Directory, raster: {url}. PostGIS: {host, port, user, passwd, database, schema}. GeoPackage: {database}, relative paths resolve in the data directory. Other vector types: keys from gs_list_store_types. A GeoTIFF url of s3://, gs:// or http(s):// is read as a remote COG, S3 credentials come from the server", true)
                        .boolOrStringArray(Keys.PUBLISH, "true: also publish the store's only layer; [names]: publish those tables, at most " + LayerTools.MAX_BULK, false).build())
                        .description("Create a vector or raster store; type is matched at runtime against installed GeoTools plugins").build();
            }
            public Object call(ToolContext ctx, Args args) throws Exception {
                WorkspaceInfo ws = Lookups.workspace(catalog, args.string(Keys.WORKSPACE));
                String name = args.string("name");
                String type = args.string("type");
                Map<String, Object> params = args.map("params");
                StoreInfo existing = catalog.getStoreByName(ws, name, StoreInfo.class);
                Map<String, Object> m;
                if (existing != null) {
                    m = Views.store(existing);
                    m.put(Keys.CREATED, false);
                } else {
                    m = createStore(ctx, ws, name, type, params);
                }
                if (args.isList(Keys.PUBLISH) || args.bool(Keys.PUBLISH, false)) {
                    m.put("layers", new LayerTools(catalog, render).publishFromStore(ctx, catalog.getStoreByName(ws, name, StoreInfo.class), args));
                }
                return m;
            }
        };
    }

    Map<String, Object> createStore(ToolContext ctx, WorkspaceInfo ws, String name, String type, Map<String, Object> params) {
        var vector = DiscoveryTools.vectorFactory(type);
        if (vector.isPresent()) return createVector(ctx, ws, name, type, vector.get(), params);
        var raster = DiscoveryTools.rasterFormat(type);
        if (raster.isPresent()) return createRaster(ctx, ws, name, raster.get(), params);
        throw new ToolException("Unknown store type '" + type + "'. Call gs_list_store_types for the names available on this server.");
    }

    Map<String, Object> createVector(ToolContext ctx, WorkspaceInfo ws, String name, String type, DataAccessFactory factory, Map<String, Object> given) {
        Map<String, Object> params = withDefaultDbtype(factory, given);
        validateParams(type, factory, params);
        CatalogBuilder builder = new CatalogBuilder(catalog);
        builder.setWorkspace(ws);
        DataStoreInfo ds = builder.buildDataStore(name);
        ds.setType(factory.getDisplayName());
        Map<String, Serializable> conn = connectionParams(ws, params);
        JdbcConnectionCheck.verify(factory, name, ResourcePool.getParams(new LinkedHashMap<>(conn), catalog.getResourceLoader()));
        ds.getConnectionParameters().putAll(conn);
        Set<String> secrets = Views.secretParams(ds);
        List<Map<String, Object>> entries = new ArrayList<>();
        conn.forEach((k, v) -> entries.add(Map.of("@key", k, "$", secrets.contains(k) ? "********" : String.valueOf(v))));
        ctx.trace().curl("POST", Keys.REST_WORKSPACES + ws.getName() + "/datastores", Keys.APPLICATION_JSON,
                Views.json(Map.of("dataStore", Map.of("name", name, "type", ds.getType(),
                        "connectionParameters", Map.of("entry", entries)))));
        catalog.add(ds);
        Map<String, Object> m = Views.store(ds);
        m.put(Keys.CREATED, true);
        if ("postgis".equalsIgnoreCase(String.valueOf(conn.get(DBTYPE)))) {
            m.put("warnings", PostgisPrivileges.check(catalog, ds));
        }
        return m;
    }

    static Map<String, Object> withDefaultDbtype(DataAccessFactory factory, Map<String, Object> params) {
        if (params.containsKey(DBTYPE)) return params;
        for (var p : factory.getParametersInfo()) {
            if (DBTYPE.equals(p.key) && p.sample != null) {
                Map<String, Object> out = new LinkedHashMap<>(params);
                out.put(DBTYPE, p.sample);
                return out;
            }
        }
        return params;
    }

    static void validateParams(String type, DataAccessFactory factory, Map<String, Object> params) {
        Set<String> known = new java.util.TreeSet<>();
        Set<String> missing = new java.util.TreeSet<>();
        for (var p : factory.getParametersInfo()) {
            known.add(p.key);
            if (p.required && !params.containsKey(p.key) && !NAMESPACE.equals(p.key)) missing.add(p.key);
        }
        Set<String> unknown = new java.util.TreeSet<>(params.keySet());
        unknown.removeAll(known);
        unknown.remove(NAMESPACE);
        if (unknown.isEmpty() && missing.isEmpty()) return;
        throw new ToolException("Invalid params for " + type + ":"
                + (unknown.isEmpty() ? "" : " unknown keys " + unknown + ";")
                + (missing.isEmpty() ? "" : " missing required keys " + missing + ";")
                + " valid keys: " + known);
    }

    Map<String, Serializable> connectionParams(WorkspaceInfo ws, Map<String, Object> params) {
        Map<String, Serializable> conn = new LinkedHashMap<>();
        params.forEach((k, v) -> conn.put(k, v == null ? null : String.valueOf(v)));
        if (!conn.containsKey(NAMESPACE)) {
            var ns = catalog.getNamespaceByPrefix(ws.getName());
            if (ns != null) conn.put(NAMESPACE, ns.getURI());
        }
        return conn;
    }

    Map<String, Object> createRaster(ToolContext ctx, WorkspaceInfo ws, String name, AbstractGridFormat format, Map<String, Object> params) {
        Object url = params.get("url");
        if (url == null) throw new ToolException("Raster store needs params.url");
        CatalogBuilder builder = new CatalogBuilder(catalog);
        builder.setWorkspace(ws);
        CoverageStoreInfo cs = builder.buildCoverageStore(name);
        cs.setType(format.getName());
        cs.setURL(String.valueOf(url));
        Map<String, Object> body = new LinkedHashMap<>(Map.of("name", name, "type", cs.getType(), Keys.WORKSPACE, ws.getName()));
        if (RemoteCog.applies(cs.getType(), cs.getURL())) {
            RemoteCog.Reader reader = RemoteCog.reader(cs.getURL());
            RemoteCog.requireInstalled(reader, StoreTools.class.getClassLoader());
            cs.setURL(RemoteCog.storeUrl(cs.getURL()));
            RemoteCog.configure(cs, reader);
            body.put("metadata", Map.of("entry", Map.of("@key", "CogSettings.Key", "cogSettingsStore", Map.of("rangeReaderSettings", reader.type()))));
        }
        body.put("url", cs.getURL());
        ctx.trace().curl("POST", Keys.REST_WORKSPACES + ws.getName() + "/coveragestores", Keys.APPLICATION_JSON,
                Views.json(Map.of("coverageStore", body)));
        catalog.add(cs);
        Map<String, Object> m = Views.store(cs);
        m.put(Keys.CREATED, true);
        return m;
    }
}
