package com.dautolock.app;

import android.Manifest;
import android.app.*;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import com.dautolock.app.api.CloudClient;
import com.dautolock.app.core.VehicleSnapshot;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

public final class MainActivity extends Activity {
  private static final int BG = 0xff09111e,
      CARD = 0xff142133,
      MINT = 0xff71edcf,
      TEXT = 0xffedf3fa,
      MUTED = 0xffa9bbce;
  private Controller controller;
  private LinearLayout body;
  private TextView vehicle, state, signal, message, account, device, capabilities, log;
  private Button monitor, refresh, chooseVehicle, logout;
  private Switch auto;
  private boolean updating;
  private final List<Button> commands = new ArrayList<>();
  private final Runnable observer = this::update;
  private BluetoothLeScanner pickerScanner;
  private ScanCallback pickerCallback;
  private final Handler handler = new Handler(Looper.getMainLooper());

  @Override
  public void onCreate(Bundle saved) {
    super.onCreate(saved);
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    controller = ((DApplication) getApplication()).controller();
    build();
  }

  @Override
  protected void onStart() {
    super.onStart();
    controller.observe(observer);
    update();
  }

  @Override
  protected void onStop() {
    controller.remove(observer);
    stopPicker();
    super.onStop();
  }

  private int dp(int n) {
    return (int) (getResources().getDisplayMetrics().density * n + .5f);
  }

