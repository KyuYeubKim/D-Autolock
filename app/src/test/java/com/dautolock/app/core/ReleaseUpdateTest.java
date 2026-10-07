package com.dautolock.app.core;

import static org.junit.Assert.*;

import org.json.*;
import org.junit.Test;

public class ReleaseUpdateTest {
  private JSONObject release(String version) throws Exception {
    String name = "D-Autolock-v" + version + ".apk";
    return new JSONObject()
        .put("tag_name", "v" + version)
        .put("draft", false)
        .put("prerelease", true)
        .put(
            "assets",
            new JSONArray()
                .put(
                    new JSONObject()
                        .put("name", name)
                        .put("size", 2000000)
                        .put(
                            "digest",
                            "sha256:" + String.join("", java.util.Collections.nCopies(64, "a")))
                        .put(
                            "browser_download_url",
                            "https://github.com/KyuYeubKim/D-Autolock/releases/download/v"
                                + version
                                + "/"
                                + name)));
  }

  @Test
  public void comparesNumericallyAndAllowsPublishedPreviewReleases() throws Exception {
    ReleaseUpdate update =
        ReleaseUpdate.newest(
            new JSONArray()
                .put(release("0.2.9"))
                .put(release("0.2.10"))
                .put(release("0.2.11").put("draft", true)),
            "0.2.4");
    assertEquals("0.2.10", update.version);
    assertNull(ReleaseUpdate.newest(new JSONArray().put(release("0.2.4")), "0.2.4"));
  }

  @Test
  public void refusesExternalAssetLocationsAndNonVersionTags() throws Exception {
    JSONObject external = release("0.2.5");
    external
        .getJSONArray("assets")
        .getJSONObject(0)
        .put("browser_download_url", "https://example.com/update.apk");
    assertNull(ReleaseUpdate.newest(new JSONArray().put(external), "0.2.4"));
    assertEquals(-1, ReleaseUpdate.order("../../file"));
    assertEquals(-1, ReleaseUpdate.order("1.2.3-beta"));
  }

  @Test
  public void requiresDigestAndBoundedFileSize() throws Exception {
    JSONObject noDigest = release("0.2.5");
    noDigest.getJSONArray("assets").getJSONObject(0).remove("digest");
    assertThrows(
        Exception.class, () -> ReleaseUpdate.newest(new JSONArray().put(noDigest), "0.2.4"));
    JSONObject large = release("0.2.5");
    large.getJSONArray("assets").getJSONObject(0).put("size", 50000001);
    assertThrows(Exception.class, () -> ReleaseUpdate.newest(new JSONArray().put(large), "0.2.4"));
  }
}
