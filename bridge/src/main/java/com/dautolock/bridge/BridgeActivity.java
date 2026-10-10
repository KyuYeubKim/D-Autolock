package com.dautolock.bridge;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.graphics.Bitmap;
import android.os.*;
import android.view.*;
import android.widget.*;
import com.dautolock.link.*;
import com.google.zxing.*;
import com.google.zxing.common.BitMatrix;
import java.util.*;
import org.json.JSONObject;

public final class BridgeActivity extends Activity {
  static final String VERSION = "0.3.2";
  private TextView status;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final Runnable tick =
      new Runnable() {
        public void run() {
          long age =
              BridgeService.sampledAt < 0
                  ? -1
                  : SystemClock.elapsedRealtime() - BridgeService.sampledAt;
          status.setText(
              BridgeService.status
                  + "\n기어 "
                  + (age >= 0 && age <= 3000 ? BridgeService.lastSample : "미확인")
                  + "\n"
                  + BridgeService.detail);
          handler.postDelayed(this, 500);
        }
      };

  public void onCreate(Bundle saved) {
    super.onCreate(saved);
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    ScrollView scroll = new ScrollView(this);
    LinearLayout box = new LinearLayout(this);
    box.setOrientation(LinearLayout.VERTICAL);
    box.setPadding(32, 24, 32, 24);
    scroll.addView(box);
    setContentView(scroll);
    text(box, "D-Autolock Bridge · " + VERSION, 26);
    text(box, "차량 상태를 읽어 휴대폰으로 전달합니다. 차량과 휴대폰을 시스템 Bluetooth 설정에서 먼저 페어링하세요.", 18);
    status = text(box, "차량 조회 준비", 19);
    button(box, "조회·연결 시작", v -> start());
    button(box, "휴대폰 연결 QR 표시", v -> qr());
    button(
        box,
        "Bluetooth 설정",
        v -> {
          try {
            startActivity(new Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS));
          } catch (ActivityNotFoundException | SecurityException e) {
            error("이 차량에서 Bluetooth 설정 화면을 열 수 없습니다. 차량 설정 메뉴에서 페어링하세요.");
          }
        });
    button(box, "조회·연결 중지", v -> stopService(new Intent(this, BridgeService.class)));
    button(box, "차량 진단 파일 저장", v -> saveDiagnostics());
    button(
        box,
        "연결 초기화 · 새 QR 만들기",
        v ->
            new AlertDialog.Builder(this)
                .setTitle("연결 초기화")
                .setMessage("기존 휴대폰 연결이 해제됩니다. 새 QR을 휴대폰에서 다시 스캔하세요.")
                .setNegativeButton("취소", null)
                .setPositiveButton(
                    "초기화",
                    (d, w) -> {
                      stopService(new Intent(this, BridgeService.class));
                      try {
                        new LinkVault(this).save(new JSONObject().put("qr", Pairing.create().qr()));
                        getSharedPreferences("bridge", 0).edit().remove("peer").commit();
                        handler.postDelayed(this::start, 300);
                      } catch (Exception e) {
                        error("연결키 저장 실패");
                      }
                    })
                .show());
    text(
        box,
        "휴대폰에서 ‘차량 보조 앱 연결 / QR’을 열어 이 화면의 QR을 스캔하세요. BYD 계정 입력은 필요 없습니다. 기어가 계기판과 일치하는지 P/R/N/D 각각"
            + " 확인하세요. 주차브레이크 상수가 없으면 미제공으로 표시합니다.",
        16);
    start();
  }

  private TextView text(LinearLayout p, String s, int size) {
    TextView t = new TextView(this);
    t.setText(s);
    t.setTextSize(size);
    t.setPadding(0, 12, 0, 12);
    p.addView(t);
    return t;
  }

  private void button(LinearLayout p, String s, View.OnClickListener click) {
    Button b = new Button(this);
    b.setText(s);
    b.setOnClickListener(click);
    p.addView(
        b,
        new LinearLayout.LayoutParams(
            -1, (int) (56 * getResources().getDisplayMetrics().density + .5f)));
  }

  private void error(String s) {
    if (isFinishing() || isDestroyed()) return;
    new AlertDialog.Builder(this).setMessage(s).setPositiveButton("확인", null).show();
  }

  static final String DIAGNOSTIC_NAME = "D-Autolock-Bridge-diagnostics.txt";

  String report() {
    return "D-Autolock Bridge "
        + VERSION
        + "\nsdk="
        + Build.VERSION.SDK_INT
        + " model="
        + Build.MODEL
        + "\nstatus="
        + BridgeService.status
        + "\nmonotonicNow="
        + SystemClock.elapsedRealtime()
        + " sampledAt="
        + BridgeService.sampledAt
        + "\nlastGear="
        + BridgeService.lastSample
        + "\n"
        + BridgeService.detail
        + "\n\n[부팅/자동시작 기록]\n"
        + BootLog.read(this)
        + "\n";
  }

  /**
   * The DiLink document picker (ACTION_CREATE_DOCUMENT) crashed the app on this head unit, so it is
   * not used at all. Save straight to Downloads (Android 10+) or the app folder and show the text;
   * every step is guarded so the button can never crash.
   */
  private void saveDiagnostics() {
    String report, where;
    try {
      report = report();
    } catch (Throwable e) {
      report = "진단 내용 생성 실패: " + e.getClass().getSimpleName();
    }
    try {
      where = saveFallback(report);
    } catch (Throwable e) {
      where = "파일 저장 실패 (" + e.getClass().getSimpleName() + ") · 아래 내용을 촬영해 주세요";
    }
    showReport(where, report);
  }

  private String saveFallback(String report) throws Exception {
    byte[] data = report.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    if (Build.VERSION.SDK_INT >= 29) {
      android.content.ContentValues values = new android.content.ContentValues();
      values.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, DIAGNOSTIC_NAME);
      values.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/plain");
      android.net.Uri uri =
          getContentResolver()
              .insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
      if (uri != null)
        try (java.io.OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
          if (out == null) throw new java.io.IOException("output");
          out.write(data);
          return "다운로드 폴더에 저장: " + DIAGNOSTIC_NAME;
        }
    }
    java.io.File dir = getExternalFilesDir(null);
    if (dir == null) dir = getFilesDir();
    java.io.File file = new java.io.File(dir, DIAGNOSTIC_NAME);
    try (java.io.FileOutputStream out = new java.io.FileOutputStream(file)) {
      out.write(data);
    }
    return "저장: " + file.getAbsolutePath();
  }

  private void showReport(String where, String report) {
    if (isFinishing() || isDestroyed()) return;
    try {
      TextView text = new TextView(this);
      text.setText(where + "\n\n" + report);
      text.setTextIsSelectable(true);
      text.setPadding(32, 16, 32, 16);
      ScrollView scroll = new ScrollView(this);
      scroll.addView(text);
      new AlertDialog.Builder(this)
          .setTitle("차량 진단")
          .setView(scroll)
          .setPositiveButton("닫기", null)
          .show();
    } catch (Throwable e) {
      Toast.makeText(this, "진단: " + where, Toast.LENGTH_LONG).show();
    }
  }

  private void start() {
    ArrayList<String> needed = new ArrayList<>();
    if (Build.VERSION.SDK_INT >= 31
        && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.BLUETOOTH_CONNECT);
    if (Build.VERSION.SDK_INT >= 33
        && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        && !getPreferences(0).getBoolean("notificationAsked", false)) {
      getPreferences(0).edit().putBoolean("notificationAsked", true).apply();
      needed.add(Manifest.permission.POST_NOTIFICATIONS);
    }
    String common = "android.permission.BYDAUTO_GEARBOX_COMMON";
    try {
      PermissionInfo info = getPackageManager().getPermissionInfo(common, 0);
      if ((info.protectionLevel & PermissionInfo.PROTECTION_MASK_BASE)
              == PermissionInfo.PROTECTION_DANGEROUS
          && checkSelfPermission(common) != PackageManager.PERMISSION_GRANTED) needed.add(common);
    } catch (PackageManager.NameNotFoundException ignored) {
    }
    if (!needed.isEmpty()) {
      requestPermissions(needed.toArray(new String[0]), 10);
      return;
    }
    try {
      LinkVault vault = new LinkVault(this);
      JSONObject saved = vault.read();
      if (!saved.has("qr")) vault.save(new JSONObject().put("qr", Pairing.create().qr()));
      startForegroundService(new Intent(this, BridgeService.class));
      BridgeJobService.schedule(this); // Keep the service alive across head-unit standby/force-stop.
    } catch (Exception e) {
      error("연결 시작 실패 · " + e.getClass().getSimpleName());
    }
  }

  private void qr() {
    try {
      String content = new LinkVault(this).read().getString("qr");
      BitMatrix bits = new MultiFormatWriter().encode(content, BarcodeFormat.QR_CODE, 560, 560);
      Bitmap bitmap = Bitmap.createBitmap(560, 560, Bitmap.Config.ARGB_8888);
      for (int y = 0; y < 560; y++)
        for (int x = 0; x < 560; x++)
          bitmap.setPixel(x, y, bits.get(x, y) ? 0xff000000 : 0xffffffff);
      ImageView view = new ImageView(this);
      view.setImageBitmap(bitmap);
      view.setAdjustViewBounds(true);
      AlertDialog dialog =
          new AlertDialog.Builder(this)
              .setTitle("휴대폰 D-Autolock에서 스캔")
              .setView(view)
              .setPositiveButton("닫기", null)
              .create();
      dialog.show();
      dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
      // Dismissing after the activity is gone would throw "not attached to window manager".
      handler.postDelayed(
          () -> {
            if (!isFinishing() && !isDestroyed() && dialog.isShowing()) dialog.dismiss();
          },
          60000);
    } catch (Exception e) {
      error("먼저 조회·연결 시작을 누르세요");
    }
  }

  @Override
  public void onRequestPermissionsResult(int r, String[] p, int[] g) {
    super.onRequestPermissionsResult(r, p, g);
    if (r == 10) {
      boolean ok = true;
      for (int i = 0; i < p.length; i++)
        if (!p[i].equals(Manifest.permission.POST_NOTIFICATIONS)
            && (i >= g.length || g[i] != PackageManager.PERMISSION_GRANTED)) ok = false;
      if (ok) start();
      else error("차량 조회 또는 Bluetooth 권한이 필요합니다. 설정에서 허용 후 다시 시작하세요.");
    }
  }

  @Override
  protected void onStart() {
    super.onStart();
    handler.post(tick);
  }

  @Override
  protected void onStop() {
    handler.removeCallbacks(tick);
    super.onStop();
  }

  @Override
  protected void onDestroy() {
    handler.removeCallbacksAndMessages(null);
    super.onDestroy();
  }

}