  private GradientDrawable background(int color, int radius) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(color);
    d.setCornerRadius(dp(radius));
    return d;
  }

  private TextView text(LinearLayout parent, String value, int size, int color) {
    TextView t = new TextView(this);
    t.setText(value);
    t.setTextColor(color);
    t.setTextSize(size);
    t.setPadding(0, dp(4), 0, dp(7));
    parent.addView(t);
    return t;
  }

  private LinearLayout card(String title) {
    LinearLayout box = new LinearLayout(this);
    box.setOrientation(LinearLayout.VERTICAL);
    box.setPadding(dp(18), dp(14), dp(18), dp(16));
    box.setBackground(background(CARD, 20));
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
    p.setMargins(0, dp(12), 0, 0);
    body.addView(box, p);
    text(box, title, 14, MINT).setTypeface(null, Typeface.BOLD);
    return box;
  }

  private Button button(LinearLayout box, String title, View.OnClickListener listener) {
    Button b = new Button(this);
    b.setText(title);
    b.setTextColor(TEXT);
    b.setAllCaps(false);
    b.setTextSize(14);
    b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xff263b53));
    b.setOnClickListener(listener);
    box.addView(b, new LinearLayout.LayoutParams(-1, dp(52)));
    return b;
  }

  private void build() {
    ScrollView scroll = new ScrollView(this);
    scroll.setBackgroundColor(BG);
    scroll.setFillViewport(true);
    body = new LinearLayout(this);
    body.setOrientation(LinearLayout.VERTICAL);
    body.setPadding(dp(20), dp(24), dp(20), dp(32));
    scroll.addView(body);
    setContentView(scroll);
    text(body, "D-Autolock", 30, TEXT).setTypeface(null, Typeface.BOLD);
    text(body, "DOLPHIN  /  대한민국  /  테스트 버전 0.1.0", 12, MUTED);
    LinearLayout dash = card("MY DOLPHIN");
    vehicle = text(dash, "차량을 연결하세요", 23, TEXT);
    state = text(dash, "차량 상태 미확인", 15, MUTED);
    signal = text(dash, "신호 대기", 20, MINT);
    message = text(dash, "", 14, TEXT);
    refresh = button(dash, "차량 상태 새로고침", v -> controller.refresh());
    LinearLayout proximity = card("자동 도어");
    text(proximity, "가까워지면 잠금 해제 · 멀어지면 잠금", 17, TEXT);
    text(
        proximity,
        "BLE 신호가 계속 잡히는 차량 기기를 선택하세요. 신호 끊김만으로 잠그지 않습니다. 차량 OFF·정차·최신 상태 확인 후 동작합니다.",
        13,
        MUTED);
    monitor =
        button(
            proximity,
            "거리 관찰 시작",
            v -> {
              if (controller.monitoring) {
                controller.stop();
                controller.note("거리 관찰을 종료했습니다");
              } else if (permissions(true)) {
                stopPicker();
                try {
                  startForegroundService(new Intent(this, ProximityService.class));
                } catch (Exception e) {
                  controller.note("거리 관찰을 시작하지 못했습니다. 권한을 확인하세요");
                }
              }
            });
    auto = new Switch(this);
    auto.setText("실제 자동 도어 제어");
    auto.setTextColor(TEXT);
    auto.setPadding(dp(4), dp(12), dp(4), dp(12));
    auto.setMinHeight(dp(48));
    proximity.addView(auto);
    auto.setOnCheckedChangeListener(
        (b, on) -> {
          if (updating) return;
          if (!on) {
            controller.auto(false);
            return;
          }
          updating = true;
          auto.setChecked(false);
          updating = false;
          new AlertDialog.Builder(this)
              .setTitle("자동 도어 제어 켜기")
              .setMessage(
                  "이 휴대폰의 거리 변화로 차량을 잠그거나 잠금 해제합니다. 차량 전원을 끈 상태에서 신호를 먼저 확인하고, 다른 탑승자와 키의 위치를"
                      + " 확인하세요.")
              .setNegativeButton("취소", null)
              .setPositiveButton("자동 제어 켜기", (d, w) -> controller.auto(true))
              .show();
        });
    LinearLayout controls = card("차량 제어");
    commands.add(button(controls, "도어 잠금 해제", v -> confirm(CloudClient.Command.UNLOCK)));
    commands.add(button(controls, "도어 잠금", v -> confirm(CloudClient.Command.LOCK)));
    commands.add(button(controls, "Stop · 차량 종료 검증", v -> confirm(CloudClient.Command.STOP)));
    capabilities = text(controls, "차량 기능 확인 전", 12, MUTED);
    text(controls, "자동 READY · 지원 확인 필요", 16, 0xffffc77d);
    text(
        controls,
        "문 열림 → 주행 READY 명령은 확인되지 않았습니다. 자동 시동·이탈 시 자동 종료는 이 버전에 포함되지 않습니다. Stop은 주차 상태에서 수동"
            + " 검증합니다.",
        13,
        MUTED);
    LinearLayout link = card("01  BYD AUTO 계정");
    account = text(link, "연결 전", 15, TEXT);
    text(
        link,
        "BYD AUTO에서 차량 공유를 승인한 Sub 계정으로 로그인하세요. 공유 차량 목록과 차량 기능을 조회하며, 실제 제어 권한은 BYD 서버가 확인합니다.",
        13,
        MUTED);
    button(link, "Sub 계정 로그인 / 변경", v -> loginDialog());
    button(link, "계정 차량 목록 불러오기", v -> controller.refreshVehicles());
    chooseVehicle = button(link, "공유 차량 선택", v -> vehicleDialog());
    logout =
        button(
            link,
            "로그아웃 · 저장 정보 삭제",
            v ->
                new AlertDialog.Builder(this)
                    .setMessage("계정과 차량 정보를 이 휴대폰에서 삭제할까요?")
                    .setNegativeButton("취소", null)
                    .setPositiveButton("삭제", (d, w) -> controller.logout())
                    .show());
    LinearLayout bluetooth = card("02  차량 블루투스");
    device = text(bluetooth, "기기 선택 전", 15, TEXT);
    button(bluetooth, "블루투스 기기 검색 / 선택", v -> pickDevice());
    button(bluetooth, "거리 감도 조정", v -> thresholdDialog());
    text(
        bluetooth,
        "블루투스는 거리 감지용입니다. 도어 제어에는 휴대폰과 차량의 인터넷 연결이 필요합니다. 전원을 끄면 신호가 사라지는 오디오 기기는 사용할 수 없습니다.",
        13,
        MUTED);
    LinearLayout events = card("최근 동작");
    log = text(events, "", 12, MUTED);
    button(body, "사용 안내 · 오픈소스", v -> about());
    text(body, "D-Autolock  ·  비공식 개인용 앱", 12, MUTED);
  }

  private void update() {
    if (isFinishing()) return;
    vehicle.setText(controller.vehicleName);
    VehicleSnapshot s = controller.snapshot;
    state.setText(
        s == null
            ? "차량 상태 미확인"
            : ("잠금 "
                + (s.locked == null ? "미확인" : s.locked ? "LOCK" : "UNLOCK")
                + "  ·  전원 "
                + s.powerLabel()
                + "\n속도 "
                + (s.speed == null ? "미확인" : s.speed + " km/h")
                + "  ·  배터리 "
                + (s.battery == null ? "미확인" : s.battery.intValue() + "%")
                + (s.fresh(System.currentTimeMillis()) ? "" : "\n상태가 오래되었거나 시각 미확인")));
    signal.setText(controller.signal);
    message.setText(controller.message);
    log.setText(controller.log());
    monitor.setText(controller.monitoring ? "거리 관찰 종료" : "거리 관찰 시작");
    updating = true;
    auto.setChecked(controller.autoEnabled);
    auto.setEnabled(
        controller.monitoring
            && !controller.vin.isEmpty()
            && CloudClient.hasFeature(controller.capabilities, "1005")
            && CloudClient.hasFeature(controller.capabilities, "1006"));
    updating = false;
    account.setText(
        controller.cloud.protocol.isLoggedIn()
            ? "계정 연결됨 · 대한민국\n" + controller.permissionSummary
            : "계정 연결 전 · 대한민국");
    device.setText(
        controller.settings.getString("deviceName", "기기 선택 전")
            + "\n"
            + controller.settings.getString("address", "")
            + "\n접근 "
            + controller.settings.getInt("near", -65)
            + " / 이탈 "
            + controller.settings.getInt("far", -80)
            + " dBm");
    capabilities.setText(
        "차량 기능 · 잠금 "
            + controller.feature(CloudClient.Command.LOCK)
            + " / 해제 "
            + controller.feature(CloudClient.Command.UNLOCK)
            + " / Stop "
            + controller.feature(CloudClient.Command.STOP));
    boolean ready = !controller.busy() && !controller.vin.isEmpty();
    refresh.setEnabled(ready);
    chooseVehicle.setEnabled(!controller.busy() && controller.vehicles.length() > 0);
    logout.setEnabled(!controller.busy());
    for (int i = 0; i < commands.size(); i++)
      commands
          .get(i)
          .setEnabled(
              ready
                  && CloudClient.hasFeature(
                      controller.capabilities, CloudClient.Command.values()[i].feature));
  }

  private EditText field(LinearLayout parent, String hint, int type) {
    EditText e = new EditText(this);
    e.setHint(hint);
    e.setInputType(type);
    e.setSingleLine(true);
    e.setTextColor(TEXT);
    e.setHintTextColor(MUTED);
    e.setMinHeight(dp(52));
    e.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
    parent.addView(e);
    return e;
  }

  private LinearLayout form() {
    LinearLayout f = new LinearLayout(this);
    f.setOrientation(LinearLayout.VERTICAL);
    f.setPadding(dp(24), dp(8), dp(24), dp(8));
    return f;
  }

  private void loginDialog() {
    LinearLayout f = form();
    text(
        f,
        "대한민국 BYD AUTO Sub 계정\n로그인 비밀번호는 저장하지 않습니다. 세션과 제어 PIN 해시는 휴대폰 보안 키로 암호화합니다.",
        13,
        MUTED);
    EditText user =
        field(
            f,
            "이메일 / BYD 로그인 ID",
            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
    EditText pass =
        field(
            f, "BYD 로그인 비밀번호", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
    EditText pin =
        field(
            f,
            "BYD 원격 제어 PIN (6자리)",
            InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
    AlertDialog dialog =
        new AlertDialog.Builder(this)
            .setTitle("BYD 계정 연결")
            .setView(f)
            .setNegativeButton("취소", null)
            .setPositiveButton("연결", null)
            .create();
    dialog.setOnShowListener(
        d -> {
          dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
          dialog
              .getButton(-1)
              .setOnClickListener(
                  v -> {
                    String u = user.getText().toString().trim(),
                        p = pass.getText().toString(),
                        n = pin.getText().toString();
                    if (u.isEmpty() || p.isEmpty() || !n.matches("[0-9]{6}")) {
                      pin.setError("계정 정보와 6자리 제어 PIN을 입력하세요");
                      return;
                    }
                    if (controller.busy()) {
                      Toast.makeText(this, "이전 요청 완료 후 다시 연결하세요", Toast.LENGTH_SHORT).show();
                      return;
                    }
                    controller.login(u, p, n);
                    pass.setText("");
                    pin.setText("");
                    dialog.dismiss();
                  });
        });
    dialog.show();
  }

  private void vehicleDialog() {
    JSONArray a = controller.vehicles;
    String[] rows = new String[a.length()];
    for (int i = 0; i < a.length(); i++) {
      JSONObject o = a.optJSONObject(i);
      String vin = o == null ? "" : o.optString("vin");
      rows[i] =
          (o == null ? "차량" : o.optString("modelName", "BYD"))
              + " · VIN 끝 "
              + vin.substring(Math.max(0, vin.length() - 6));
    }
    new AlertDialog.Builder(this)
        .setTitle("연동할 차량 선택")
        .setItems(
            rows,
            (d, w) -> {
              JSONObject item = a.optJSONObject(w);
              if (item != null) controller.selectVehicle(item);
            })
        .setNegativeButton("취소", null)
        .show();
  }

  private void confirm(CloudClient.Command cmd) {
    new AlertDialog.Builder(this)
        .setTitle(cmd.label)
        .setMessage(
            cmd == CloudClient.Command.STOP
                ? "P단에 주차했고 차량 주변과 탑승자가 안전한지 확인하세요. 정차·주차브레이크 상태를 조회한 뒤 실제 차량 종료 명령을 한 번 전송합니다. 종료"
                    + " 후 계기판에서 전원 OFF를 직접 확인하세요."
                : "선택한 차량에 " + cmd.label + " 명령을 전송합니다.")
        .setNegativeButton("취소", null)
        .setPositiveButton(
            cmd == CloudClient.Command.STOP ? "주차 확인 · 종료" : "실행",
            (d, w) -> controller.command(cmd, false, () -> true))
        .show();
  }

  private void thresholdDialog() {
    LinearLayout f = form();
    text(f, "RSSI는 미터 단위 거리가 아닙니다. 관찰 화면에서 신호를 확인하며 조정하세요. 접근·이탈 기준 간격은 8 dBm 이상입니다.", 13, MUTED);
    EditText
        near =
            field(f, "접근 (-65)", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED),
        far = field(f, "이탈 (-80)", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
    near.setText(String.valueOf(controller.settings.getInt("near", -65)));
    far.setText(String.valueOf(controller.settings.getInt("far", -80)));
    AlertDialog d =
        new AlertDialog.Builder(this)
            .setTitle("거리 감도")
            .setView(f)
            .setNegativeButton("취소", null)
            .setPositiveButton("저장", null)
            .create();
    d.setOnShowListener(
        x ->
            d.getButton(-1)
                .setOnClickListener(
                    v -> {
                      try {
                        controller.thresholds(
                            Integer.parseInt(near.getText().toString()),
                            Integer.parseInt(far.getText().toString()));
                        d.dismiss();
                      } catch (Exception e) {
                        near.setError("-100 ~ -30, 접근이 이탈보다 8 이상 커야 합니다");
                      }
                    }));
    d.show();
  }

  private boolean permissions(boolean notifications) {
    ArrayList<String> missing = new ArrayList<>();
    String[] needed =
        Build.VERSION.SDK_INT >= 31
            ? new String[] {
              Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT
            }
            : new String[] {
              Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION
            };
    for (String p : needed)
      if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) missing.add(p);
    if (notifications
        && !controller.settings.getBoolean("notificationAsked", false)
        && Build.VERSION.SDK_INT >= 33
        && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
      missing.add(Manifest.permission.POST_NOTIFICATIONS);
      controller.settings.edit().putBoolean("notificationAsked", true).apply();
    }
    if (!missing.isEmpty()) {
      requestPermissions(missing.toArray(new String[0]), 20);
      return false;
    }
    return true;
  }

  @Override
  public void onRequestPermissionsResult(int r, String[] p, int[] g) {
    super.onRequestPermissionsResult(r, p, g);
    if (r == 20) Toast.makeText(this, "권한 설정 후 원하는 버튼을 다시 누르세요", Toast.LENGTH_LONG).show();
  }

  private void stopPicker() {
    handler.removeCallbacksAndMessages(null);
    if (pickerScanner != null && pickerCallback != null)
      try {
        pickerScanner.stopScan(pickerCallback);
      } catch (SecurityException ignored) {
      }
    pickerScanner = null;
    pickerCallback = null;
  }

  private void pickDevice() {
    if (!permissions(false)) return;
    if (controller.monitoring) {
      new AlertDialog.Builder(this)
          .setMessage("기기를 변경하려면 거리 관찰을 종료하세요.")
          .setPositiveButton("확인", null)
          .show();
      return;
    }
    try {
      BluetoothManager manager = getSystemService(BluetoothManager.class);
      BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
      if (adapter == null || !adapter.isEnabled()) {
        startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
        return;
      }
      stopPicker();
      LinkedHashMap<String, String> found = new LinkedHashMap<>();
      ArrayList<String> addresses = new ArrayList<>();
      ArrayList<String> rows = new ArrayList<>();
      for (BluetoothDevice b : adapter.getBondedDevices()) {
        found.put(
            b.getAddress(),
            (b.getName() == null ? "이름 없음" : b.getName()) + " · 저장된 기기 (BLE 신호 확인 필요)");
      }
      LinearLayout f = form();
      TextView status = text(f, "20초 동안 BLE 신호를 찾습니다. 차량 가까이에서 선택하세요.", 13, MUTED);
      ListView list = new ListView(this);
      ArrayAdapter<String> items =
          new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, rows);
      list.setAdapter(items);
      f.addView(list, new LinearLayout.LayoutParams(-1, dp(310)));
      Runnable redraw =
          () -> {
            addresses.clear();
            addresses.addAll(found.keySet());
            rows.clear();
            for (String a : addresses) rows.add(found.get(a) + "\n" + a);
            items.notifyDataSetChanged();
          };
      redraw.run();
      AlertDialog d =
          new AlertDialog.Builder(this)
              .setTitle("차량 블루투스 선택")
              .setView(f)
              .setNegativeButton("닫기", null)
              .create();
      list.setOnItemClickListener(
          (p, v, index, id) -> {
            String address = addresses.get(index);
            String label = found.get(address);
            controller.device(label, address);
            d.dismiss();
          });
      d.setOnDismissListener(x -> stopPicker());
      d.show();
      pickerScanner = adapter.getBluetoothLeScanner();
      if (pickerScanner == null) {
        status.setText("BLE 검색을 사용할 수 없습니다");
        return;
      }
      pickerCallback =
          new ScanCallback() {
            @Override
            public void onScanResult(int type, ScanResult result) {
              runOnUiThread(
                  () -> {
                    try {
                      BluetoothDevice b = result.getDevice();
                      String name = b.getName();
                      if (name == null && result.getScanRecord() != null)
                        name = result.getScanRecord().getDeviceName();
                      found.put(
                          b.getAddress(),
                          (name == null ? "이름 없는 BLE 기기" : name)
                              + " · "
                              + result.getRssi()
                              + " dBm");
                      redraw.run();
                    } catch (SecurityException ignored) {
                    }
                  });
            }

            @Override
            public void onScanFailed(int code) {
              runOnUiThread(() -> status.setText("검색 실패 (" + code + "). 잠시 후 다시 시도하세요"));
            }
          };
      pickerScanner.startScan(
          null,
          new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
          pickerCallback);
      handler.postDelayed(
          () -> {
            stopPicker();
            status.setText("검색 완료 · 차량의 BLE 기기를 선택하세요");
          },
          20000);
    } catch (SecurityException e) {
      controller.note("블루투스 권한을 확인하세요");
    }
  }

  private void about() {
    String notices = "";
    try (java.io.InputStream in = getAssets().open("notices.txt");
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
      byte[] b = new byte[4096];
      int n;
      while ((n = in.read(b)) != -1) out.write(b, 0, n);
      notices = new String(out.toByteArray(), StandardCharsets.UTF_8);
    } catch (Exception ignored) {
    }
    new AlertDialog.Builder(this)
        .setTitle("D-Autolock 사용 안내")
        .setMessage(
            "1. BYD AUTO에서 Sub 계정 차량 공유를 승인합니다.\n"
                + "2. Sub 계정과 원격 제어 PIN으로 로그인합니다.\n"
                + "3. 공유 차량과 차량 BLE 기기를 선택합니다.\n"
                + "4. 전원 OFF 상태에서 거리 관찰을 시작합니다.\n"
                + "5. 수동 도어 제어를 확인하고 자동 제어를 켭니다.\n\n"
                + "화면을 꺼도 관찰하려면 휴대폰 설정에서 앱 배터리를 제한 없음으로 설정하세요. 앱을 강제 종료하거나 재부팅하면 관찰을 다시 시작해야"
                + " 합니다.\n\n"
                + "지원되지 않은 신호·권한·차량 상태에서는 제어를 보류합니다. RSSI는 차량의 실제 거리나 실내/실외를 보장하지 않습니다.\n\n"
                + notices)
        .setPositiveButton("닫기", null)
        .show();
  }
}
