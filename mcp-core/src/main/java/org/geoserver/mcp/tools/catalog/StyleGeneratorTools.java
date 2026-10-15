package org.geoserver.mcp.tools.catalog;

import io.modelcontextprotocol.spec.McpSchema;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.geoserver.catalog.Catalog;
import org.geoserver.catalog.FeatureTypeInfo;
import org.geoserver.catalog.LayerInfo;
import org.geoserver.mcp.tool.Args;
import org.geoserver.mcp.tool.GsTool;
import org.geoserver.mcp.tool.Keys;
import org.geoserver.mcp.tool.Schema;
import org.geoserver.mcp.tool.ToolContext;
import org.geoserver.mcp.tool.ToolException;
import org.geotools.api.feature.simple.SimpleFeatureType;
import org.geotools.api.feature.type.AttributeDescriptor;
import org.geotools.api.feature.type.GeometryDescriptor;
import org.geotools.api.filter.FilterFactory;
import org.geotools.factory.CommonFactoryFinder;
import org.geotools.feature.FeatureCollection;
import org.geotools.feature.visitor.UniqueVisitor;
import org.geotools.filter.function.RangedClassifier;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.MultiLineString;
import org.locationtech.jts.geom.MultiPoint;
import org.locationtech.jts.geom.Point;

public class StyleGeneratorTools {
    static final int MAX_CATEGORIES = 50;
    private static final String CATEGORIZED = "categorized";
    private static final String ATTRIBUTE = "attribute";
    private static final Map<String, String> CLASSIFIERS = Map.of("equalInterval", "EqualInterval", "quantile", "Quantile", "jenks", "Jenks");

    private final Catalog catalog;
    private final GsTool putStyle;

    public StyleGeneratorTools(Catalog catalog) {
        this.catalog = catalog;
        this.putStyle = new StyleTools(catalog).tools().stream().filter(t -> t.definition().name().equals("gs_put_style")).findFirst().orElseThrow();
    }

    public List<GsTool> tools() {
        return List.of(generate());
    }

    GsTool generate() {
        return new GsTool() {
            public McpSchema.Tool definition() {
                return McpSchema.Tool.builder("gs_generate_style", Schema.object()
                        .string(Keys.LAYER, "Vector layer, workspace:name; its data drives the classes", true)
                        .string(ATTRIBUTE, "Attribute to style on", true)
                        .enumeration("method", "categorized: one rule per distinct value (up to " + MAX_CATEGORIES + "); equalInterval, quantile, jenks: numeric classes", true,
                                CATEGORIZED, "equalInterval", "quantile", "jenks")
                        .integer("classes", "Number of classes for numeric methods, default 5", false)
                        .enumeration("ramp", "Colors; default categorical for categorized, blues otherwise", false,
                                new TreeSet<>(ColorRamps.RAMPS.keySet()).toArray(String[]::new))
                        .string("name", "Style name, default <layer>_<attribute>_<method>", false)
                        .string(Keys.WORKSPACE, "Workspace for the style; omit for a global style", false)
                        .bool("setDefault", "Also make it the layer's default style", false).build())
                        .description("Generate a categorized or classified SLD from the layer's data, store it like gs_put_style and return the rules and SLD").build();
            }
            public Object call(ToolContext ctx, Args args) throws Exception {
                return StyleGeneratorTools.this.call(ctx, args);
            }
        };
    }

    Map<String, Object> call(ToolContext ctx, Args args) throws Exception {
        LayerInfo layer = Lookups.layer(catalog, args.string(Keys.LAYER));
        if (!(layer.getResource() instanceof FeatureTypeInfo ft)) throw new ToolException("Layer '" + layer.prefixedName() + "' is not a vector layer");
        String attribute = args.string(ATTRIBUTE);
        String method = args.string("method");
        AttributeDescriptor descriptor = ft.getFeatureType() instanceof SimpleFeatureType sft ? sft.getDescriptor(attribute) : null;
        if (descriptor == null) throw new ToolException(attributeMessage(attribute, "not found in " + layer.prefixedName()));
        FeatureCollection<?, ?> features = ft.getFeatureSource(null, null).getFeatures();
        List<SldRules.Rule> rules = CATEGORIZED.equals(method)
                ? categories(features, attribute, args.string("ramp", ColorRamps.CATEGORICAL))
                : classes(features, descriptor, method, args.integer("classes", 5), args.string("ramp", "blues"));
        String name = args.string("name", layer.getName() + "_" + attribute + "_" + method);
        String body = SldRules.document(name, geometry(ft.getFeatureType().getGeometryDescriptor()), rules);
        Map<String, Object> putArgs = new LinkedHashMap<>(Map.of("name", name, "body", body));
        if (args.has(Keys.WORKSPACE)) putArgs.put(Keys.WORKSPACE, args.string(Keys.WORKSPACE));
        @SuppressWarnings("unchecked")
        Map<String, Object> out = new LinkedHashMap<>((Map<String, Object>) putStyle.call(ctx, new Args(putArgs)));
        if (args.bool("setDefault", false)) setDefault(ctx, layer, args.string(Keys.WORKSPACE, null), name);
        out.put("rules", rules.stream().map(r -> Map.of(Keys.TITLE, r.title(), "color", r.color())).toList());
        out.put("sld", body);
        return out;
    }

