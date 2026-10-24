package dev.lagless;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdaterTest {

    private static final String RELEASE = """
            {
              "tag_name": "v0.2.0",
              "name": "v0.2.0",
              "draft": false,
              "prerelease": false,
              "body": "notes with a quote: it said hello",
              "assets": [
                {
                  "name": "lagless.jar",
                  "size": 2325667,
                  "url": "https://api.github.com/repos/o/r/releases/assets/1",
                  "browser_download_url": "https://github.com/o/r/releases/download/v0.2.0/lagless.jar"
                },
                {
                  "name": "lagless-0.2.0-windows-x64.zip",
                  "size": 41000000,
                  "url": "https://api.github.com/repos/o/r/releases/assets/2",
                  "browser_download_url": "https://github.com/o/r/releases/download/v0.2.0/lagless.zip"
                }
              ]
            }
            """;

    @Test
    void readsTagAndAssetsFromAReleaseDocument() {
        Object root = Json.parse(RELEASE);
        assertEquals("v0.2.0", Json.string(root, "tag_name"));
        assertEquals(Boolean.FALSE, Json.get(root, "draft"));
        List<Object> assets = Json.list(root, "assets");
        assertEquals(2, assets.size());
        assertEquals("lagless.jar", Json.string(root, "assets", 0, "name"));
        assertEquals(2325667L, Json.number(root, -1, "assets", 0, "size"));
        assertEquals("https://api.github.com/repos/o/r/releases/assets/1",
                Json.string(root, "assets", 0, "url"));
        assertTrue(Json.string(root, "assets", 1, "name").endsWith(".zip"));
    }

    @Test
    void missingPathsReturnNullInsteadOfThrowing() {
        Object root = Json.parse(RELEASE);
        assertNull(Json.get(root, "nope"));
        assertNull(Json.get(root, "assets", 9, "name"));
        assertNull(Json.get(root, "tag_name", "deeper"));
        assertEquals(List.of(), Json.list(root, "nope"));
        assertEquals(7L, Json.number(root, 7, "nope"));
    }

    @Test
    void parsesTheJsonValueTypes() {
        assertEquals(Map.of(), Json.parse("{}"));
        assertEquals(List.of(), Json.parse("[]"));
        assertEquals("hi", Json.parse(Q + "hi" + Q));
        assertEquals(1.5, Json.parse("1.5"));
        assertEquals(-20.0, Json.parse("-2e1"));
        assertEquals(Boolean.TRUE, Json.parse("true"));
        assertNull(Json.parse("null"));
        assertEquals(List.of(1.0, 2.0), Json.parse("[1, 2]"));
    }

    @Test
    void escapesInsideStringsAreDecoded() {
        String json = "{" + q("a") + ": " + q("x" + BS + "ny") + "}";
        Object root = Json.parse(json);
        assertEquals("x" + (char) 10 + "y", Json.string(root, "a"));

        String unicode = "{" + q("a") + ": " + q(BS + "u0041") + "}";
        assertEquals("A", Json.string(Json.parse(unicode), "a"));
    }

    @Test
    void rejectsMalformedInput() {
        assertThrows(IllegalArgumentException.class, () -> Json.parse("{"));
        assertThrows(IllegalArgumentException.class, () -> Json.parse("{} extra"));
        assertThrows(IllegalArgumentException.class, () -> Json.parse("[1 2]"));
    }

    @Test
    void comparesVersions() {
        assertTrue(Updater.compareVersions("0.1.1", "v0.1.2") < 0);
        assertTrue(Updater.compareVersions("v0.1.2", "0.1.1") > 0);
        assertEquals(0, Updater.compareVersions("0.1.1", "v0.1.1"));
        assertTrue(Updater.compareVersions("0.9.0", "0.10.0") < 0);
        assertTrue(Updater.compareVersions("1.0", "1.0.0") == 0);
        assertTrue(Updater.compareVersions("0.1.1-SNAPSHOT", "0.1.1") < 0);
        assertTrue(Updater.compareVersions("dev", "0.0.1") < 0);
    }

    @Test void prereleaseVersionsAndChannelsAreOrderedCorrectly(){
        assertTrue(Updater.compareVersions("0.2.0-rc.1","v0.2.0-rc.2")<0);
        assertTrue(Updater.compareVersions("0.2.0-rc.2","0.2.0-rc.10")<0);
        assertTrue(Updater.compareVersions("0.2.0-rc.10","0.2.0")<0);
        assertEquals(0,Updater.compareVersions("0.2.0+build.9","v0.2.0"));
        Object releases=Json.parse("""
            [
              {"tag_name":"v0.2.0-rc.1","prerelease":true,"draft":false},
              {"tag_name":"v0.2.0-rc.10","prerelease":true,"draft":false},
              {"tag_name":"v0.3.0-rc.1","prerelease":true,"draft":true},
              {"tag_name":"v0.1.15","prerelease":false,"draft":false}
            ]
            """);
        assertEquals("v0.2.0-rc.10",Updater.selectLatestPublished(releases,true).tag());
        assertEquals("v0.1.15",Updater.selectLatestPublished(releases,false).tag());
        assertTrue(Updater.usesPrereleases("v0.2.0-rc.2"));
        assertTrue(!Updater.usesPrereleases("0.2.0"));
    }

    private static final String BS = String.valueOf((char) 92);
    private static final String Q = String.valueOf((char) 34);

    private static String q(String s) {
        return Q + s + Q;
    }
}
