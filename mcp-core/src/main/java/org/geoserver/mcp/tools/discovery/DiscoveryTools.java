package org.geoserver.mcp.tools.discovery;

import io.modelcontextprotocol.spec.McpSchema;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.geoserver.mcp.tool.Args;
import org.geoserver.mcp.tool.GsTool;
import org.geoserver.mcp.tool.Schema;
import org.geoserver.mcp.tool.ToolContext;
import org.geoserver.platform.GeoServerExtensions;
import org.geoserver.platform.ModuleStatus;
import org.geotools.api.coverage.grid.Format;
import org.geotools.api.data.DataAccessFactory;
import org.geotools.api.data.DataAccessFinder;
import org.geotools.coverage.grid.io.AbstractGridFormat;
import org.geotools.coverage.grid.io.GridFormatFinder;

public class DiscoveryTools {

    public List<GsTool> tools() {
        return List.of(storeTypes(), modules());
    }

    public static Optional<DataAccessFactory> vectorFactory(String type) {
        Iterator<DataAccessFactory> it = DataAccessFinder.getAvailableDataStores();
        while (it.hasNext()) {
            DataAccessFactory f = it.next();
            if (f.getDisplayName() != null && f.getDisplayName().equalsIgnoreCase(type)) return Optional.of(f);
        }
        return Optional.empty();
    }

    public static Optional<AbstractGridFormat> rasterFormat(String type) {
        for (Format f : GridFormatFinder.getFormatArray()) {
            if (f instanceof AbstractGridFormat g && g.getName() != null && g.getName().equalsIgnoreCase(type)) return Optional.of(g);
        }
        return Optional.empty();
    }

    GsTool storeTypes() {
        return new GsTool() {
            public McpSchema.Tool definition() {
                return McpSchema.Tool.builder("gs_list_store_types", Schema.object()
                        .enumeration("kind", "Restrict to vector or raster", false, "vector", "raster").build())
                        .description("Store types available on this server, discovered at runtime from GeoTools plugins. "
                                + "Vector types list their connection parameters. Raster types take a single 'url'. "
                                + "Use a returned name as the 'type' argument of gs_create_store.").build();
            }
            public Object call(ToolContext ctx, Args args) {
                String kind = args.string("kind", null);
                List<Map<String, Object>> vector = new ArrayList<>();
                if (kind == null || kind.equals("vector")) {
                    Iterator<DataAccessFactory> it = DataAccessFinder.getAvailableDataStores();
                    while (it.hasNext()) {
                        DataAccessFactory f = it.next();
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("name", f.getDisplayName());
                        m.put("description", f.getDescription());
                        List<Map<String, Object>> params = new ArrayList<>();
                        for (DataAccessFactory.Param p : f.getParametersInfo()) {
                            Map<String, Object> pm = new LinkedHashMap<>();
                            pm.put("key", p.key);
                            pm.put("type", p.type == null ? "String" : p.type.getSimpleName());
                            pm.put("required", p.required);
                            pm.put("description", p.description == null ? null : p.description.toString());
                            if (p.sample != null) pm.put("sample", String.valueOf(p.sample));
                            params.add(pm);
                        }
                        m.put("parameters", params);
                        vector.add(m);
                    }
                }
                List<Map<String, Object>> raster = new ArrayList<>();
                if (kind == null || kind.equals("raster")) {
                    for (Format f : GridFormatFinder.getFormatArray()) {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("name", f.getName());
                        m.put("description", f.getDescription());
                        m.put("parameters", List.of(Map.of("key", "url", "type", "String", "required", true,
                                "description", "File path, file:// URL or remote URL depending on the format")));
                        raster.add(m);
                    }
                }
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("vector", vector);
                out.put("raster", raster);
                return out;
            }
        };
    }

    GsTool modules() {
        return new GsTool() {
            public McpSchema.Tool definition() {
                return McpSchema.Tool.builder("gs_list_modules", Schema.object().build())
                        .description("Installed GeoServer modules, extensions and community modules with availability").build();
            }
            public Object call(ToolContext ctx, Args args) {
                List<Map<String, Object>> out = new ArrayList<>();
                for (ModuleStatus s : GeoServerExtensions.extensions(ModuleStatus.class)) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", s.getModule());
                    m.put("title", s.getName());
                    m.put("version", s.getVersion().orElse(null));
                    m.put("category", String.valueOf(s.getCategory()));
                    m.put("available", s.isAvailable());
                    m.put("enabled", s.isEnabled());
                    m.put("message", s.getMessage().orElse(null));
                    out.add(m);
                }
                return Map.of("modules", out);
            }
        };
    }
}
