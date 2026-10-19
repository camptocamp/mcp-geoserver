package org.geoserver.mcp.tools.catalog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.geoserver.catalog.CatalogBuilder;
import org.geoserver.catalog.DataStoreInfo;
import org.geoserver.catalog.LayerInfo;
import org.geoserver.catalog.ResourceInfo;
import org.geoserver.data.test.SystemTestData;
import org.geoserver.mcp.McpToolTestSupport;
import org.geoserver.mcp.tool.ToolException;
import org.junit.Test;

public class BulkPublishToolsTest extends McpToolTestSupport {
    static final String POLY = "_=the_geom:MultiPolygon:srid=4326,ID:String\np.1=MULTIPOLYGON (((-1 0, 0 1, 1 0, 0 -1, -1 0)))|a\n";
    static final String NO_GEOM = "_=ID:String,NAME:String\nn.1=a|b\n";
    static final String TWO_GEOMS = "_=a:Point:srid=4326,b:Point:srid=3857,ID:String\nt.1=POINT (1 1)|POINT (100 100)|x\n";
    static final String NO_SRID = "_=geom:Point,ID:String\ns.1=POINT (1 1)|x\n";

    @Override
    protected void onSetUp(SystemTestData testData) throws Exception {
        super.onSetUp(testData);
        store("bulk", Map.of("BExtra", POLY, "BNoGeom", NO_GEOM, "BTwoGeoms", TWO_GEOMS, "BNoSrid", NO_SRID));
        store("named", Map.of("NExtra", POLY, "NNoGeom", NO_GEOM));
        store("avail", Map.of("AExtra", POLY, "ATwoGeoms", TWO_GEOMS, "ANoGeom", NO_GEOM));
        CatalogBuilder b = new CatalogBuilder(getCatalog());
        b.setWorkspace(getCatalog().getWorkspaceByName("cite"));
        DataStoreInfo pg = b.buildDataStore("pgdown");
        pg.setType("PostGIS");
        pg.getConnectionParameters().putAll(Map.of("dbtype", "postgis", "host", "nowhere.invalid", "database", "db", "user", "bob", "passwd", "x"));
        getCatalog().add(pg);
    }

    void store(String name, Map<String, String> files) throws Exception {
        File dir = new File(getTestData().getDataDirectoryRoot(), "data/" + name);
        dir.mkdirs();
        for (var e : files.entrySet()) {
            Files.writeString(new File(dir, e.getKey() + ".properties").toPath(), e.getValue(), StandardCharsets.UTF_8);
        }
        run(tool(new StoreTools(getCatalog()).tools(), "gs_create_store"), Map.of(
                "workspace", "cite", "name", name, "type", "Properties", "params", Map.of("directory", dir.getAbsolutePath())));
    }

    Map<String, JsonNode> items(JsonNode r) {
        Map<String, JsonNode> out = new LinkedHashMap<>();
        r.get("items").forEach(i -> out.put(i.get("name").asText(), i));
        return out;
    }

    List<String> texts(JsonNode array) {
        List<String> out = new ArrayList<>();
        if (array != null) array.forEach(n -> out.add(n.asText()));
        return out;
    }

    @Test
    public void publishDefaultsNativeNameWhenStoreHasOneFeatureType() throws Exception {
        store("single", Map.of("SOnly", POLY));
        JsonNode r = run(tool(new LayerTools(getCatalog()).tools(), "gs_publish_layer"), Map.of("workspace", "cite", "store", "single"));
        assertEquals("SOnly", r.get("nativeName").asText());
        assertNotNull(getCatalog().getLayerByName("cite:SOnly"));
    }

    @Test
    public void publishWithoutNativeNameListsCandidatesWhenSeveral() throws Exception {
        ToolException e = assertThrows(ToolException.class, () -> runRaw(tool(new LayerTools(getCatalog()).tools(), "gs_publish_layer"), ctx(),
                Map.of("workspace", "cite", "store", "avail")));
        assertTrue(e.getMessage(), e.getMessage().contains("3 feature types: AExtra, ANoGeom, ATwoGeoms"));
    }

