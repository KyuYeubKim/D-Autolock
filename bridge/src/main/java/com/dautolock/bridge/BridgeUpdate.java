package com.dautolock.bridge;

import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.*;
import javax.net.ssl.HttpsURLConnection;
import org.json.*;

/**
 * Self-updater for the vehicle Bridge app, mirroring the phone AutoUpdater but for the Bridge APK
 * asset (D-Autolock-Bridge-v{version}.apk) and package. No OkHttp here (not a Bridge dependency);
 * uses HttpsURLConnection. Verifies SHA-256 + size + package + higher versionCode + same signature
 * before offering install. The head unit still shows the Android install screen (no silent install).
 */
final class BridgeUpdate {
  static final String REPO = "KyuYeubKim/D-Autolock";
  static final String ASSET_PREFIX = "D-Autolock-Bridge-v", ASSET_SUFFIX = ".apk";
  static final long RECHECK_MS = 6L * 60 * 60 * 1000; // Car data is limited: check at most every 6 h.
  private static final int NOTICE_ID = 21;

  private final Context context;
  private final SharedPreferences prefs;

  BridgeUpdate(Context context) {
    this.context = context.getApplicationContext();
    this.prefs = this.context.getSharedPreferences("bridge_update", 0);
  }

  private String installedVersion() throws Exception {
    return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
  }

  static int order(String v) {
    if (v == null || !v.matches("[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}")) return -1;
    String[] p = v.split("\\.");
    return Integer.parseInt(p[0]) * 1000000 + Integer.parseInt(p[1]) * 1000 + Integer.parseInt(p[2]);
  }

  /** One entry point: finish a pending download (verify/notify) or start a new check. */
  synchronized void tick(boolean force) {
    try {
      if (prefs.getLong("id", 0) > 0) {
        resume();
        return;
      }
      long age = System.currentTimeMillis() - prefs.getLong("checked", 0);
      if (!force && age >= 0 && age < RECHECK_MS) return;
      check();
    } catch (Throwable e) {
      BootLog.add(context, "업데이트 확인 보류: " + e.getClass().getSimpleName());
    }
  }

  private void check() throws Exception {
    JSONArray releases = new JSONArray(get("https://api.github.com/repos/" + REPO + "/releases?per_page=20"));
    prefs.edit().putLong("checked", System.currentTimeMillis()).apply();
    int best = order(installedVersion());
    if (best < 0) return;
    String bestVersion = null, url = null, digest = null;
    long size = 0;
    for (int i = 0; i < releases.length(); i++) {
      JSONObject r = releases.getJSONObject(i);
      if (r.optBoolean("draft", true)) continue;
      String tag = r.optString("tag_name");
      JSONArray assets = r.optJSONArray("assets");
      if (assets == null) continue;
      for (int j = 0; j < assets.length(); j++) {
        JSONObject a = assets.getJSONObject(j);
        String name = a.optString("name");
        if (!name.startsWith(ASSET_PREFIX) || !name.endsWith(ASSET_SUFFIX)) continue;
        String v = name.substring(ASSET_PREFIX.length(), name.length() - ASSET_SUFFIX.length());
        if (order(v) <= best) continue;
        String d = a.optString("digest");
        long s = a.optLong("size", 0);
        if (!d.matches("sha256:[a-fA-F0-9]{64}") || s <= 0 || s > 50000000) continue;
        best = order(v);
        bestVersion = v;
        url = a.optString("browser_download_url");
        digest = d.substring(7).toLowerCase(Locale.ROOT);
        size = s;
      }
    }
    if (bestVersion == null) return;
    download(bestVersion, url, digest, size);
  }

  private void download(String version, String url, String digest, long size) throws Exception {
    File dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
    if (dir == null) throw new Exception("no download dir");
    File file = new File(dir, "bridge-update-" + version + ".apk");
    if (file.exists() && !file.delete()) throw new Exception("stale file");
    long id =
        context
            .getSystemService(DownloadManager.class)
            .enqueue(
                new DownloadManager.Request(Uri.parse(url))
                    .setTitle("D-Autolock Bridge " + version + " 업데이트")
                    .setMimeType("application/vnd.android.package-archive")
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                    .setDestinationUri(Uri.fromFile(file)));
    prefs.edit().putLong("id", id).putString("version", version).putString("digest", digest)
        .putLong("size", size).apply();
    BootLog.add(context, "Bridge " + version + " 다운로드 시작");
  }

  private void resume() throws Exception {
    String version = prefs.getString("version", "");
    if (order(version) <= order(installedVersion())) {
      discard();
      return;
    }
    long id = prefs.getLong("id", 0);
    DownloadManager dm = context.getSystemService(DownloadManager.class);
    try (Cursor c = dm.query(new DownloadManager.Query().setFilterById(id))) {
      if (c == null || !c.moveToFirst()) {
        discard();
        return;
      }
      int state = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
      if (state == DownloadManager.STATUS_FAILED) {
        discard();
        return;
      }
      if (state != DownloadManager.STATUS_SUCCESSFUL) return; // Still downloading.
    }
    if (!verify(version)) {
      discard();
      BootLog.add(context, "Bridge 업데이트 검증 실패 · 폐기");
      return;
    }
    notifyReady(version);
  }

