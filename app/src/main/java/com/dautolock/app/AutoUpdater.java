package com.dautolock.app;

import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import com.dautolock.app.core.ReleaseUpdate;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import okhttp3.*;
import org.json.*;

final class AutoUpdater {
  private final Context context;
  private final SharedPreferences settings;
  private final Runnable changed;
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private final AtomicBoolean busy = new AtomicBoolean();
  private final Handler main = new Handler(Looper.getMainLooper());
  private volatile boolean foreground;
  volatile String status = "앱 실행 시 새 버전을 확인합니다";
  volatile boolean installReady;
  /** Outcome messages for a check the user asked for from the menu (activity log). */
  volatile java.util.function.Consumer<String> notice = message -> {};
  /** Open the system installer as soon as a verified download is ready. */
  private volatile boolean installRequested, userRequested;
  private volatile String promptedVersion = "";
  static final long RECHECK_MS = 5 * 60 * 1000;
  private final Runnable poll = () -> resumeDownload();

  AutoUpdater(Context context, SharedPreferences settings, Runnable changed) {
    this.context = context;
    this.settings = settings;
    this.changed = changed;
  }

  void foreground(boolean value) {
    foreground = value;
    main.removeCallbacks(poll);
    if (!value) return;
    boolean automatic = settings.getBoolean("autoUpdate", true);
    if (automatic) installRequested = true;
    if (settings.getLong("updateDownload", 0) > 0) resumeDownload();
    else if (automatic) check(false);
  }

  /** Menu "앱 업데이트": check now, download, and open the installer without another tap. */
  void request() {
    userRequested = true;
    installRequested = true;
    promptedVersion = "";
    if (installReady) changed.run();
    else check(true);
  }

  /**
   * True once per version per app run (or on every explicit request). The Android installer
   * screen is still the user's confirmation; cancelling it does not reopen it on its own.
   */
  boolean takeInstallRequest() {
    if (!installRequested || !installReady) return false;
    String version = settings.getString("updateVersion", "");
    installRequested = false;
    if (!userRequested && version.equals(promptedVersion)) return false;
    userRequested = false;
    promptedVersion = version;
    return true;
  }

  private void report(String message) {
    status = message;
    if (userRequested) {
      userRequested = false;
      notice.accept("앱 업데이트 · " + message);
    }
  }

  private String installedVersion() throws Exception {
    return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
  }

