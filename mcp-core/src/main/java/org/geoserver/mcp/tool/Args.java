package org.geoserver.mcp.tool;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

public final class Args {
    private final Map<String, Object> raw;

    public Args(Map<String, Object> raw) {
        this.raw = raw == null ? Map.of() : raw;
    }

    public boolean has(String key) {
        return raw.get(key) != null;
    }

    public String string(String key) {
        Object v = raw.get(key);
        if (v == null || v.toString().isBlank()) throw new ToolException("Missing required argument '" + key + "'");
        return v.toString();
    }

    public String string(String key, String dflt) {
        Object v = raw.get(key);
        return v == null || v.toString().isBlank() ? dflt : v.toString();
    }

    public int integer(String key, int dflt) {
        Object v = raw.get(key);
        if (v == null) return dflt;
        if (v instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(v.toString());
        } catch (NumberFormatException e) {
            throw invalid(key, "an integer");
        }
    }

    public boolean bool(String key, boolean dflt) {
        Object v = raw.get(key);
        if (v == null) return dflt;
        if (v instanceof Boolean b) return b;
        return Boolean.parseBoolean(v.toString());
    }

    public List<String> strings(String key) {
        Object v = raw.get(key);
        if (v == null) return List.of();
        if (v instanceof Collection<?> c) {
            List<String> out = new ArrayList<>();
            for (Object o : c) out.add(String.valueOf(o));
            return out;
        }
        return List.of(v.toString());
    }

    public List<Double> numbers(String key) {
        Object v = raw.get(key);
        if (v == null) return List.of();
        if (v instanceof Collection<?> c) {
            List<Double> out = new ArrayList<>();
            for (Object o : c) out.add(o instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(o)));
            return out;
        }
        throw invalid(key, "an array of numbers");
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> map(String key) {
        Object v = raw.get(key);
        if (v == null) return Map.of();
        if (v instanceof Map<?, ?> m) return (Map<String, Object>) m;
        throw invalid(key, "an object");
    }

    private static ToolException invalid(String key, String expected) {
        return new ToolException("Argument '" + key + "' must be " + expected);
    }
}