    @Test
    public void getStoreListsAvailableNamesOnly() throws Exception {
        run(tool(new LayerTools(getCatalog()).tools(), "gs_publish_layer"), Map.of("workspace", "cite", "store", "avail", "nativeName", "AExtra"));
        JsonNode r = run(tool(new StoreTools(getCatalog()).tools(), "gs_get_store"), Map.of("workspace", "cite", "name", "avail"));
        assertEquals(List.of("AExtra"), texts(r.get("resources")));
        assertEquals(List.of("ANoGeom", "ATwoGeoms"), texts(r.get("available")));
        assertNull(r.get("tables"));
    }

    @Test
    public void getStoreDetailDescribesGeometryColumns() throws Exception {
        JsonNode r = run(tool(new StoreTools(getCatalog()).tools(), "gs_get_store"), Map.of(
                "workspace", "cite", "name", "avail", "names", List.of("ATwoGeoms", "ANoGeom")));
        JsonNode tables = r.get("tables");
        assertEquals(2, tables.size());
        JsonNode two = tables.get(0);
        assertEquals("ATwoGeoms", two.get("name").asText());
        assertFalse(two.get("published").asBoolean());
        assertEquals(2, two.get("geometries").size());
        assertEquals("a", two.get("geometries").get(0).get("column").asText());
        assertEquals("Point", two.get("geometries").get(0).get("type").asText());
        assertEquals(4326, two.get("geometries").get(0).get("srid").asInt());
        assertEquals(3857, two.get("geometries").get(1).get("srid").asInt());
        assertEquals(0, tables.get(1).get("geometries").size());
        JsonNode all = run(tool(new StoreTools(getCatalog()).tools(), "gs_get_store"), Map.of("workspace", "cite", "name", "avail", "detail", true));
        assertEquals(3, all.get("tables").size());
    }

    @Test
    public void getStoreReportsUnreachableStoreWithoutFailing() throws Exception {
        JsonNode r = run(tool(new StoreTools(getCatalog()).tools(), "gs_get_store"), Map.of("workspace", "cite", "name", "pgdown"));
        assertNull(r.get("available"));
        assertTrue(!r.get("availableError").asText().isEmpty());
    }

    @Test
    public void publishLayersAllSkipsGeometrylessAndWarns() throws Exception {
        var t = tool(new LayerTools(getCatalog()).tools(), "gs_publish_layers");
        JsonNode r = run(t, Map.of("workspace", "cite", "store", "bulk", "all", true));
        assertEquals(r.toString(), 3, r.get("published").asInt());
        assertEquals(1, r.get("skipped").asInt());
        assertEquals(0, r.get("failed").asInt());
        Map<String, JsonNode> items = items(r);
        JsonNode extra = items.get("cite:BExtra");
        assertTrue(extra.get("created").asBoolean());
        assertEquals("EPSG:4326", extra.get("srs").asText());
        assertEquals(-1.0, extra.get("nativeBoundingBox").get("minx").asDouble(), 1e-9);
        assertTrue(extra.get("ms").asLong() >= 0);
        assertNull(extra.get("attributes"));
        assertTrue(items.get("BNoGeom").get("skipped").asBoolean());
        assertTrue(texts(items.get("BNoGeom").get("warnings")).get(0).contains("geometry"));
        String two = String.join(" ", texts(items.get("cite:BTwoGeoms").get("warnings")));
        assertTrue(two, two.contains("a") && two.contains("b") && two.contains("geometry columns"));
        JsonNode noSrid = items.get("cite:BNoSrid");
        assertTrue(noSrid.get("created").asBoolean());
        assertTrue(noSrid.get("srs").isNull() || "EPSG:404000".equals(noSrid.get("srs").asText()));
        assertTrue(String.join(" ", texts(noSrid.get("warnings"))).contains("SRS"));
        JsonNode again = run(t, Map.of("workspace", "cite", "store", "bulk", "all", true));
        assertEquals(0, again.get("published").asInt());
        assertEquals(1, again.get("skipped").asInt());
    }

