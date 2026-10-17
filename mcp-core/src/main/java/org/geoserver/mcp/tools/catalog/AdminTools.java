package org.geoserver.mcp.tools.catalog;

import org.geoserver.catalog.CascadeDeleteVisitor;
import org.geoserver.catalog.CatalogInfo;
import org.geoserver.catalog.LayerGroupInfo;
import org.geoserver.catalog.LayerInfo;
import org.geoserver.catalog.NamespaceInfo;
import org.geoserver.catalog.ResourceInfo;
import org.geoserver.catalog.StoreInfo;
import org.geoserver.catalog.StyleInfo;
import org.geoserver.catalog.WorkspaceInfo;
import org.geoserver.mcp.tool.ToolException;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.geoserver.catalog.Catalog;
import org.geoserver.config.GeoServer;
import org.geoserver.mcp.Version;
import org.geoserver.mcp.tool.Args;
import org.geoserver.mcp.tool.GsTool;
import org.geoserver.mcp.tool.Schema;
import org.geoserver.mcp.tool.ToolContext;
import org.geoserver.platform.GeoServerExtensions;
import org.geoserver.platform.ModuleStatus;
import org.geoserver.mcp.tool.Keys;

public class AdminTools {
    private static final String RECURSE = "recurse";

    protected final Catalog catalog;
    protected final GeoServer geoServer;

    public AdminTools(Catalog catalog, GeoServer geoServer) {
        this.catalog = catalog;
        this.geoServer = geoServer;
    }

    public List<GsTool> tools() {
        return List.of(status(), delete(), reload());
    }

    GsTool status() {
        return new GsTool() {
            public McpSchema.Tool definition() {
                return McpSchema.Tool.builder("gs_status", Schema.object().build())
                        .description("Server overview: versions, proxy base URL, catalog counts, installed module count").build();
            }
            public Object call(ToolContext ctx, Args args) {
                ctx.trace().curl("GET", "/rest/about/version.json", null, null);
                ctx.trace().curl("GET", "/rest/about/status.json", null, null);
                Map<String, Object> m = new LinkedHashMap<>();
                String gsVersion = GeoServer.class.getPackage().getImplementationVersion();
                m.put("geoserverVersion", gsVersion == null ? "unknown" : gsVersion);
                m.put("mcpVersion", Version.current());
                m.put("proxyBaseUrl", geoServer.getGlobal().getSettings().getProxyBaseUrl());
                m.put("workspaceCount", catalog.getWorkspaces().size());
                m.put("storeCount", catalog.getStores(org.geoserver.catalog.StoreInfo.class).size());
                m.put("layerCount", catalog.getLayers().size());
                m.put("layerGroupCount", catalog.getLayerGroups().size());
                m.put("styleCount", catalog.getStyles().size());
                m.put("moduleCount", GeoServerExtensions.extensions(ModuleStatus.class).size());
                return m;
            }
        };
    }

    GsTool delete() {
        return new GsTool() {
            public McpSchema.Tool definition() {
                return McpSchema.Tool.builder("gs_delete", Schema.object()
                        .enumeration("kind", null, true, Keys.WORKSPACE, Keys.STORE, Keys.LAYER, Keys.STYLE, "layergroup")
                        .string(Keys.WORKSPACE, "Store: required; style, layergroup: optional; layer: unused, qualify name instead", false)
                        .string("name", null, true)
                        .bool(RECURSE, "Also delete everything that depends on it", false).build())
                        .description("Delete a catalog object. Without recurse, refuses if other objects still reference it.").build();
            }
            public Object call(ToolContext ctx, Args args) {
                String kind = args.string("kind");
                String ws = args.string(Keys.WORKSPACE, null);
                String name = args.string("name");
                boolean recurse = args.bool(RECURSE, false);
                CatalogInfo target = switch (kind) {
                    case Keys.WORKSPACE -> Lookups.workspace(catalog, name);
                    case Keys.STORE -> Lookups.store(catalog, args.string(Keys.WORKSPACE), name);
                    case Keys.LAYER -> Lookups.layer(catalog, name);
                    case Keys.STYLE -> Lookups.style(catalog, ws, name);
                    case "layergroup" -> Lookups.layerGroup(catalog, ws, name);
                    default -> throw new ToolException("Unknown kind '" + kind + "'");
                };
                ctx.trace().curl("DELETE", restPath(kind, ws, name, target) + "?recurse=" + recurse, null, null);
                try {
                    remove(target, recurse);
                } catch (ToolException e) {
                    throw e;
                } catch (RuntimeException e) {
                    throw new ToolException("Cannot delete " + kind + " '" + name + "': " + e.getMessage() + ". Pass recurse=true to cascade.");
                }
                return Map.of("deleted", true, "kind", kind, "name", name, RECURSE, recurse);
            }
        };
    }

    void remove(CatalogInfo target, boolean recurse) {
        if (recurse) {
            target.accept(new CascadeDeleteVisitor(catalog));
        } else if (target instanceof LayerInfo layer) {
            ResourceInfo resource = layer.getResource();
            catalog.remove(layer);
            catalog.remove(resource);
        } else if (target instanceof StoreInfo store) {
            if (!catalog.getResourcesByStore(store, ResourceInfo.class).isEmpty()) {
                throw new ToolException("Store '" + store.getName() + "' still has published resources; pass recurse=true");
            }
            catalog.remove(store);
        } else if (target instanceof WorkspaceInfo workspace) {
            NamespaceInfo ns = catalog.getNamespaceByPrefix(workspace.getName());
            catalog.remove(workspace);
            if (ns != null) catalog.remove(ns);
        } else if (target instanceof StyleInfo style) {
            catalog.remove(style);
        } else if (target instanceof LayerGroupInfo group) {
            catalog.remove(group);
        }
    }

    static String restPath(String kind, String ws, String name, CatalogInfo target) {
        return switch (kind) {
            case Keys.WORKSPACE -> Keys.REST_WORKSPACES + name;
            case Keys.STORE -> Keys.REST_WORKSPACES + ws + "/" + (target instanceof org.geoserver.catalog.CoverageStoreInfo ? "coveragestores/" : "datastores/") + name;
            case Keys.LAYER -> "/rest/layers/" + name;
            case Keys.STYLE -> ws == null ? "/rest/styles/" + name : Keys.REST_WORKSPACES + ws + "/styles/" + name;
            default -> ws == null ? "/rest/layergroups/" + name : Keys.REST_WORKSPACES + ws + "/layergroups/" + name;
        };
    }

    GsTool reload() {
        return new GsTool() {
            public McpSchema.Tool definition() {
                return McpSchema.Tool.builder("gs_reload", Schema.object().build())
                        .description("Reload the catalog and configuration from the data directory").build();
            }
            public Object call(ToolContext ctx, Args args) throws Exception {
                ctx.trace().curl("POST", "/rest/reload", null, null);
                geoServer.reload();
                return Map.of("reloaded", true);
            }
        };
    }
}
