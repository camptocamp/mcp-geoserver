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
import org.geoserver.mcp.tool.Keys;

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
                        .enumeration("kind", null, false, Keys.VECTOR, Keys.RASTER).build())
                        .description("Store types usable as gs_create_store type; vector ones list their connection parameters, raster ones take url").build();
            }
            public Object call(ToolContext ctx, Args args) {
                String kind = args.string("kind", null);
                List<Map<String, Object>> vector = kind == null || kind.equals(Keys.VECTOR) ? vectorTypes() : List.of();
                List<Map<String, Object>> raster = kind == null || kind.equals(Keys.RASTER) ? rasterTypes() : List.of();
                Map<String, Object> out = new LinkedHashMap<>();
                out.put(Keys.VECTOR, vector);
                out.put(Keys.RASTER, raster);
                return out;
            }
        };
    }

    static List<Map<String, Object>> vectorTypes() {
        List<Map<String, Object>> out = new ArrayList<>();
        Iterator<DataAccessFactory> it = DataAccessFinder.getAvailableDataStores();
        while (it.hasNext()) {
            DataAccessFactory f = it.next();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", f.getDisplayName());
            m.put(Keys.DESCRIPTION, f.getDescription());
            List<Map<String, Object>> params = new ArrayList<>();
            for (DataAccessFactory.Param p : f.getParametersInfo()) params.add(param(p));
            m.put("parameters", params);
            out.add(m);
        }
        return out;
    }

    static Map<String, Object> param(DataAccessFactory.Param p) {
        Map<String, Object> pm = new LinkedHashMap<>();
        pm.put("key", p.key);
        pm.put("type", p.type == null ? "String" : p.type.getSimpleName());
        pm.put("required", p.required);
        pm.put(Keys.DESCRIPTION, p.description == null ? null : p.description.toString());
        if (p.sample != null) pm.put("sample", String.valueOf(p.sample));
        return pm;
    }

    static List<Map<String, Object>> rasterTypes() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Format f : GridFormatFinder.getFormatArray()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", f.getName());
            m.put(Keys.DESCRIPTION, f.getDescription());
            m.put("parameters", List.of(Map.of("key", "url", "type", "String", "required", true,
                    Keys.DESCRIPTION, "File path, file:// URL or remote URL depending on the format")));
            out.add(m);
        }
        return out;
    }

    GsTool modules() {
        return new GsTool() {
            public McpSchema.Tool definition() {
                return McpSchema.Tool.builder("gs_list_modules", Schema.object().build())
                        .description("Installed GeoServer modules, extensions and community modules; available and enabled appear only when false").build();
            }
            public Object call(ToolContext ctx, Args args) {
                List<Map<String, Object>> out = new ArrayList<>();
                for (ModuleStatus s : GeoServerExtensions.extensions(ModuleStatus.class)) out.add(module(s));
                return Map.of("modules", out);
            }
        };
    }

    static Map<String, Object> module(ModuleStatus s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", s.getModule());
        m.put(Keys.TITLE, s.getName());
        m.put("version", s.getVersion().orElse(null));
        m.put("category", String.valueOf(s.getCategory()));
        if (!s.isAvailable()) m.put("available", false);
        if (!s.isEnabled()) m.put(Keys.ENABLED, false);
        m.put("message", s.getMessage().orElse(null));
        return m;
    }
}
