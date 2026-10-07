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
    if (settings.getLong("updateDownload", 0) > 0) resumeDownload();
    else if (settings.getBoolean("autoUpdate", true)) check(false);
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
    if (!force && age >= 0 && age < 86400000) {
      status = "오늘 업데이트 확인 완료 · 필요하면 다시 확인하세요";
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
              if (release == null) status = "최신 버전입니다 · " + installedVersion();
              else download(release);
            }
          } catch (Exception e) {
            status = "업데이트 확인 보류 · " + e.getMessage();
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
    status = "새 버전 " + release.version + " 자동 다운로드 중…";
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
              status = "최신 버전입니다 · " + installedVersion();
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
                status = version + " 다운로드·서명 검증 완료 · 업데이트 설치를 누르세요";
                if (announce)
                  DoorNotifications.result(
                      context, 4, "D-Autolock 업데이트 준비됨", "앱의 업데이트 설치 버튼을 눌러 설치를 확인하세요");
              } else
                status =
                    "새 버전 "
                        + version
                        + " 다운로드 "
                        + (state == DownloadManager.STATUS_PAUSED ? "대기 중" : "중…");
            }
          } catch (Exception e) {
            discard();
            status = "업데이트 보류 · " + e.getMessage();
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
      status = "차량 제어 완료 및 업데이트 다운로드를 기다리세요";
      changed.run();
      return;
    }
    if (!activity.getPackageManager().canRequestPackageInstalls()) {
      activity.startActivity(
          new Intent(
              android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
              Uri.parse("package:" + context.getPackageName())));
      status = "이 앱의 설치 허용 후 업데이트 설치를 다시 누르세요";
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
                    status = "차량 제어 완료 후 설치를 다시 누르세요";
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
                    changed.run();
                  }
                });
          } catch (Exception e) {
            installReady = false;
            status = "설치 보류 · " + e.getMessage();
          } finally {
            busy.set(false);
            changed.run();
          }
        });
  }
}
