package org.geoserver.mcp.tools.catalog;

import io.modelcontextprotocol.spec.McpSchema;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.geoserver.catalog.AttributeTypeInfo;
import org.geoserver.catalog.Catalog;
import org.geoserver.catalog.CoverageInfo;
import org.geoserver.catalog.DimensionDefaultValueSetting;
import org.geoserver.catalog.DimensionInfo;
import org.geoserver.catalog.DimensionPresentation;
import org.geoserver.catalog.FeatureTypeInfo;
import org.geoserver.catalog.LayerInfo;
import org.geoserver.catalog.ResourceInfo;
import org.geoserver.catalog.impl.DimensionInfoImpl;
import org.geoserver.mcp.tool.Args;
import org.geoserver.mcp.tool.GsTool;
import org.geoserver.mcp.tool.Keys;
import org.geoserver.mcp.tool.Schema;
import org.geoserver.mcp.tool.ToolContext;
import org.geoserver.mcp.tool.ToolException;

public class DimensionTools {
    private static final String ATTRIBUTE = "attribute";
    private static final String END_ATTRIBUTE = "endAttribute";
    private static final String PRESENTATION = "presentation";
    private static final String RESOLUTION = "resolution";
    private static final String DEFAULT_VALUE = "defaultValue";
    private static final String REFERENCE_VALUE = "referenceValue";
    private static final String NEAREST_MATCH = "nearestMatch";

    private final Catalog catalog;

    public DimensionTools(Catalog catalog) {
        this.catalog = catalog;
    }

    public List<GsTool> tools() {
        return List.of(setTime());
    }

    GsTool setTime() {
        return new GsTool() {
            public McpSchema.Tool definition() {
                return McpSchema.Tool.builder("gs_set_time_dimension", Schema.object()
                        .string("name", Keys.DESC_LAYER_NAME, true)
                        .bool(Keys.ENABLED, "Enable the time dimension, default true; false removes it from WMS", false)
                        .string(ATTRIBUTE, "Vector layers: date or timestamp attribute holding the time; ignored for rasters (ImageMosaic time comes from the index)", false)
                        .string(END_ATTRIBUTE, "Vector layers: optional end time attribute, for features valid over a period", false)
                        .enumeration(PRESENTATION, "How capabilities list the times, default LIST", false, "LIST", "DISCRETE_INTERVAL", "CONTINUOUS_INTERVAL")
                        .string(RESOLUTION, "Step in milliseconds for DISCRETE_INTERVAL, e.g. 86400000 for one day", false)
                        .enumeration(DEFAULT_VALUE, "Time used when a request has none, default MINIMUM", false, "MINIMUM", "MAXIMUM", "NEAREST", "FIXED")
                        .string(REFERENCE_VALUE, "With defaultValue NEAREST or FIXED: an ISO 8601 time, or 'current'", false)
                        .bool(NEAREST_MATCH, "Snap requested times to the nearest available one", false).build())
                        .description("Configure the WMS/WCS time dimension of a vector or ImageMosaic layer").build();
            }
            public Object call(ToolContext ctx, Args args) throws IOException {
                LayerInfo layer = Lookups.layer(catalog, args.string("name"));
                ResourceInfo resource = layer.getResource();
                DimensionInfo time = dimension(resource, args);
                resource.getMetadata().put(ResourceInfo.TIME, time);
                ctx.trace().curl("PUT", resourcePath(resource), Keys.APPLICATION_JSON, Views.json(Map.of(
                        resource instanceof FeatureTypeInfo ? "featureType" : "coverage",
                        Map.of("metadata", Map.of("entry", Map.of("@key", ResourceInfo.TIME, "dimensionInfo", view(time)))))));
                catalog.save(resource);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", layer.prefixedName());
                m.put("time", view(time));
                return m;
            }
        };
    }

    DimensionInfo dimension(ResourceInfo resource, Args args) throws IOException {
        DimensionInfo time = new DimensionInfoImpl();
        time.setEnabled(args.bool(Keys.ENABLED, true));
        if (resource instanceof FeatureTypeInfo ft) {
            time.setAttribute(requireAttribute(ft, args.string(ATTRIBUTE)));
            if (args.has(END_ATTRIBUTE)) time.setEndAttribute(requireAttribute(ft, args.string(END_ATTRIBUTE)));
        } else if (!(resource instanceof CoverageInfo)) {
            throw new ToolException("Layer '" + resource.prefixedName() + "' is neither vector nor raster");
        }
        time.setPresentation(DimensionPresentation.valueOf(args.string(PRESENTATION, "LIST")));
        if (args.has(RESOLUTION)) time.setResolution(resolution(args.string(RESOLUTION)));
        if (time.getPresentation() == DimensionPresentation.DISCRETE_INTERVAL && time.getResolution() == null) {
            throw new ToolException("DISCRETE_INTERVAL needs a resolution in milliseconds");
        }
        DimensionDefaultValueSetting dflt = new DimensionDefaultValueSetting();
        dflt.setStrategyType(DimensionDefaultValueSetting.Strategy.valueOf(args.string(DEFAULT_VALUE, "MINIMUM")));
        if (args.has(REFERENCE_VALUE)) dflt.setReferenceValue(args.string(REFERENCE_VALUE));
        time.setDefaultValue(dflt);
        time.setNearestMatchEnabled(args.bool(NEAREST_MATCH, false));
        time.setUnits(DimensionInfo.TIME_UNITS);
        return time;
    }

    static String requireAttribute(FeatureTypeInfo ft, String name) throws IOException {
        for (AttributeTypeInfo a : ft.attributes()) {
            if (a.getName().equals(name)) return name;
        }
        throw new ToolException("Attribute '" + name + "' not found in " + ft.prefixedName());
    }

    static BigDecimal resolution(String value) {
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException e) {
            throw new ToolException("resolution must be a number of milliseconds");
        }
    }

    static String resourcePath(ResourceInfo r) {
        String store = r.getStore().getWorkspace().getName() + "/" + (r instanceof FeatureTypeInfo ? "datastores/" : "coveragestores/") + r.getStore().getName();
        return Keys.REST_WORKSPACES + store + (r instanceof FeatureTypeInfo ? "/featuretypes/" : "/coverages/") + r.getName() + Keys.JSON_EXT;
    }

    static Map<String, Object> view(DimensionInfo d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(Keys.ENABLED, d.isEnabled());
        m.put(ATTRIBUTE, d.getAttribute());
        m.put(END_ATTRIBUTE, d.getEndAttribute());
        m.put(PRESENTATION, String.valueOf(d.getPresentation()));
        m.put(RESOLUTION, d.getResolution() == null ? null : d.getResolution().toPlainString());
        m.put(DEFAULT_VALUE, String.valueOf(d.getDefaultValue().getStrategyType()));
        m.put(REFERENCE_VALUE, d.getDefaultValue().getReferenceValue());
        m.put(NEAREST_MATCH, d.isNearestMatchEnabled());
        return m;
    }
}