  private boolean verify(String version) {
    try {
      File dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
      File file = new File(dir, "bridge-update-" + version + ".apk");
      if (file.length() != prefs.getLong("size", -1)) return false;
      MessageDigest sha = MessageDigest.getInstance("SHA-256");
      try (InputStream in = new FileInputStream(file)) {
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) > 0) sha.update(b, 0, n);
      }
      StringBuilder hex = new StringBuilder();
      for (byte b : sha.digest()) hex.append(String.format(Locale.ROOT, "%02x", b & 255));
      if (!hex.toString().equals(prefs.getString("digest", ""))) return false;
      PackageManager pm = context.getPackageManager();
      int flags =
          Build.VERSION.SDK_INT >= 28
              ? PackageManager.GET_SIGNING_CERTIFICATES
              : PackageManager.GET_SIGNATURES;
      PackageInfo cur = pm.getPackageInfo(context.getPackageName(), flags);
      PackageInfo cand = pm.getPackageArchiveInfo(file.getAbsolutePath(), flags);
      return compatible(cur, cand, version);
    } catch (Exception e) {
      return false;
    }
  }

  static boolean compatible(PackageInfo current, PackageInfo candidate, String version) {
    if (candidate == null
        || current == null
        || !current.packageName.equals(candidate.packageName)
        || !version.equals(candidate.versionName)) return false;
    long cur = Build.VERSION.SDK_INT >= 28 ? current.getLongVersionCode() : current.versionCode;
    long next = Build.VERSION.SDK_INT >= 28 ? candidate.getLongVersionCode() : candidate.versionCode;
    if (next <= cur) return false;
    Signature[] before =
        Build.VERSION.SDK_INT >= 28 && current.signingInfo != null
            ? current.signingInfo.getApkContentsSigners()
            : current.signatures;
    Signature[] after =
        Build.VERSION.SDK_INT >= 28 && candidate.signingInfo != null
            ? candidate.signingInfo.getApkContentsSigners()
            : candidate.signatures;
    return before != null
        && after != null
        && before.length > 0
        && before.length == after.length
        && new HashSet<>(Arrays.asList(before)).equals(new HashSet<>(Arrays.asList(after)));
  }

  /** Verified and ready: tell the user (the install screen needs the foreground Activity). */
  private void notifyReady(String version) {
    BootLog.add(context, "Bridge " + version + " 업데이트 준비됨");
    if (!DoorNotificationsEnabled()) return;
    NotificationManager nm = context.getSystemService(NotificationManager.class);
    nm.createNotificationChannel(
        new NotificationChannel("bridge_update", "Bridge 업데이트", NotificationManager.IMPORTANCE_DEFAULT));
    PendingIntent open =
        PendingIntent.getActivity(
            context,
            0,
            new Intent(context, BridgeActivity.class).putExtra("install_update", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    try {
      nm.notify(
          NOTICE_ID,
          new Notification.Builder(context, "bridge_update")
              .setSmallIcon(R.drawable.ic_bridge)
              .setContentTitle("Bridge 업데이트 준비됨 · " + version)
              .setContentText("눌러서 설치하세요")
              .setContentIntent(open)
              .setAutoCancel(true)
              .build());
    } catch (SecurityException ignored) {
    }
  }

  private boolean DoorNotificationsEnabled() {
    return Build.VERSION.SDK_INT < 33
        || context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED
        || context.getSystemService(NotificationManager.class).areNotificationsEnabled();
  }

  /** From the Activity: if a verified update is ready, open the system installer. */
  void installIfReady(Activity activity) {
    String version = prefs.getString("version", "");
    if (prefs.getLong("id", 0) <= 0 || version.isEmpty()) return;
    try {
      if (order(version) <= order(installedVersion()) || !verify(version)) return;
    } catch (Exception e) {
      return;
    }
    if (!activity.getPackageManager().canRequestPackageInstalls()) {
      try {
        activity.startActivity(
            new Intent(
                android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + context.getPackageName())));
      } catch (Exception ignored) {
      }
      return;
    }
    Uri uri = context.getSystemService(DownloadManager.class).getUriForDownloadedFile(prefs.getLong("id", 0));
    if (uri == null) return;
    try {
      activity.startActivity(
          new Intent(Intent.ACTION_VIEW)
              .setDataAndType(uri, "application/vnd.android.package-archive")
              .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
    } catch (Exception ignored) {
    }
  }

  private void discard() {
    long id = prefs.getLong("id", 0);
    if (id > 0) context.getSystemService(DownloadManager.class).remove(id);
    prefs.edit().remove("id").remove("version").remove("digest").remove("size").apply();
  }

  private String get(String url) throws Exception {
    HttpsURLConnection conn = (HttpsURLConnection) new URL(url).openConnection();
    conn.setConnectTimeout(15000);
    conn.setReadTimeout(20000);
    conn.setRequestProperty("Accept", "application/vnd.github+json");
    conn.setRequestProperty("User-Agent", "D-Autolock-Bridge-Updater");
    try {
      int code = conn.getResponseCode();
      if (code != HttpURLConnection.HTTP_OK) throw new Exception("HTTP " + code);
      try (BufferedReader r =
          new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) sb.append(line);
        return sb.toString();
      }
    } finally {
      conn.disconnect();
    }
  }
}
