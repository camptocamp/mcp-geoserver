package org.geoserver.mcp.tool;

import java.util.ArrayList;
import java.util.List;

public final class CommandTrace {
    private final List<String> lines = new ArrayList<>();

    public void curl(String method, String path, String contentType, String body) {
        StringBuilder sb = new StringBuilder("curl -u \"$GS_AUTH\" -X ").append(method);
        if (contentType != null) sb.append(" -H 'Content-Type: ").append(contentType).append('\'');
        if (body != null) sb.append(" -d '").append(body.replace("'", "'\\''")).append('\'');
        sb.append(" '").append(path).append('\'');
        lines.add(sb.toString());
    }

    public List<String> lines() {
        return List.copyOf(lines);
    }

    public String render(String baseUrl) {
        StringBuilder sb = new StringBuilder("Equivalent commands:\n");
        for (String l : lines) {
            sb.append(l.replace(" '/", " '" + baseUrl + "/")).append('\n');
        }
        return sb.toString().stripTrailing();
    }
}
