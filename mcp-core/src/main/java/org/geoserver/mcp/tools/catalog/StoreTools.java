package org.geoserver.mcp.tools.catalog;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Set;
import org.geoserver.catalog.CatalogBuilder;
import org.geoserver.catalog.CoverageStoreInfo;
import org.geoserver.catalog.DataStoreInfo;
import org.geoserver.mcp.tool.ToolException;
import org.geoserver.mcp.tools.discovery.DiscoveryTools;
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

public class StoreTools {
    protected final Catalog catalog;

    public StoreTools(Catalog catalog) {
        this.catalog = catalog;
    }

    public List<GsTool> tools() {
        return List.of(list(), get(), create());
    }

    GsTool list() {
        return new GsTool() {
            public McpSchema.Tool definition() {
                return McpSchema.Tool.builder("gs_list_stores", Schema.object().string("workspace", "Restrict to this workspace", false).build())
                        .description("List vector, raster, WMS and WMTS stores").build();
            }
            public Object call(ToolContext ctx, Args args) {
                List<StoreInfo> stores;
                if (args.has("workspace")) {
                    WorkspaceInfo ws = Lookups.workspace(catalog, args.string("workspace"));
                    ctx.trace().curl("GET", "/rest/workspaces/" + ws.getName() + "/datastores.json", null, null);
                    ctx.trace().curl("GET", "/rest/workspaces/" + ws.getName() + "/coveragestores.json", null, null);
                    stores = catalog.getStoresByWorkspace(ws, StoreInfo.class);
                } else {
                    for (WorkspaceInfo ws : catalog.getWorkspaces()) {
                        ctx.trace().curl("GET", "/rest/workspaces/" + ws.getName() + "/datastores.json", null, null);
                        ctx.trace().curl("GET", "/rest/workspaces/" + ws.getName() + "/coveragestores.json", null, null);
                    }
                    stores = catalog.getStores(StoreInfo.class);
                }
                List<Map<String, Object>> out = new ArrayList<>();
                for (StoreInfo s : stores) {
                    Map<String, Object> v = Views.store(s);
                    v.remove("connectionParameters");
                    out.add(v);
                }
                return Map.of("stores", out);
            }
        };
    }

    GsTool get() {
        return new GsTool() {
            public McpSchema.Tool definition() {
                return McpSchema.Tool.builder("gs_get_store", Schema.object()
                        .string("workspace", "Workspace name", true)
                        .string("name", "Store name", true).build())
                        .description("Describe a store: type, connection parameters (secrets masked), published resources").build();
            }
            public Object call(ToolContext ctx, Args args) {
                StoreInfo s = Lookups.store(catalog, args.string("workspace"), args.string("name"));
                String kind = Views.storeKind(s).equals("raster") ? "coveragestores" : "datastores";
                ctx.trace().curl("GET", "/rest/workspaces/" + s.getWorkspace().getName() + "/" + kind + "/" + s.getName() + ".json", null, null);
                Map<String, Object> m = Views.store(s);
                List<String> resources = new ArrayList<>();
                for (ResourceInfo r : catalog.getResourcesByStore(s, ResourceInfo.class)) resources.add(r.getName());
                m.put("resources", resources);
                return m;
            }
        };
    }

