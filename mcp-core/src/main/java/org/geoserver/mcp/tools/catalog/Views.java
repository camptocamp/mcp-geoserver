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
import org.geoserver.mcp.tool.Keys;

public final class Views {
    private static final ObjectMapper JSON = new ObjectMapper();

    private Views() {}

    public static Map<String, Object> workspace(WorkspaceInfo ws, Catalog catalog) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", ws.getName());
        m.put(Keys.ISOLATED, ws.isIsolated());
        WorkspaceInfo dflt = catalog.getDefaultWorkspace();
        m.put("default", dflt != null && dflt.getName().equals(ws.getName()));
        NamespaceInfo ns = catalog.getNamespaceByPrefix(ws.getName());
        m.put("namespaceUri", ns == null ? null : ns.getURI());
        return m;
    }

    public static String storeKind(StoreInfo s) {
        if (s instanceof DataStoreInfo) return Keys.VECTOR;
        if (s instanceof CoverageStoreInfo) return Keys.RASTER;
        if (s instanceof WMTSStoreInfo) return "wmts";
        if (s instanceof WMSStoreInfo) return "wms";
        return "unknown";
    }

    public static Map<String, Object> store(StoreInfo s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(Keys.WORKSPACE, s.getWorkspace().getName());
        m.put("name", s.getName());
        m.put("kind", storeKind(s));
        m.put("type", s.getType());
        m.put(Keys.ENABLED, s.isEnabled());
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
        m.put(Keys.TITLE, l.getResource().getTitle());
        m.put(Keys.ENABLED, l.isEnabled());
        return m;
    }

    public static Map<String, Object> listRow(Map<String, Object> m, boolean workspaceFiltered) {
        if (Boolean.TRUE.equals(m.get(Keys.ENABLED))) m.remove(Keys.ENABLED);
        if (workspaceFiltered) m.remove(Keys.WORKSPACE);
        return m;
    }

    public static Map<String, Object> layer(LayerInfo l) {
        ResourceInfo r = l.getResource();
        Map<String, Object> m = layerSummary(l);
        m.put(Keys.WORKSPACE, r.getStore().getWorkspace().getName());
        m.put(Keys.STORE, r.getStore().getName());
        m.put(Keys.NATIVE_NAME, r.getNativeName());
        m.put(Keys.ABSTRACT, r.getAbstract());
        m.put("srs", r.getSRS());
        m.put("projectionPolicy", String.valueOf(r.getProjectionPolicy()));
        putBbox(m, "nativeBoundingBox", r.getNativeBoundingBox());
        putBbox(m, "latLonBoundingBox", r.getLatLonBoundingBox());
        m.put(Keys.DEFAULT_STYLE, l.getDefaultStyle() == null ? null : l.getDefaultStyle().prefixedName());
        List<String> styles = new ArrayList<>();
        for (StyleInfo s : l.getStyles()) styles.add(s.prefixedName());
        m.put(Keys.STYLES, styles);
        m.put(Keys.ADVERTISED, l.isAdvertised());
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

    public static Map<String, Object> published(LayerInfo l, boolean created, List<String> warnings) {
        ResourceInfo r = l.getResource();
        Map<String, Object> m = layerSummary(l);
        m.put(Keys.STORE, r.getStore().getWorkspace().getName() + ":" + r.getStore().getName());
        m.put(Keys.NATIVE_NAME, r.getNativeName());
        m.put("srs", r.getSRS());
        putBbox(m, "nativeBoundingBox", r.getNativeBoundingBox());
        m.put(Keys.DEFAULT_STYLE, l.getDefaultStyle() == null ? null : l.getDefaultStyle().prefixedName());
        m.put(Keys.CREATED, created);
        if (!warnings.isEmpty()) m.put("warnings", warnings);
        return m;
    }

    public static Map<String, Object> style(StyleInfo s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", s.getName());
        m.put(Keys.WORKSPACE, s.getWorkspace() == null ? null : s.getWorkspace().getName());
        m.put(Keys.FORMAT, s.getFormat());
        m.put("formatVersion", s.getFormatVersion() == null ? null : s.getFormatVersion().toString());
        m.put("filename", s.getFilename());
        return m;
    }

    public static Map<String, Object> layerGroup(LayerGroupInfo g) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", g.getName());
        m.put(Keys.WORKSPACE, g.getWorkspace() == null ? null : g.getWorkspace().getName());
        m.put("mode", String.valueOf(g.getMode()));
        m.put(Keys.TITLE, g.getTitle());
        List<Map<String, Object>> layers = new ArrayList<>();
        for (int i = 0; i < g.getLayers().size(); i++) {
            PublishedInfo p = g.getLayers().get(i);
            StyleInfo s = i < g.getStyles().size() ? g.getStyles().get(i) : null;
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("name", p == null ? null : p.prefixedName());
            e.put(Keys.STYLE, s == null ? null : s.prefixedName());
            layers.add(e);
        }
        m.put(Keys.LAYERS, layers);
        putBbox(m, "bounds", g.getBounds());
        return m;
    }

    static void putBbox(Map<String, Object> m, String key, ReferencedEnvelope e) {
        m.put(key, e == null ? null : bbox(e));
    }

    public static Map<String, Object> bbox(ReferencedEnvelope e) {
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