    void setDefault(ToolContext ctx, LayerInfo layer, String workspace, String name) {
        layer.setDefaultStyle(Lookups.style(catalog, workspace, name));
        ctx.trace().curl("PUT", "/rest/layers/" + layer.prefixedName() + Keys.JSON_EXT, Keys.APPLICATION_JSON,
                "{\"layer\":{\"defaultStyle\":{\"name\":\"" + Lookups.qualified(workspace, name) + "\"}}}");
        catalog.save(layer);
    }

    static List<SldRules.Rule> categories(FeatureCollection<?, ?> features, String attribute, String ramp) throws IOException {
        UniqueVisitor visitor = new UniqueVisitor(attribute);
        features.accepts(visitor, null);
        List<Object> values = new ArrayList<>(visitor.getUnique());
        if (values.isEmpty()) throw new ToolException(attributeMessage(attribute, "has no values"));
        if (values.size() > MAX_CATEGORIES) {
            throw new ToolException(values.size() + " distinct values in '" + attribute + "'; categorized allows " + MAX_CATEGORIES + ", use a numeric method or another attribute");
        }
        values.sort(StyleGeneratorTools::compare);
        List<String> colors = ColorRamps.colors(ramp, values.size());
        List<SldRules.Rule> rules = new ArrayList<>();
        for (int i = 0; i < values.size(); i++) {
            Object v = values.get(i);
            rules.add(new SldRules.Rule(v == null ? "(no value)" : String.valueOf(v), SldRules.equalTo(attribute, v), colors.get(i)));
        }
        return rules;
    }

    static List<SldRules.Rule> classes(FeatureCollection<?, ?> features, AttributeDescriptor descriptor, String method, int count, String ramp) {
        String function = CLASSIFIERS.get(method);
        if (function == null) throw new ToolException("Unknown method '" + method + "'");
        if (!Number.class.isAssignableFrom(descriptor.getType().getBinding())) {
            throw new ToolException(attributeMessage(descriptor.getLocalName(), "is not numeric; use method categorized"));
        }
        if (count < 2 || count > 20) throw new ToolException("classes must be between 2 and 20");
        FilterFactory ff = CommonFactoryFinder.getFilterFactory();
        String attribute = descriptor.getLocalName();
        Object result = ff.function(function, ff.property(attribute), ff.literal(count)).evaluate(features);
        if (!(result instanceof RangedClassifier classifier)) throw new ToolException("Could not classify '" + attribute + "' with " + method + "; are there enough distinct values?");
        List<String> colors = ColorRamps.colors(ramp, classifier.getSize());
        List<SldRules.Rule> rules = new ArrayList<>();
        for (int i = 0; i < classifier.getSize(); i++) {
            Object min = classifier.getMin(i);
            Object max = classifier.getMax(i);
            boolean last = i == classifier.getSize() - 1;
            rules.add(new SldRules.Rule(min + " to " + max, SldRules.range(attribute, min, max, last), colors.get(i)));
        }
        return rules;
    }

    static String attributeMessage(String attribute, String problem) {
        return "Attribute '" + attribute + "' " + problem;
    }

    static SldRules.Geometry geometry(GeometryDescriptor g) {
        Class<?> b = g == null ? null : g.getType().getBinding();
        if (b != null && (Point.class.isAssignableFrom(b) || MultiPoint.class.isAssignableFrom(b))) return SldRules.Geometry.POINT;
        if (b != null && (LineString.class.isAssignableFrom(b) || MultiLineString.class.isAssignableFrom(b))) return SldRules.Geometry.LINE;
        return SldRules.Geometry.POLYGON;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static int compare(Object a, Object b) {
        if (a == null) return b == null ? 0 : 1;
        if (b == null) return -1;
        if (a instanceof Comparable ca && a.getClass().isInstance(b)) return ca.compareTo(b);
        return String.valueOf(a).compareTo(String.valueOf(b));
    }
}
