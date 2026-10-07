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
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.text.method.PasswordTransformationMethod;
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
  private TextView signalDetails, autoDetails, autoReason, controlDetails;
  private ProgressBar signalGauge;
  private Button monitor, refresh, chooseVehicle, logout, login;
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
    text(body, "DOLPHIN  /  대한민국  /  테스트 버전 0.2.0", 12, MUTED);
    LinearLayout dash = card("MY DOLPHIN");
    vehicle = text(dash, "차량을 연결하세요", 23, TEXT);
    state = text(dash, "차량 상태 미확인", 15, MUTED);
    signal = text(dash, "신호 대기", 20, MINT);
    signalGauge = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
    signalGauge.setMax(100);
    signalGauge.setProgressTintList(android.content.res.ColorStateList.valueOf(MINT));
    signalGauge.setProgressBackgroundTintList(
        android.content.res.ColorStateList.valueOf(0xff30465e));
    dash.addView(signalGauge, new LinearLayout.LayoutParams(-1, dp(18)));
    text(dash, "약함 −100 dBm                         강함 −30 dBm", 11, MUTED);
    signalDetails = text(dash, "현재 미수신 / 평균 —\n수신 0회 · 마지막 수신 없음", 13, MUTED);
    autoDetails = text(dash, "자동 제어 꺼짐", 14, MINT);
    controlDetails = text(dash, "아직 제어 요청 없음", 13, MUTED);
    message = text(dash, "", 14, TEXT);
    refresh = button(dash, "차량 상태 새로고침", v -> controller.refresh());
    LinearLayout proximity = card("자동 도어");
    text(proximity, "가까워지면 잠금 해제 · 멀어지면 잠금", 17, TEXT);
    text(
        proximity,
        "설정한 시간 동안 가까운 신호가 유지되면 해제합니다. 이탈·신호 끊김 시간은 감도 설정에서 조절합니다. 정차·도어·전원 상태 확인 후 실행합니다.",
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
    autoReason = text(proximity, "거리 관찰을 먼저 시작하세요", 13, MUTED);
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
                  "접근 신호가 안정되면 잠금 해제합니다. 이탈하거나 수신하던 BLE 신호가 "
                      + controller.settings.getInt("lossLockSeconds", 10)
                      + "초 끊기면 잠금을 요청합니다. 다른 탑승자와 키의 위치를"
                      + " 확인하세요.")
              .setNegativeButton("취소", null)
              .setPositiveButton("자동 제어 켜기", (d, w) -> controller.auto(true))
              .show();
        });
    LinearLayout controls = card("차량 제어");
    commands.add(
        button(
            controls,
            "도어 잠금 해제",
            v -> controller.command(CloudClient.Command.UNLOCK, false, () -> true)));
    commands.add(
        button(
            controls,
            "도어 잠금",
            v -> controller.command(CloudClient.Command.LOCK, false, () -> true)));
    commands.add(button(controls, "Stop · 차량 종료 검증", v -> confirm(CloudClient.Command.STOP)));
    capabilities = text(controls, "차량 기능 확인 전", 12, MUTED);
    button(
        controls,
        "공조 2초 동작 · READY",
        v ->
            new AlertDialog.Builder(this)
                .setTitle("공조 2초 동작")
                .setMessage(
                    "P단 주차를 확인하세요. 23°C로 공조 ON 응답을 확인한 뒤 2초를 기다리고 OFF를 보냅니다. 통신 시간 때문에 실제 작동 시간은 더"
                        + " 길 수 있습니다. 완료 후 계기판 READY/OK 표시를 확인하세요.")
                .setNegativeButton("취소", null)
                .setPositiveButton("주차 확인 · 실행", (d, w) -> controller.manualPulse())
                .show());
    Switch readySwitch = new Switch(this);
    readySwitch.setText("자동 해제 후 문 열림 → 공조 2초");
    readySwitch.setTextColor(TEXT);
    readySwitch.setPadding(0, dp(12), 0, dp(12));
    readySwitch.setMinHeight(dp(56));
    readySwitch.setChecked(controller.settings.getBoolean("autoReady", false));
    controls.addView(readySwitch);
    readySwitch.setOnCheckedChangeListener((b, on) -> controller.readyOption(on));
    Switch windowsSwitch = new Switch(this);
    windowsSwitch.setText("도어 잠금 시 전체 창문 닫기");
    windowsSwitch.setTextColor(TEXT);
    windowsSwitch.setPadding(0, dp(12), 0, dp(12));
    windowsSwitch.setMinHeight(dp(56));
    windowsSwitch.setChecked(controller.settings.getBoolean("closeWindows", true));
    controls.addView(windowsSwitch);
    windowsSwitch.setOnCheckedChangeListener(
        (b, on) -> {
          controller.settings.edit().putBoolean("closeWindows", on).apply();
          controller.note(on ? "도어 잠금 시 전체 창문 닫기 켜짐" : "창문 닫기 연동 꺼짐");
        });
    text(controls, "READY · 공조 2초 동작 연동", 16, 0xffffc77d);
    text(
        controls,
        "사용자 차량에서 확인한 공조 ON/OFF 동작입니다. 자동 해제 후 90초 안의 문 닫힘→열림을 확인하면 실행합니다. 주차브레이크 정보가 없으면 최신 전원"
            + " OFF·속도 0 상태에서만 요청합니다. READY 자체는 계기판에서 확인하세요. 이탈 시 자동 전원 종료는 포함되지 않습니다.",
        13,
        MUTED);
    LinearLayout link = card("01  BYD AUTO 계정");
    account = text(link, "연결 전", 15, TEXT);
    text(
        link,
        "BYD AUTO에서 차량 공유를 승인한 Sub 계정으로 로그인하세요. 공유 차량 목록과 차량 기능을 조회하며, 실제 제어 권한은 BYD 서버가 확인합니다.",
        13,
        MUTED);
    login = button(link, "Sub 계정 로그인 / 변경", v -> loginDialog());
    button(link, "계정 차량 목록 불러오기", v -> controller.refreshVehicles());
    chooseVehicle = button(link, "공유 차량 선택", v -> vehicleDialog());
    logout =
        button(
            link,
            "로그아웃 · 저장 정보 삭제",
            v ->
                new AlertDialog.Builder(this)
                    .setMessage("저장된 ID·비밀번호·제어 PIN과 차량 연결 정보를 이 휴대폰에서 삭제할까요?")
                    .setNegativeButton("취소", null)
                    .setPositiveButton("삭제", (d, w) -> controller.logout())
                    .show());
    LinearLayout bluetooth = card("02  차량 블루투스");
    device = text(bluetooth, "기기 선택 전", 15, TEXT);
    button(bluetooth, "블루투스 기기 검색 / 선택", v -> pickDevice());
    button(bluetooth, "거리 감도 / 대기 시간", v -> thresholdDialog());
    text(
        bluetooth,
        "블루투스는 거리 감지용입니다. 도어 제어에는 휴대폰과 차량의 인터넷 연결이 필요합니다. 전원을 끄면 신호가 사라지는 오디오 기기는 사용할 수 없습니다.",
        13,
        MUTED);
    LinearLayout events = card("최근 동작");
    log = text(events, "", 12, MUTED);
    button(events, "진단 로그 보기", v -> showDiagnostics());
    button(
        events,
        "진단 로그 파일 저장",
        v -> {
          Intent save =
              new Intent(Intent.ACTION_CREATE_DOCUMENT)
                  .addCategory(Intent.CATEGORY_OPENABLE)
                  .setType("text/plain")
                  .putExtra(
                      Intent.EXTRA_TITLE,
                      "D-Autolock-diagnostics-"
                          + new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", Locale.KOREA)
                              .format(new Date())
                          + ".txt");
          try {
            startActivityForResult(save, 51);
          } catch (ActivityNotFoundException e) {
            controller.note("파일 저장 앱을 찾지 못했습니다. 진단 로그 보기를 사용하세요");
          }
        });
    button(
        events,
        "저장된 진단 로그 삭제",
        v -> controller.diagnostics.clear(() -> controller.note("진단 로그를 삭제했습니다")));
    text(
        events,
        "신호·판단·제어 결과를 휴대폰에 자동 저장합니다(최대 약 1.5 MB). 계정·비밀번호·PIN·VIN·기기 주소와 서버 원문은 포함하지 않습니다.",
        12,
        MUTED);
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
    signalGauge.setProgress(controller.signalStrength);
    signalGauge.setContentDescription(
        "BLE 수신 강도 " + controller.signalStrength + " / 100. 미수신 시 0.");
    signalDetails.setText(controller.signalDetail);
    autoDetails.setText(controller.autoDetail);
    controlDetails.setText("최근 제어 · " + controller.lastControl);
    message.setText(controller.message);
    log.setText(controller.log());
    monitor.setText(controller.monitoring ? "거리 관찰 종료" : "거리 관찰 시작");
    updating = true;
    auto.setChecked(controller.autoEnabled);
    String unavailable = controller.autoUnavailable();
    auto.setEnabled(unavailable == null);
    autoReason.setText(
        unavailable != null
            ? unavailable
            : controller.autoEnabled
                ? "자동 제어 ON · 진단 로그에서 판단과 결과를 확인하세요"
                : "스위치를 켜면 실제 차량 명령을 보냅니다. 기능 지원은 실행 전 조회합니다.");
    updating = false;
    account.setText(
        (controller.cloud.protocol.isLoggedIn()
                ? "계정 연결됨 · 대한민국\n" + controller.permissionSummary
                : controller.hasSavedLogin() ? "계정 저장됨 · 사용 시 자동 재연결" : "계정 연결 전 · 대한민국")
            + (controller.hasSavedLogin() ? "\n비밀번호 ******** · 암호화 저장됨" : ""));
    device.setText(
        controller.settings.getString("deviceName", "기기 선택 전")
            + "\n"
            + controller.settings.getString("address", "")
            + "\n접근 "
            + controller.settings.getInt("near", -65)
            + " / 이탈 "
            + controller.settings.getInt("far", -80)
            + " dBm\n접근 "
            + controller.settings.getInt("nearWaitSeconds", 3)
            + "초 / 이탈 "
            + controller.settings.getInt("farWaitSeconds", 8)
            + "초 / 신호 끊김 "
            + controller.settings.getInt("lossLockSeconds", 10)
            + "초");
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
    logout.setEnabled(!controller.busy() && !controller.initializing);
    login.setEnabled(!controller.busy() && !controller.initializing);
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
    e.setSaveEnabled(false);
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
    if (controller.initializing || controller.busy()) {
      controller.note("계정 복원 또는 이전 요청 완료 후 다시 연결하세요");
      return;
    }
    LinearLayout f = form();
    text(
        f,
        "대한민국 BYD AUTO Sub 계정\n"
            + "ID·비밀번호·세션·제어 PIN 해시를 이 휴대폰에 암호화해 저장합니다. 저장된 비밀번호와 PIN은 변경할 때만 입력하세요.",
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
    pass.setTransformationMethod(PasswordTransformationMethod.getInstance());
    pin.setTransformationMethod(PasswordTransformationMethod.getInstance());
    user.setText(controller.loginUser);
    Runnable hints =
        () -> {
          String id = user.getText().toString().trim();
          pass.setHint(
              controller.savedPasswordFor(id) ? "******** (저장됨 · 변경 시 입력)" : "BYD 로그인 비밀번호");
          pin.setHint(
              controller.savedPinFor(id) ? "****** (PIN 저장됨 · 변경 시 입력)" : "BYD 원격 제어 PIN (6자리)");
        };
    hints.run();
    user.addTextChangedListener(
        new TextWatcher() {
          public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

          public void onTextChanged(CharSequence s, int start, int before, int count) {
            hints.run();
          }

          public void afterTextChanged(Editable e) {}
        });
    AlertDialog dialog =
        new AlertDialog.Builder(this)
            .setTitle("BYD 계정 연결")
            .setView(f)
            .setNegativeButton("취소", null)
            .setPositiveButton("연결", null)
            .create();
    dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    dialog.setOnShowListener(
        d -> {
          dialog
              .getButton(-1)
              .setOnClickListener(
                  v -> {
                    String u = user.getText().toString().trim(),
                        p = pass.getText().toString(),
                        n = pin.getText().toString();
                    if (u.isEmpty()
                        || (p.isEmpty() && !controller.savedPasswordFor(u))
                        || (n.isEmpty() ? !controller.savedPinFor(u) : !n.matches("[0-9]{6}"))) {
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
    dialog.setOnDismissListener(
        d -> {
          pass.setText("");
          pin.setText("");
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
    text(f, "게이지를 움직여 조절하세요. dBm은 미터 단위 거리가 아닙니다. 접근·이탈 기준은 8 dBm 간격을 유지하도록 함께 조정됩니다.", 13, MUTED);
    SeekBar[] sensitivity = new SeekBar[2];
    sensitivity[0] =
        settingSlider(
            f,
            "접근 감도",
            "near",
            -92,
            -30,
            controller.settings.getInt("near", -65),
            " dBm 이상",
            "멀리서도 해제 ← → 가까워야 해제",
            value -> {
              if (sensitivity[1] != null && value < sensitivity[1].getProgress() - 100 + 8)
                sensitivity[1].setProgress(value - 8 + 100);
            });
    sensitivity[1] =
        settingSlider(
            f,
            "이탈 감도",
            "far",
            -100,
            -38,
            controller.settings.getInt("far", -80),
            " dBm 이하",
            "더 멀어져야 잠금 ← → 가까운 곳부터 잠금",
            value -> {
              if (value > sensitivity[0].getProgress() - 92 - 8)
                sensitivity[0].setProgress(value + 8 + 92);
            });
    SeekBar nearWait =
        settingSlider(
            f,
            "접근 후 잠금 해제 대기",
            "nearWait",
            0,
            15,
            controller.settings.getInt("nearWaitSeconds", 3),
            "초",
            "0초 ← → 15초",
            value -> {});
    SeekBar farWait =
        settingSlider(
            f,
            "이탈 후 잠금 대기",
            "farWait",
            0,
            30,
            controller.settings.getInt("farWaitSeconds", 8),
            "초",
            "0초 ← → 30초",
            value -> {});
    SeekBar lossWait =
        settingSlider(
            f,
            "BLE 신호 끊김 후 잠금",
            "lossWait",
            5,
            60,
            controller.settings.getInt("lossLockSeconds", 10),
            "초",
            "5초 ← → 60초",
            value -> {});
    text(
        f,
        "기본값: 접근 −65 / 이탈 −80 dBm, 접근 3초 / 이탈 8초 / 신호 끊김 10초.\n"
            + "0초도 유효 신호 4회와 차량 상태 조회가 필요합니다. 실제 동작에는 통신 시간과 명령 간격이 더해집니다. 저장하면 관찰이 종료되므로 거리 관찰과 자동"
            + " 제어를 다시 켜세요.",
        13,
        MUTED);
    ScrollView scroll = new ScrollView(this);
    scroll.addView(f);
    AlertDialog d =
        new AlertDialog.Builder(this)
            .setTitle("감도 · 대기 시간")
            .setView(scroll)
            .setNegativeButton("취소", null)
            .setNeutralButton("기본값", null)
            .setPositiveButton("저장", null)
            .create();
    d.setOnShowListener(
        x -> {
          d.getButton(-3)
              .setOnClickListener(
                  v -> {
                    sensitivity[0].setProgress(-65 + 92);
                    sensitivity[1].setProgress(-80 + 100);
                    nearWait.setProgress(3);
                    farWait.setProgress(8);
                    lossWait.setProgress(10 - 5);
                  });
          d.getButton(-1)
              .setOnClickListener(
                  v -> {
                    try {
                      controller.thresholds(
                          sensitivity[0].getProgress() - 92,
                          sensitivity[1].getProgress() - 100,
                          nearWait.getProgress(),
                          farWait.getProgress(),
                          lossWait.getProgress() + 5);
                      d.dismiss();
                    } catch (Exception e) {
                      Toast.makeText(this, "감도와 대기 시간 범위를 확인하세요", Toast.LENGTH_SHORT).show();
                    }
                  });
        });
    d.show();
  }

  private SeekBar settingSlider(
      LinearLayout parent,
      String title,
      String tag,
      int min,
      int max,
      int value,
      String suffix,
      String direction,
      java.util.function.IntConsumer changed) {
    TextView caption = text(parent, title + " · " + value + suffix, 15, TEXT);
    SeekBar bar = new SeekBar(this);
    bar.setTag(tag);
    bar.setMax(max - min);
    bar.setProgress(value - min);
    bar.setMinimumHeight(dp(48));
    bar.setProgressTintList(android.content.res.ColorStateList.valueOf(MINT));
    bar.setThumbTintList(android.content.res.ColorStateList.valueOf(MINT));
    bar.setContentDescription(caption.getText());
    bar.setOnSeekBarChangeListener(
        new SeekBar.OnSeekBarChangeListener() {
          public void onStartTrackingTouch(SeekBar b) {}

          public void onStopTrackingTouch(SeekBar b) {}

          public void onProgressChanged(SeekBar b, int progress, boolean fromUser) {
            caption.setText(title + " · " + (min + progress) + suffix);
            b.setContentDescription(caption.getText());
            changed.accept(min + progress);
          }
        });
    parent.addView(bar, new LinearLayout.LayoutParams(-1, dp(48)));
    text(parent, direction, 12, MUTED);
    return bar;
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
      {
        {
          {
            missing.add(Manifest.permission.POST_NOTIFICATIONS);
            controller.settings.edit().putBoolean("notificationAsked", true).apply();
          }
          controller.settings.edit().putBoolean("notificationAsked", true).apply();
        }
        controller.settings.edit().putBoolean("notificationAsked", true).apply();
      }
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
      HashMap<String, String> names = new HashMap<>();
      ArrayList<String> addresses = new ArrayList<>();
      ArrayList<String> rows = new ArrayList<>();
      for (BluetoothDevice b : adapter.getBondedDevices()) {
        String label = b.getName() == null ? "이름 없음" : b.getName();
        names.put(b.getAddress(), label);
        found.put(
            b.getAddress(),
            label
                + (b.getType() == BluetoothDevice.DEVICE_TYPE_CLASSIC
                    ? " · 일반 Bluetooth · BLE 미확인"
                    : " · 저장된 기기 · BLE 수신 미확인"));
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
            String label = names.get(address);
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
                      names.put(b.getAddress(), name == null ? "이름 없는 BLE 기기" : name);
                      found.put(
                          b.getAddress(),
                          (name == null ? "이름 없는 BLE 기기" : name)
                              + " · BLE 수신 "
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

  private void showDiagnostics() {
    controller.diagnostics.snapshot(
        data ->
            runOnUiThread(
                () -> {
                  if (isFinishing() || isDestroyed()) return;
                  String recent =
                      data.length() > 24000
                          ? "최근 기록만 표시합니다. 전체 기록은 파일로 저장하세요.\n"
                              + data.substring(data.length() - 24000)
                          : data;
                  TextView text = new TextView(this);
                  text.setText(recent);
                  text.setTextIsSelectable(true);
                  text.setTextColor(TEXT);
                  text.setTextSize(12);
                  text.setPadding(dp(16), dp(12), dp(16), dp(12));
                  ScrollView scroll = new ScrollView(this);
                  scroll.addView(text);
                  new AlertDialog.Builder(this)
                      .setTitle("진단 로그 · 시간 UTC")
                      .setView(scroll)
                      .setPositiveButton("닫기", null)
                      .show();
                }));
  }

  @Override
  protected void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    if (request == 51 && result == RESULT_OK && data != null && data.getData() != null)
      controller.exportDiagnostics(data.getData());
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
