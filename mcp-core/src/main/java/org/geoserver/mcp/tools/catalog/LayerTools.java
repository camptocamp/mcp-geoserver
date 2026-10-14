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
        return List.of(list(), get(), publish(), update());
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
                        .description("Publish a layer from an existing store. Computes bounds and looks up the SRS.").build();
            }
            public Object call(ToolContext ctx, Args args) throws Exception {
                WorkspaceInfo ws = Lookups.workspace(catalog, args.string("workspace"));
                StoreInfo store = Lookups.store(catalog, ws.getName(), args.string("store"));
                CatalogBuilder builder = new CatalogBuilder(catalog);
                builder.setWorkspace(ws);
                builder.setStore(store);
                ResourceInfo resource;
                if (store instanceof DataStoreInfo) {
                    String nativeName = args.string("nativeName");
                    FeatureTypeInfo ft = builder.buildFeatureType(new NameImpl(nativeName));
                    resource = ft;
                    ctx.trace().curl("POST", "/rest/workspaces/" + ws.getName() + "/datastores/" + store.getName() + "/featuretypes",
                            "application/json", "{\"featureType\":{\"name\":\"" + args.string("name", nativeName) + "\",\"nativeName\":\"" + nativeName + "\"}}");
                } else if (store instanceof CoverageStoreInfo) {
                    CoverageInfo ci = args.has("nativeName") ? builder.buildCoverage(args.string("nativeName")) : builder.buildCoverage();
                    resource = ci;
                    ctx.trace().curl("POST", "/rest/workspaces/" + ws.getName() + "/coveragestores/" + store.getName() + "/coverages",
                            "application/json", "{\"coverage\":{\"name\":\"" + args.string("name", ci.getNativeName()) + "\",\"nativeName\":\"" + ci.getNativeName() + "\"}}");
                } else {
                    throw new ToolException("Store '" + store.getName() + "' is a " + Views.storeKind(store) + " store; only vector and raster stores are supported");
                }
                if (args.has("name")) resource.setName(args.string("name"));
                if (args.has("title")) resource.setTitle(args.string("title"));
                if (args.has("srs")) {
                    resource.setSRS(args.string("srs"));
                    resource.setProjectionPolicy(ProjectionPolicy.FORCE_DECLARED);
                }
                if (resource.getSRS() == null && resource instanceof FeatureTypeInfo ft) builder.lookupSRS(ft, true);
                builder.setupBounds(resource);
                if (catalog.getLayerByName(ws.getName() + ":" + resource.getName()) != null) {
                    throw new ToolException("Layer '" + ws.getName() + ":" + resource.getName() + "' already exists");
                }
                LayerInfo layer = builder.buildLayer(resource);
                catalog.add(resource);
                catalog.add(layer);
                return Views.layer(catalog.getLayerByName(ws.getName() + ":" + resource.getName()));
            }
        };
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
