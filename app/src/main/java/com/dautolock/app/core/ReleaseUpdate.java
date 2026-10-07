package com.dautolock.app.core;

import java.util.regex.Pattern;
import org.json.*;

/** Only this repository's numbered, published APK releases are eligible. */
public final class ReleaseUpdate {
  public final String version, url, digest;
  public final long size;
  private static final Pattern VERSION = Pattern.compile("[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}");

  private ReleaseUpdate(String version, String url, String digest, long size) {
    this.version = version;
    this.url = url;
    this.digest = digest;
    this.size = size;
  }

  public static int order(String version) {
    if (version == null || !VERSION.matcher(version).matches()) return -1;
    String[] parts = version.split("\\.");
    return Integer.parseInt(parts[0]) * 1000000
        + Integer.parseInt(parts[1]) * 1000
        + Integer.parseInt(parts[2]);
  }

  public static ReleaseUpdate newest(JSONArray releases, String installed) throws Exception {
    int best = order(installed);
    if (best < 0) throw new Exception("현재 앱 버전을 확인하지 못했습니다");
    ReleaseUpdate selected = null;
    for (int i = 0; i < releases.length(); i++) {
      JSONObject release = releases.getJSONObject(i);
      if (release.optBoolean("draft", true)) continue;
      String tag = release.optString("tag_name");
      if (!tag.startsWith("v")) continue;
      String version = tag.substring(1);
      if (order(version) <= best) continue;
      JSONArray assets = release.optJSONArray("assets");
      if (assets == null) continue;
      String name = "D-Autolock-v" + version + ".apk";
      String expectedUrl =
          "https://github.com/KyuYeubKim/D-Autolock/releases/download/" + tag + "/" + name;
      for (int j = 0; j < assets.length(); j++) {
        JSONObject asset = assets.getJSONObject(j);
        if (!name.equals(asset.optString("name"))
            || !expectedUrl.equals(asset.optString("browser_download_url"))) continue;
        long size = asset.optLong("size", 0);
        String digest = asset.optString("digest");
        if (!digest.matches("sha256:[a-fA-F0-9]{64}") || size <= 0 || size > 50000000)
          throw new Exception("새 버전의 파일 검증 정보가 없어 자동 업데이트를 보류합니다");
        selected =
            new ReleaseUpdate(
                version, expectedUrl, digest.substring(7).toLowerCase(java.util.Locale.ROOT), size);
        best = order(version);
      }
    }
    return selected;
  }
}