  void check(boolean force) {
    if (settings.getLong("updateDownload", 0) > 0) {
      resumeDownload();
      return;
    }
    long now = System.currentTimeMillis();
    long age = now - settings.getLong("updateChecked", 0);
    if (!force && age >= 0 && age < RECHECK_MS) {
      changed.run();
      return;
    }
    if (!busy.compareAndSet(false, true)) return;
    status = "GitHub 새 버전 확인 중…";
    changed.run();
    worker.execute(
        () -> {
          try {
            OkHttpClient client =
                new OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build();
            Request request =
                new Request.Builder()
                    .url("https://api.github.com/repos/KyuYeubKim/D-Autolock/releases?per_page=20")
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "D-Autolock-Updater")
                    .build();
            try (Response response = client.newCall(request).execute()) {
              if (!response.isSuccessful() || response.body() == null)
                throw new Exception("업데이트 서버 연결 실패");
              ReleaseUpdate release =
                  ReleaseUpdate.newest(new JSONArray(response.body().string()), installedVersion());
              settings.edit().putLong("updateChecked", now).apply();
              if (release == null) report("최신 버전입니다 · " + installedVersion());
              else download(release);
            }
          } catch (Exception e) {
            report("업데이트 확인 보류 · " + e.getMessage());
          } finally {
            busy.set(false);
            changed.run();
            schedulePoll();
          }
        });
  }

  private void download(ReleaseUpdate release) throws Exception {
    File directory = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
    if (directory == null) throw new Exception("다운로드 저장소를 사용할 수 없습니다");
    File file = new File(directory, "update-" + release.version + ".apk");
    if (file.exists() && !file.delete()) throw new Exception("이전 다운로드 파일을 정리하지 못했습니다");
    DownloadManager.Request request =
        new DownloadManager.Request(Uri.parse(release.url))
            .setTitle("D-Autolock " + release.version + " 업데이트")
            .setDescription("다운로드 후 앱에서 설치를 확인하세요")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationUri(Uri.fromFile(file));
    long id = context.getSystemService(DownloadManager.class).enqueue(request);
    settings
        .edit()
        .putLong("updateDownload", id)
        .putString("updateVersion", release.version)
        .putString("updateDigest", release.digest)
        .putLong("updateSize", release.size)
        .apply();
    status = "새 버전 " + release.version + " 다운로드 중… · 완료되면 설치 화면을 엽니다";
    if (userRequested) notice.accept("앱 업데이트 · " + status);
  }

  private void schedulePoll() {
    main.post(
        () -> {
          main.removeCallbacks(poll);
          if (foreground && settings.getLong("updateDownload", 0) > 0 && !installReady)
            main.postDelayed(poll, 2000);
        });
  }

  private void resumeDownload() {
    if (!busy.compareAndSet(false, true)) {
      schedulePoll();
      return;
    }
    worker.execute(
        () -> {
          try {
            String version = settings.getString("updateVersion", "");
            if (ReleaseUpdate.order(version) <= ReleaseUpdate.order(installedVersion())) {
              discard();
              report("최신 버전입니다 · " + installedVersion());
              return;
            }
            long id = settings.getLong("updateDownload", 0);
            DownloadManager downloads = context.getSystemService(DownloadManager.class);
            try (Cursor result = downloads.query(new DownloadManager.Query().setFilterById(id))) {
              if (result == null || !result.moveToFirst())
                throw new Exception("다운로드 기록 없음 · 다시 확인하세요");
              int state =
                  result.getInt(result.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
              if (state == DownloadManager.STATUS_FAILED) throw new Exception("다운로드 실패 · 다시 확인하세요");
              if (state == DownloadManager.STATUS_SUCCESSFUL) {
                verifyDownloaded();
                boolean announce = !installReady;
                installReady = true;
                status = version + " 다운로드·서명 검증 완료 · 설치 화면을 엽니다";
                if (announce && !foreground)
                  DoorNotifications.result(
                      context, 4, "D-Autolock 업데이트 준비됨", "앱을 열면 설치 화면이 표시됩니다");
              } else
                status =
                    "새 버전 "
                        + version
                        + " 다운로드 "
                        + (state == DownloadManager.STATUS_PAUSED ? "대기 중" : "중…");
            }
          } catch (Exception e) {
            discard();
            report("업데이트 보류 · " + e.getMessage());
          } finally {
            busy.set(false);
            changed.run();
            schedulePoll();
          }
        });
  }

  private void discard() {
    long id = settings.getLong("updateDownload", 0);
    if (id > 0) context.getSystemService(DownloadManager.class).remove(id);
    settings
        .edit()
        .remove("updateDownload")
        .remove("updateVersion")
        .remove("updateDigest")
        .remove("updateSize")
        .apply();
    installReady = false;
  }

  private void verifyDownloaded() throws Exception {
    String version = settings.getString("updateVersion", "");
    if (ReleaseUpdate.order(version) < 0) throw new Exception("잘못된 업데이트 버전");
    File directory = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
    if (directory == null) throw new Exception("다운로드 저장소를 사용할 수 없습니다");
    File file = new File(directory, "update-" + version + ".apk");
    if (file.length() != settings.getLong("updateSize", -1)) throw new Exception("업데이트 파일 크기 불일치");
    MessageDigest sha = MessageDigest.getInstance("SHA-256");
    try (InputStream in = new FileInputStream(file)) {
      byte[] buffer = new byte[8192];
      int count;
      while ((count = in.read(buffer)) > 0) sha.update(buffer, 0, count);
    }
    StringBuilder digest = new StringBuilder();
    for (byte b : sha.digest()) digest.append(String.format(Locale.ROOT, "%02x", b & 255));
    if (!digest.toString().equals(settings.getString("updateDigest", "")))
      throw new Exception("업데이트 파일 검증 실패");
    PackageManager manager = context.getPackageManager();
    int flags =
        Build.VERSION.SDK_INT >= 28
            ? PackageManager.GET_SIGNING_CERTIFICATES
            : PackageManager.GET_SIGNATURES;
    PackageInfo current = manager.getPackageInfo(context.getPackageName(), flags);
    PackageInfo candidate = manager.getPackageArchiveInfo(file.getAbsolutePath(), flags);
    if (!compatible(current, candidate, version)) throw new Exception("앱 패키지·버전·서명이 일치하지 않습니다");
  }

  static boolean compatible(PackageInfo current, PackageInfo candidate, String version) {
    if (candidate == null
        || !current.packageName.equals(candidate.packageName)
        || !version.equals(candidate.versionName)) return false;
    long currentCode =
        Build.VERSION.SDK_INT >= 28 ? current.getLongVersionCode() : current.versionCode;
    long nextCode =
        Build.VERSION.SDK_INT >= 28 ? candidate.getLongVersionCode() : candidate.versionCode;
    if (nextCode <= currentCode) return false;
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

  void install(
      Activity activity, java.util.function.BooleanSupplier ready, Runnable beforeInstall) {
    if (!installReady || !ready.getAsBoolean()) {
      installRequested = true; // Retry when the running vehicle request finishes.
      status = "차량 요청 완료 후 설치 화면을 엽니다";
      changed.run();
      return;
    }
    if (!activity.getPackageManager().canRequestPackageInstalls()) {
      activity.startActivity(
          new Intent(
              android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
              Uri.parse("package:" + context.getPackageName())));
      // Returning from the permission screen should open the installer without another tap.
      installRequested = true;
      promptedVersion = "";
      notice.accept("앱 업데이트 · 이 앱의 설치를 허용하면 설치 화면을 엽니다");
      status = "이 앱의 설치 허용이 필요합니다";
      changed.run();
      return;
    }
    if (!busy.compareAndSet(false, true)) return;
    worker.execute(
        () -> {
          try {
            verifyDownloaded();
            Uri uri =
                context
                    .getSystemService(DownloadManager.class)
                    .getUriForDownloadedFile(settings.getLong("updateDownload", 0));
            if (uri == null) throw new Exception("다운로드 파일을 열 수 없습니다");
            main.post(
                () -> {
                  if (activity.isFinishing() || activity.isDestroyed()) return;
                  if (!ready.getAsBoolean()) {
                    installRequested = true;
                    status = "차량 요청 완료 후 설치 화면을 엽니다";
                    changed.run();
                    return;
                  }
                  try {
                    beforeInstall.run();
                    activity.startActivity(
                        new Intent(Intent.ACTION_VIEW)
                            .setDataAndType(uri, "application/vnd.android.package-archive")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
                  } catch (Exception e) {
                    status = "설치 화면을 열지 못했습니다";
                    notice.accept("앱 업데이트 · " + status);
                  }
                });
          } catch (Exception e) {
            installReady = false;
            status = "설치 보류 · " + e.getMessage();
            notice.accept("앱 업데이트 · " + status);
          } finally {
            busy.set(false);
            changed.run();
          }
        });
  }
}
