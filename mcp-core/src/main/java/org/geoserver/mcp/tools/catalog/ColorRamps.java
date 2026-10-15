package org.geoserver.mcp.tools.catalog;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.geoserver.mcp.tool.ToolException;

final class ColorRamps {
    static final String CATEGORICAL = "categorical";

    static final Map<String, String[]> RAMPS = Map.of(
            CATEGORICAL, new String[] {"#1f77b4", "#ff7f0e", "#2ca02c", "#d62728", "#9467bd", "#8c564b", "#e377c2", "#7f7f7f", "#bcbd22", "#17becf"},
            "blues", new String[] {"#deebf7", "#08306b"},
            "reds", new String[] {"#fee0d2", "#67000d"},
            "greens", new String[] {"#e5f5e0", "#00441b"},
            "viridis", new String[] {"#440154", "#3b528b", "#21918c", "#5ec962", "#fde725"},
            "spectral", new String[] {"#9e0142", "#f46d43", "#fee08b", "#e6f598", "#66c2a5", "#5e4fa2"});

    private ColorRamps() {}

    static List<String> colors(String ramp, int count) {
        String[] anchors = RAMPS.get(ramp);
        if (anchors == null) throw new ToolException("Unknown ramp '" + ramp + "'; use one of " + new TreeSet<>(RAMPS.keySet()));
        List<String> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            out.add(CATEGORICAL.equals(ramp) ? anchors[i % anchors.length] : interpolate(anchors, count == 1 ? 0 : (double) i / (count - 1)));
        }
        return out;
    }

    static String interpolate(String[] anchors, double t) {
        double pos = t * (anchors.length - 1);
        int i = Math.min((int) pos, anchors.length - 2);
        double f = pos - i;
        Color a = Color.decode(anchors[i]);
        Color b = Color.decode(anchors[i + 1]);
        return String.format("#%02x%02x%02x", mix(a.getRed(), b.getRed(), f), mix(a.getGreen(), b.getGreen(), f), mix(a.getBlue(), b.getBlue(), f));
    }

    private static int mix(int a, int b, double f) {
        return (int) Math.round(a + (b - a) * f);
    }
}
