package org.geoserver.mcp.tools.catalog;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.geoserver.catalog.AttributeTypeInfo;
import org.geoserver.catalog.Catalog;
import org.geoserver.catalog.CoverageInfo;
import org.geoserver.catalog.CoverageStoreInfo;
import org.geoserver.catalog.DataStoreInfo;
import org.geoserver.catalog.FeatureTypeInfo;
import org.geoserver.catalog.LayerGroupInfo;
import org.geoserver.catalog.LayerInfo;
import org.geoserver.catalog.NamespaceInfo;
import org.geoserver.catalog.PublishedInfo;
import org.geoserver.catalog.ResourceInfo;
import org.geoserver.catalog.StoreInfo;
import org.geoserver.catalog.StyleInfo;
import org.geoserver.catalog.WMSStoreInfo;
import org.geoserver.catalog.WMTSStoreInfo;
import org.geoserver.catalog.WorkspaceInfo;
import org.geoserver.mcp.tool.ToolException;
import org.geoserver.platform.GeoServerExtensions;
import org.geoserver.security.GeoServerSecurityManager;
import org.geotools.geometry.jts.ReferencedEnvelope;

public final class Views {
    private static final ObjectMapper JSON = new ObjectMapper();

    private Views() {}

    public static Map<String, Object> workspace(WorkspaceInfo ws, Catalog catalog) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", ws.getName());
        m.put("isolated", ws.isIsolated());
        WorkspaceInfo dflt = catalog.getDefaultWorkspace();
        m.put("default", dflt != null && dflt.getName().equals(ws.getName()));
        NamespaceInfo ns = catalog.getNamespaceByPrefix(ws.getName());
        m.put("namespaceUri", ns == null ? null : ns.getURI());
        return m;
    }

    public static String storeKind(StoreInfo s) {
        if (s instanceof DataStoreInfo) return "vector";
        if (s instanceof CoverageStoreInfo) return "raster";
        if (s instanceof WMTSStoreInfo) return "wmts";
        if (s instanceof WMSStoreInfo) return "wms";
        return "unknown";
    }

    public static Map<String, Object> store(StoreInfo s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("workspace", s.getWorkspace().getName());
        m.put("name", s.getName());
        m.put("kind", storeKind(s));
        m.put("type", s.getType());
        m.put("enabled", s.isEnabled());
        if (s instanceof CoverageStoreInfo c) m.put("url", c.getURL());
        Map<String, Object> params = new LinkedHashMap<>();
        Set<String> secrets = secretParams(s);
        s.getConnectionParameters().forEach((k, v) -> params.put(k, secrets.contains(k) ? "********" : String.valueOf(v)));
        m.put("connectionParameters", params);
        return m;
    }

    // Fields GeoServer itself encrypts; never hardcode a key list.
    static Set<String> secretParams(StoreInfo s) {
        return GeoServerExtensions.bean(GeoServerSecurityManager.class).getConfigPasswordEncryptionHelper().getEncryptedFields(s);
    }

    public static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new ToolException("Cannot serialise " + value.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    public static String layerType(LayerInfo l) {
        return l.getType() == null ? "unknown" : l.getType().name().toLowerCase();
    }

    public static Map<String, Object> layerSummary(LayerInfo l) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", l.prefixedName());
        m.put("type", layerType(l));
        m.put("title", l.getResource().getTitle());
        m.put("enabled", l.isEnabled());
        return m;
    }

    public static Map<String, Object> layer(LayerInfo l) {
        ResourceInfo r = l.getResource();
        Map<String, Object> m = layerSummary(l);
        m.put("workspace", r.getStore().getWorkspace().getName());
        m.put("store", r.getStore().getName());
        m.put("nativeName", r.getNativeName());
        m.put("abstract", r.getAbstract());
        m.put("srs", r.getSRS());
        m.put("projectionPolicy", String.valueOf(r.getProjectionPolicy()));
        m.put("nativeBoundingBox", bbox(r.getNativeBoundingBox()));
        m.put("latLonBoundingBox", bbox(r.getLatLonBoundingBox()));
        m.put("defaultStyle", l.getDefaultStyle() == null ? null : l.getDefaultStyle().prefixedName());
        List<String> styles = new ArrayList<>();
        for (StyleInfo s : l.getStyles()) styles.add(s.prefixedName());
        m.put("styles", styles);
        m.put("advertised", l.isAdvertised());
        if (r instanceof FeatureTypeInfo ft) {
            List<Map<String, Object>> attrs = new ArrayList<>();
            try {
                for (AttributeTypeInfo a : ft.attributes()) {
                    attrs.add(Map.of("name", a.getName(), "type", a.getBinding() == null ? "?" : a.getBinding().getSimpleName()));
                }
            } catch (Exception e) {
                attrs.add(Map.of("error", String.valueOf(e.getMessage())));
            }
            m.put("attributes", attrs);
        }
        if (r instanceof CoverageInfo c) {
            m.put("nativeFormat", c.getNativeFormat());
            m.put("supportedFormats", c.getSupportedFormats());
        }
        return m;
    }

    public static Map<String, Object> style(StyleInfo s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", s.getName());
        m.put("workspace", s.getWorkspace() == null ? null : s.getWorkspace().getName());
        m.put("format", s.getFormat());
        m.put("formatVersion", s.getFormatVersion() == null ? null : s.getFormatVersion().toString());
        m.put("filename", s.getFilename());
        return m;
    }

    public static Map<String, Object> layerGroup(LayerGroupInfo g) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", g.getName());
        m.put("workspace", g.getWorkspace() == null ? null : g.getWorkspace().getName());
        m.put("mode", String.valueOf(g.getMode()));
        m.put("title", g.getTitle());
        List<Map<String, Object>> layers = new ArrayList<>();
        for (int i = 0; i < g.getLayers().size(); i++) {
            PublishedInfo p = g.getLayers().get(i);
            StyleInfo s = i < g.getStyles().size() ? g.getStyles().get(i) : null;
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("name", p == null ? null : p.prefixedName());
            e.put("style", s == null ? null : s.prefixedName());
            layers.add(e);
        }
        m.put("layers", layers);
        m.put("bounds", bbox(g.getBounds()));
        return m;
    }

    public static Map<String, Object> bbox(ReferencedEnvelope e) {
        if (e == null) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("minx", e.getMinX());
        m.put("miny", e.getMinY());
        m.put("maxx", e.getMaxX());
        m.put("maxy", e.getMaxY());
        try {
            m.put("crs", org.geotools.referencing.CRS.toSRS(e.getCoordinateReferenceSystem()));
        } catch (Exception ex) {
            m.put("crs", null);
        }
        return m;
    }
}