    @Test
    public void publishLayersIsolatesErrorsAndIncludesNonSpatialOnRequest() throws Exception {
        var t = tool(new LayerTools(getCatalog()).tools(), "gs_publish_layers");
        JsonNode r = run(t, Map.of("workspace", "cite", "store", "named", "names", List.of("Nope", "NExtra", "NNoGeom"), "includeNonSpatial", true));
        assertEquals(2, r.get("published").asInt());
        assertEquals(1, r.get("failed").asInt());
        Map<String, JsonNode> items = items(r);
        assertTrue(items.get("Nope").get("error").asText(), items.get("Nope").get("error").asText().contains("Nope"));
        assertTrue(items.get("cite:NExtra").get("created").asBoolean());
        assertTrue(items.get("cite:NNoGeom").get("created").asBoolean());
        assertTrue(String.join(" ", texts(items.get("cite:NNoGeom").get("warnings"))).contains("geometry"));
        assertNull(getCatalog().getFeatureTypeByDataStore(getCatalog().getDataStoreByName("cite", "named"), "Nope"));
    }

    @Test
    public void publishLayersCapsAtFifty() {
        var t = tool(new LayerTools(getCatalog()).tools(), "gs_publish_layers");
        List<String> names = new ArrayList<>();
        for (int i = 0; i < 51; i++) names.add("t" + i);
        Map<String, Object> args = Map.of("workspace", "cite", "store", "named", "names", names);
        ToolException e = assertThrows(ToolException.class, () -> run(t, args));
        assertTrue(e.getMessage(), e.getMessage().contains("50"));
        Map<String, Object> args1 = Map.of("workspace", "cite", "store", "named");
        assertThrows(ToolException.class, () -> run(t, args1));
    }

    @Test
    public void publishLayerReusesOrphanResourceInsteadOfDuplicating() throws Exception {
        var t = tool(new LayerTools(getCatalog()).tools(), "gs_publish_layer");
        JsonNode first = run(t, Map.of("workspace", "cite", "store", "cite", "nativeName", "BasicPolygons", "name", "Renamed"));
        assertTrue(first.get("created").asBoolean());
        assertNull(first.get("attributes"));
        LayerInfo layer = getCatalog().getLayerByName("cite:Renamed");
        getCatalog().remove(layer);
        DataStoreInfo store = getCatalog().getDataStoreByName("cite", "cite");
        int resources = getCatalog().getResourcesByStore(store, ResourceInfo.class).size();
        JsonNode second = run(t, Map.of("workspace", "cite", "store", "cite", "nativeName", "BasicPolygons", "name", "Renamed"));
        assertTrue(second.get("created").asBoolean());
        assertEquals(resources, getCatalog().getResourcesByStore(store, ResourceInfo.class).size());
        assertTrue(String.join(" ", texts(second.get("warnings"))).contains("Renamed"));
    }

    @Test
    public void createSqlViewRejectsUnknownGeometryTypeAndNonJdbcStore() {
        var t = tool(new LayerTools(getCatalog()).tools(), "gs_create_sql_view");
        Map<String, Object> base = Map.of("workspace", "cite", "store", "named", "name", "v", "sql", "select 1", "geometry", "g", "srid", 4326);
        Map<String, Object> badType = new LinkedHashMap<>(base);
        badType.put("geometryType", "Blob");
        assertTrue(assertThrows(ToolException.class, () -> run(t, badType)).getMessage().contains("geometryType"));
        Map<String, Object> notJdbc = new LinkedHashMap<>(base);
        notJdbc.put("geometryType", "Point");
        assertTrue(assertThrows(ToolException.class, () -> run(t, notJdbc)).getMessage().contains("not a JDBC store"));
    }

    JsonNode createWithPublish(String name, Map<String, String> files, Object publish) throws Exception {
        File dir = new File(getTestData().getDataDirectoryRoot(), "data/" + name);
        dir.mkdirs();
        for (var e : files.entrySet()) {
            Files.writeString(new File(dir, e.getKey() + ".properties").toPath(), e.getValue(), StandardCharsets.UTF_8);
        }
        return run(tool(new StoreTools(getCatalog()).tools(), "gs_create_store"), Map.of(
                "workspace", "cite", "name", name, "type", "Properties", "params", Map.of("directory", dir.getAbsolutePath()), "publish", publish));
    }

    @Test
    public void createStoreWithPublishTruePublishesTheOnlyLayer() throws Exception {
        JsonNode r = createWithPublish("pubone", Map.of("POne", POLY), true);
        assertTrue(r.get("layers").get(0).get("created").asBoolean());
        assertNotNull(getCatalog().getLayerByName("cite:POne"));
    }