    GsTool create() {
        return new GsTool() {
            public McpSchema.Tool definition() {
                return McpSchema.Tool.builder("gs_create_store", Schema.object()
                        .string("workspace", "Workspace name", true)
                        .string("name", "Store name", true)
                        .string("type", "Store type name from gs_list_store_types, e.g. Shapefile, PostGIS, GeoTIFF, GeoPackage", true)
                        .map("params", "Connection parameters. Vector: keys from gs_list_store_types. Raster: {\"url\": ...}", true).build())
                        .description("Create a vector or raster store. Type is matched at runtime against installed GeoTools plugins, so community formats work when installed.").build();
            }
            public Object call(ToolContext ctx, Args args) {
                WorkspaceInfo ws = Lookups.workspace(catalog, args.string("workspace"));
                String name = args.string("name");
                String type = args.string("type");
                Map<String, Object> params = args.map("params");
                StoreInfo existing = catalog.getStoreByName(ws, name, StoreInfo.class);
                if (existing != null) {
                    Map<String, Object> m = Views.store(existing);
                    m.put("created", false);
                    return m;
                }
                CatalogBuilder builder = new CatalogBuilder(catalog);
                builder.setWorkspace(ws);
                var vector = DiscoveryTools.vectorFactory(type);
                if (vector.isPresent()) {
                    var factory = vector.get();
                    Set<String> known = new java.util.TreeSet<>();
                    Set<String> missing = new java.util.TreeSet<>();
                    for (var p : factory.getParametersInfo()) {
                        known.add(p.key);
                        if (p.required && !params.containsKey(p.key) && !"namespace".equals(p.key)) missing.add(p.key);
                    }
                    Set<String> unknown = new java.util.TreeSet<>(params.keySet());
                    unknown.removeAll(known);
                    unknown.remove("namespace");
                    if (!unknown.isEmpty() || !missing.isEmpty()) {
                        throw new ToolException("Invalid params for " + type + ":"
                                + (unknown.isEmpty() ? "" : " unknown keys " + unknown + ";")
                                + (missing.isEmpty() ? "" : " missing required keys " + missing + ";")
                                + " valid keys: " + known);
                    }
                    DataStoreInfo ds = builder.buildDataStore(name);
                    ds.setType(vector.get().getDisplayName());
                    Map<String, Serializable> conn = new LinkedHashMap<>();
                    params.forEach((k, v) -> conn.put(k, v == null ? null : String.valueOf(v)));
                    if (!conn.containsKey("namespace")) {
                        var ns = catalog.getNamespaceByPrefix(ws.getName());
                        if (ns != null) conn.put("namespace", ns.getURI());
                    }
                    JdbcConnectionCheck.verify(factory, name, conn);
                    ds.getConnectionParameters().putAll(conn);
                    Set<String> secrets = Views.secretParams(ds);
                    List<Map<String, Object>> entries = new ArrayList<>();
                    conn.forEach((k, v) -> entries.add(Map.of("@key", k, "$", secrets.contains(k) ? "********" : String.valueOf(v))));
                    ctx.trace().curl("POST", "/rest/workspaces/" + ws.getName() + "/datastores", "application/json",
                            Views.json(Map.of("dataStore", Map.of("name", name, "type", ds.getType(),
                                    "connectionParameters", Map.of("entry", entries)))));
                    catalog.add(ds);
                    Map<String, Object> m = Views.store(ds);
                    m.put("created", true);
                    if ("postgis".equalsIgnoreCase(String.valueOf(conn.get("dbtype")))) {
                        m.put("warnings", PostgisPrivileges.check(catalog, ds));
                    }
                    return m;
                }
                var raster = DiscoveryTools.rasterFormat(type);
                if (raster.isPresent()) {
                    Object url = params.get("url");
                    if (url == null) throw new ToolException("Raster store needs params.url");
                    CoverageStoreInfo cs = builder.buildCoverageStore(name);
                    cs.setType(raster.get().getName());
                    cs.setURL(String.valueOf(url));
                    ctx.trace().curl("POST", "/rest/workspaces/" + ws.getName() + "/coveragestores", "application/json",
                            "{\"coverageStore\":{\"name\":\"" + name + "\",\"type\":\"" + cs.getType() + "\",\"url\":\"" + url + "\",\"workspace\":\"" + ws.getName() + "\"}}");
                    catalog.add(cs);
                    Map<String, Object> m = Views.store(cs);
                    m.put("created", true);
                    return m;
                }
                throw new ToolException("Unknown store type '" + type + "'. Call gs_list_store_types for the names available on this server.");
            }
        };
    }
}
