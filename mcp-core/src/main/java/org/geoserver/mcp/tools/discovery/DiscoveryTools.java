package org.geoserver.mcp.tools.discovery;

import io.modelcontextprotocol.spec.McpSchema;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.geoserver.mcp.tool.Args;
import org.geoserver.mcp.tool.GsTool;
import org.geoserver.mcp.tool.Schema;
import org.geoserver.mcp.tool.ToolContext;
import org.geoserver.mcp.tool.ToolException;
import org.geoserver.platform.GeoServerExtensions;
import org.geoserver.platform.ModuleStatus;
import org.geotools.api.coverage.grid.Format;
import org.geotools.api.data.DataAccessFactory;
import org.geotools.api.data.DataAccessFinder;
import org.geotools.coverage.grid.io.AbstractGridFormat;
import org.geotools.coverage.grid.io.GridFormatFinder;
import org.geoserver.mcp.tool.Keys;

public class DiscoveryTools {
    private static final String PARAMETERS = "parameters";
    private static final String REQUIRED = "required";
    private static final String URL = "url";
    private static final int MAX_SAMPLE_CHARS = 40;

    public List<GsTool> tools() {
        return List.of(storeTypes(), modules());
    }

    public static Optional<DataAccessFactory> vectorFactory(String type) {
        List<DataAccessFactory> all = new ArrayList<>();
        DataAccessFinder.getAvailableDataStores().forEachRemaining(all::add);
        return byName(all, DataAccessFactory::getDisplayName, type);
    }

    public static Optional<AbstractGridFormat> rasterFormat(String type) {
        List<AbstractGridFormat> all = new ArrayList<>();
        for (Format f : GridFormatFinder.getFormatArray()) if (f instanceof AbstractGridFormat g) all.add(g);
        return byName(all, AbstractGridFormat::getName, type);
    }

    static <T> Optional<T> byName(List<T> candidates, Function<T, String> name, String type) {
        String wanted = type.toLowerCase(Locale.ROOT);
        List<T> prefixed = new ArrayList<>();
        for (T c : candidates) {
            String n = name.apply(c) == null ? "" : name.apply(c).toLowerCase(Locale.ROOT);
            if (n.equals(wanted)) return Optional.of(c);
            if (n.startsWith(wanted)) prefixed.add(c);
        }
        return prefixed.size() == 1 ? Optional.of(prefixed.get(0)) : Optional.empty();
    }

    GsTool storeTypes() {
        return new GsTool() {
            public McpSchema.Tool definition() {
                return McpSchema.Tool.builder("gs_list_store_types", Schema.object()
                        .enumeration("kind", null, false, Keys.VECTOR, Keys.RASTER)
                        .string("type", "One store type: return all of its parameters", false).build())
                        .description("Store types usable as gs_create_store type, each with its required parameter keys; type returns every parameter of that one type").build();
            }
            public Object call(ToolContext ctx, Args args) {
                String kind = args.string("kind", null);
                String type = args.string("type", null);
                List<Map<String, Object>> vector = kind == null || kind.equals(Keys.VECTOR) ? vectorTypes(type) : List.of();
                List<Map<String, Object>> raster = kind == null || kind.equals(Keys.RASTER) ? rasterTypes(type) : List.of();
                if (type != null && vector.isEmpty() && raster.isEmpty()) throw new ToolException("Unknown store type '" + type + "'");
                Map<String, Object> out = new LinkedHashMap<>();
                out.put(Keys.VECTOR, vector);
                out.put(Keys.RASTER, raster);
                return out;
            }
        };
    }

    static List<Map<String, Object>> vectorTypes(String type) {
        if (type != null) return vectorFactory(type).map(f -> List.of(vectorDetail(f))).orElse(List.of());
        List<Map<String, Object>> out = new ArrayList<>();
        Iterator<DataAccessFactory> it = DataAccessFinder.getAvailableDataStores();
        while (it.hasNext()) {
            DataAccessFactory f = it.next();
            out.add(summary(f.getDisplayName(), requiredKeys(f)));
        }
        return out;
    }

    static List<String> requiredKeys(DataAccessFactory f) {
        List<String> keys = new ArrayList<>();
        for (DataAccessFactory.Param p : f.getParametersInfo()) if (p.required) keys.add(p.key);
        return keys;
    }

    static Map<String, Object> summary(String name, List<String> required) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put(REQUIRED, required);
        return m;
    }

    static Map<String, Object> vectorDetail(DataAccessFactory f) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", f.getDisplayName());
        m.put(Keys.DESCRIPTION, f.getDescription());
        List<Map<String, Object>> params = new ArrayList<>();
        for (DataAccessFactory.Param p : f.getParametersInfo()) params.add(param(p));
        m.put(PARAMETERS, params);
        return m;
    }

    static Map<String, Object> param(DataAccessFactory.Param p) {
        Map<String, Object> pm = new LinkedHashMap<>();
        pm.put("key", p.key);
        pm.put(REQUIRED, p.required);
        pm.put(Keys.DESCRIPTION, p.description == null ? null : p.description.toString());
        // The Shapefile timezone sample is a TimeZone whose toString runs to 400 chars.
        String sample = p.sample == null ? null : String.valueOf(p.sample);
        if (sample != null && sample.length() <= MAX_SAMPLE_CHARS) pm.put("sample", sample);
        return pm;
    }

    static List<Map<String, Object>> rasterTypes(String type) {
        if (type != null) return rasterFormat(type).map(f -> List.of(rasterDetail(f))).orElse(List.of());
        List<Map<String, Object>> out = new ArrayList<>();
        for (Format f : GridFormatFinder.getFormatArray()) out.add(summary(f.getName(), List.of(URL)));
        return out;
    }

    static Map<String, Object> rasterDetail(Format f) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", f.getName());
        m.put(Keys.DESCRIPTION, f.getDescription());
        m.put(PARAMETERS, List.of(Map.of("key", URL, REQUIRED, true,
                Keys.DESCRIPTION, "File path, file:// URL or remote URL depending on the format")));
        return m;
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