    @Test
    public void createStoreWithPublishTrueSkipsMultiTableStores() throws Exception {
        JsonNode r = createWithPublish("pubmany", Map.of("PmA", POLY, "PmB", POLY), true);
        assertTrue(r.get("created").asBoolean());
        assertTrue(r.get("layers").get("skipped").asText().startsWith("2 unpublished tables"));
        assertNull(getCatalog().getLayerByName("cite:PmA"));
    }

    @Test
    public void createStoreWithPublishListPublishesThoseTables() throws Exception {
        JsonNode r = createWithPublish("publist", Map.of("PlA", POLY, "PlB", POLY, "PlC", POLY), List.of("PlA", "PlB"));
        assertEquals(2, r.get("layers").size());
        assertNotNull(getCatalog().getLayerByName("cite:PlA"));
        assertNotNull(getCatalog().getLayerByName("cite:PlB"));
        assertNull(getCatalog().getLayerByName("cite:PlC"));
    }

    @Test
    public void createStoreRefusesMoreThanTheBulkCap() throws Exception {
        List<String> names = new ArrayList<>();
        for (int i = 0; i <= LayerTools.MAX_BULK; i++) names.add("t" + i);
        JsonNode r = createWithPublish("pubcap", Map.of("PcA", POLY), names);
        assertTrue(r.get("layers").get("skipped").asText().contains("at most " + LayerTools.MAX_BULK));
    }

    @Test
    public void createStoreWithPublishFalsePublishesNothing() throws Exception {
        JsonNode r = createWithPublish("pubnone", Map.of("PnA", POLY), false);
        assertNull(r.get("layers"));
        assertNull(getCatalog().getLayerByName("cite:PnA"));
    }

    @Test
    public void publishLayerHintsAtOtherUnpublishedTables() throws Exception {
        store("hinted", Map.of("HA", POLY, "HB", POLY));
        JsonNode r = run(tool(new LayerTools(getCatalog()).tools(), "gs_publish_layer"),
                Map.of("workspace", "cite", "store", "hinted", "nativeName", "HA"));
        assertTrue(r.get("hint").asText().startsWith("1 other tables"));
        JsonNode last = run(tool(new LayerTools(getCatalog()).tools(), "gs_publish_layer"),
                Map.of("workspace", "cite", "store", "hinted", "nativeName", "HB"));
        assertNull(last.get("hint"));
    }

    @Test
    public void geoPackageDefaultsDbtypeAndResolvesDataDirectoryPaths() throws Exception {
        new File(getTestData().getDataDirectoryRoot(), "data/gp").mkdirs();
        JsonNode r = run(tool(new StoreTools(getCatalog()).tools(), "gs_create_store"), Map.of(
                "workspace", "cite", "name", "gp", "type", "GeoPackage", "params", Map.of("database", "file:data/gp/x.gpkg")));
        assertTrue(r.get("created").asBoolean());
        DataStoreInfo ds = getCatalog().getDataStoreByName("cite", "gp");
        assertEquals("geopkg", String.valueOf(ds.getConnectionParameters().get("dbtype")));
        assertEquals("file:data/gp/x.gpkg", String.valueOf(ds.getConnectionParameters().get("database")));
    }

    @Test
    public void publishCarriesARenderCheckOnlyForNewLayers() throws Exception {
        store("rendered", Map.of("RA", POLY, "RB", POLY));
        LayerTools layers = new LayerTools(getCatalog(), (c, l) -> Map.of("ok", true, "layer", l.prefixedName()));
        Map<String, Object> one = Map.of("workspace", "cite", "store", "rendered", "nativeName", "RA");
        JsonNode first = run(tool(layers.tools(), "gs_publish_layer"), one);
        assertEquals("cite:RA", first.get("render").get("layer").asText());
        assertNull(run(tool(layers.tools(), "gs_publish_layer"), one).get("render"));
        JsonNode bulk = run(tool(layers.tools(), "gs_publish_layers"), Map.of("workspace", "cite", "store", "rendered", "names", List.of("RB")));
        assertTrue(items(bulk).get("cite:RB").get("render").get("ok").asBoolean());
    }
}
