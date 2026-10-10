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
import com.dautolock.app.core.ActivityFeed;
import com.dautolock.app.core.DashboardStatus;
import com.dautolock.app.core.LogDisplay;
import com.dautolock.app.core.ProximityEngine;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

public final class MainActivity extends Activity {
  private static final int BG = 0xff0b0f14,
      CARD = 0xff171c24,
      MINT = 0xff62dca7,
      TEXT = 0xffe9edf3,
      MUTED = 0xff919ba9;
  private static final int QR_REQUEST = 52;
  private Controller controller;
  private LinearLayout body;
  private TextView signal, message, account, device, capabilities, checkedTime;
  private ImageView refreshIcon;
  private LinearLayout chargeCard;
  private TextView chargeHeadline, chargeDetail;
  private ProgressBar chargeBar;
  private android.animation.ObjectAnimator refreshSpin;
  private LinearLayout activityRows;
  private String activityKey = "";
  private View armedCommand;
  private TextView armedText;
  private CharSequence armedDescription;
  private Button cancelStop, stopDelay;
  private Switch stopSwitch;
  private final Runnable stopCountdown = this::update;
  private CharSequence armedLabel;
  private final Runnable disarmCommand = this::disarmCommand;
  private final TextView[] vehicleStates = new TextView[3];
  private TextView signalDetails, autoDetails, autoReason, controlDetails, readyDetails;
  private SignalGauge signalGauge;
  private TextView updateStatus;
  private TextView bridgeStatus;
  private Button monitor, chooseVehicle, logout, login;
  private ScrollView homeScroll, settingsScroll, setupScroll;
  private LinearLayout settingsBody, signalExtra, setupBanner;
  private TextView title, setupTitle, setupInstructions, setupStatus, setupProgress;
  private Button back, signalMore, setupAction, setupNext, setupPrevious;
  private String page = "home";
  private boolean signalExpanded;
  private int setupStep;
  private final Handler refreshHandler = new Handler(Looper.getMainLooper());
  private final Runnable refreshTick =
      new Runnable() {
        public void run() {
          if (!foreground) return;
          controller.refreshStatusWhenNeeded();
          update();
          refreshHandler.postDelayed(this, 5000);
        }
      };
  private Switch auto;
  private boolean updating;
  private final List<View> commands = new ArrayList<>();
  private final List<CloudClient.Command[]> commandNeeds = new ArrayList<>();
  private View titleSpacer, signalDot;
  private ImageView logo, lockIcon;
  private FrameLayout lockTile;
  private TextView doorTitle, batteryText, powerText, windowText, signalDbm, automationReason;
  private Switch automation;
  private LinearLayout securityBanner;
  private TextView securityText;
  private final Runnable observer = this::update;
  private Runnable thresholdPreview;
  private boolean foreground, resumePending;
  private boolean bridgeResumePending;
  private BluetoothLeScanner pickerScanner;
  private ScanCallback pickerCallback;
  private final Handler handler = new Handler(Looper.getMainLooper());

  @Override
  public void onCreate(Bundle saved) {
    super.onCreate(saved);
    controller = ((DApplication) getApplication()).controller();
    build();
    if (saved != null) {
      signalExpanded = saved.getBoolean("signalExpanded");
      setSignalExpanded(signalExpanded);
      showPage(saved.getString("page", "home"));
    }
  }

  @Override
  protected void onStart() {
    super.onStart();
    foreground = true;
    resumePending = true;
    bridgeResumePending = true;
    controller.observe(observer);
    update();
    controller.updater.foreground(true);
    refreshHandler.postDelayed(refreshTick, 750);
  }

  @Override
  protected void onStop() {
    foreground = false;
    refreshHandler.removeCallbacksAndMessages(null);
    handler.removeCallbacks(stopCountdown);
    disarmCommand();
    if (refreshSpin != null) refreshSpin.cancel();
    refreshIcon.animate().cancel();
    refreshIcon.setRotation(0);
    controller.updater.foreground(false);
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
    box.setPadding(dp(14), dp(12), dp(14), dp(14));
    GradientDrawable shape = background(CARD, 18);
    shape.setStroke(dp(1), 0xff252b35);
    box.setBackground(shape);
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
    p.setMargins(0, dp(10), 0, 0);
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
    b.setBackgroundTintList(null);
    b.setBackground(background(0xff242e3d, 12));
    b.setPadding(dp(8), dp(4), dp(8), dp(4));
    b.setOnClickListener(listener);
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(50));
    params.topMargin = dp(6);
    box.addView(b, params);
    return b;
  }

  private void build() {
    LinearLayout shell = new LinearLayout(this);
    shell.setOrientation(LinearLayout.VERTICAL);
    shell.setBackgroundColor(BG);
    LinearLayout bar = new LinearLayout(this);
    bar.setGravity(Gravity.CENTER_VERTICAL);
    bar.setPadding(dp(12), dp(6), dp(12), dp(6));
    shell.addView(bar, new LinearLayout.LayoutParams(-1, dp(60)));
    back = button(bar, "‹", v -> showPage("home"));
    back.setContentDescription("메인 화면으로");
    back.setLayoutParams(new LinearLayout.LayoutParams(dp(44), dp(44)));
    back.setVisibility(View.GONE);
    titleSpacer = new View(this);
    bar.addView(titleSpacer, new LinearLayout.LayoutParams(dp(48), dp(44)));
    titleSpacer.setVisibility(View.GONE);
    LinearLayout brand = new LinearLayout(this);
    brand.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
    bar.addView(brand, new LinearLayout.LayoutParams(0, -1, 1));
    logo = new ImageView(this);
    logo.setImageResource(R.drawable.ic_logo);
    logo.setContentDescription(null);
    brand.addView(logo, new LinearLayout.LayoutParams(dp(34), dp(34)));
    title = text(brand, "D-Autolock", 23, TEXT);
    title.setTypeface(null, Typeface.BOLD);
    title.setPadding(dp(8), 0, 0, 0);
    Button menu =
        button(
            bar,
            "⋮",
            v -> {
              PopupMenu popup = new PopupMenu(this, v);
              popup.getMenu().add(0, 1, 0, "설정");
              popup.getMenu().add(0, 3, 1, "앱 업데이트");
              popup.getMenu().add(0, 2, 2, "처음 설정 안내");
              popup.setOnMenuItemClickListener(
                  item -> {
                    if (item.getItemId() == 1) showPage("settings");
                    else if (item.getItemId() == 3) requestUpdate();
                    else showSetup();
                    return true;
                  });
              popup.show();
            });
    menu.setTag("overflowMenu");
    menu.setContentDescription("메뉴");
    menu.setTextSize(24);
    menu.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(44)));
    FrameLayout pages = new FrameLayout(this);
    shell.addView(pages, new LinearLayout.LayoutParams(-1, 0, 1));
    homeScroll = new ScrollView(this);
    homeScroll.setTag("homePage");
    homeScroll.setFillViewport(true);
    body = new LinearLayout(this);
    body.setOrientation(LinearLayout.VERTICAL);
    body.setPadding(dp(12), 0, dp(12), dp(16));
    homeScroll.addView(body);
    pages.addView(homeScroll);
    settingsScroll = new ScrollView(this);
    settingsScroll.setTag("settingsPage");
    settingsBody = new LinearLayout(this);
    settingsBody.setOrientation(LinearLayout.VERTICAL);
    settingsBody.setPadding(dp(12), 0, dp(12), dp(24));
    settingsScroll.addView(settingsBody);
    pages.addView(settingsScroll);
    settingsScroll.setVisibility(View.GONE);
    setupScroll = new ScrollView(this);
    setupScroll.setTag("setupPage");
    pages.addView(setupScroll);
    setupScroll.setVisibility(View.GONE);
    setContentView(shell);
    buildStatusAndSignal();
    LinearLayout bridgeCard = card("차량 보조 앱 연결 / QR");
    bridgeStatus = text(bridgeCard, "차량 보조 앱 미등록", 13, MUTED);
    button(bridgeCard, "차량 보조 앱 연결 / QR", v -> bridgeDialog());
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
                  controller.startMonitoring(
                      controller.settings.getBoolean("autoStart", true), false);
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
                      + controller.settings.getInt("lossLockSeconds", Controller.DEFAULT_LOSS)
                      + "초 끊기면 잠금을 요청합니다. 다른 탑승자와 키의 위치를"
                      + " 확인하세요.")
              .setNegativeButton("취소", null)
              .setPositiveButton("자동 제어 켜기", (d, w) -> controller.auto(true))
              .show();
        });
    capabilities = text(bridgeCard, "차량 기능 확인 전", 12, MUTED);
    buildControlsAutomationLog();
    LinearLayout options = card("자동 동작 설정");
    Switch startSwitch = new Switch(this);
    startSwitch.setText("앱 실행·감도 저장 시 자동 제어 시작");
    startSwitch.setTextColor(TEXT);
    startSwitch.setMinHeight(dp(56));
    startSwitch.setChecked(controller.settings.getBoolean("autoStart", true));
    options.addView(startSwitch);
    startSwitch.setOnCheckedChangeListener(
        (b, on) -> {
          controller.settings.edit().putBoolean("autoStart", on).apply();
          if (on) {
            resumePending = true;
            tryAutoResume();
          }
        });
    Switch readySwitch = new Switch(this);
    readySwitch.setText("탑승 시 공조 시작");
    readySwitch.setTextColor(TEXT);
    readySwitch.setPadding(0, dp(12), 0, dp(12));
    readySwitch.setMinHeight(dp(56));
    readySwitch.setChecked(controller.settings.getBoolean("autoReady", false));
    options.addView(readySwitch);
    readySwitch.setOnCheckedChangeListener((b, on) -> controller.readyOption(on));
    Switch climateOffSwitch = new Switch(this);
    climateOffSwitch.setTag("climateAutoOff");
    climateOffSwitch.setText("탑승 공조 시작 후 자동 끄기");
    climateOffSwitch.setTextColor(TEXT);
    climateOffSwitch.setPadding(0, dp(12), 0, dp(12));
    climateOffSwitch.setMinHeight(dp(56));
    climateOffSwitch.setChecked(controller.settings.getBoolean("climateAutoOff", false));
    options.addView(climateOffSwitch);
    climateOffSwitch.setOnCheckedChangeListener(
        (b, on) -> {
          if (updating) return;
          if (!on) {
            controller.settings.edit().putBoolean("climateAutoOff", false).apply();
            controller.note("탑승 공조 자동 끄기 꺼짐");
            return;
          }
          AlertDialog confirm =
              new AlertDialog.Builder(this)
                  .setTitle("탑승 공조 자동 끄기")
                  .setMessage(
                      "탑승 시 공조 시작은 차량 전원을 켜는 용도입니다. 시작 후 "
                          + controller.climateOffSeconds()
                          + "초 뒤 공조를 한 번 끕니다(수동 '시동 켜기'·'출차 준비'에는 적용되지 않음).\n\n"
                          + "주의: 너무 빨리 끄면 차량 전원도 함께 꺼질 수 있습니다(과거 2초에서 관찰). 5초 이상에서는 전원이"
                          + " 유지된 기록이 있으나 실차에서 직접 확인하세요.")
                  .setNegativeButton("취소", null)
                  .setPositiveButton(
                      "켜기",
                      (d, w) -> {
                        controller.settings.edit().putBoolean("climateAutoOff", true).apply();
                        controller.note(
                            "탑승 공조 자동 끄기 켜짐 · " + controller.climateOffSeconds() + "초 뒤 OFF");
                      })
                  // Sync the switch to the saved value whether confirmed, cancelled or dismissed.
                  .setOnDismissListener(
                      d -> {
                        updating = true;
                        climateOffSwitch.setChecked(
                            controller.settings.getBoolean("climateAutoOff", false));
                        updating = false;
                      })
                  .show();
        });
    button(options, "탑승 공조 자동 끄기 시간", v -> climateOffDelayDialog());
    Switch windowsSwitch = new Switch(this);
    windowsSwitch.setText("도어 잠금 시 전체 창문 닫기");
    windowsSwitch.setTextColor(TEXT);
    windowsSwitch.setPadding(0, dp(12), 0, dp(12));
    windowsSwitch.setMinHeight(dp(56));
    windowsSwitch.setChecked(controller.settings.getBoolean("closeWindows", true));
    options.addView(windowsSwitch);
    windowsSwitch.setOnCheckedChangeListener(
        (b, on) -> {
          controller.settings.edit().putBoolean("closeWindows", on).apply();
          controller.note(on ? "도어 잠금 시 전체 창문 닫기 켜짐" : "창문 닫기 연동 꺼짐");
        });
    Switch stopSwitch = new Switch(this);
    stopSwitch.setText("자동 잠금 후 차량 전원 종료");
    stopSwitch.setTextColor(TEXT);
    stopSwitch.setMinHeight(dp(56));
    stopSwitch.setChecked(controller.settings.getBoolean("autoStop", true));
    options.addView(stopSwitch);
    stopSwitch.setOnCheckedChangeListener(
        (b, on) -> controller.settings.edit().putBoolean("autoStop", on).apply());
    this.stopSwitch = stopSwitch;
    Switch sharedSwitch = new Switch(this);
    sharedSwitch.setTag("sharedVehicle");
    sharedSwitch.setText("공유 차량 모드 · 여러 운전자 사용");
    sharedSwitch.setTextColor(TEXT);
    sharedSwitch.setMinHeight(dp(56));
    sharedSwitch.setChecked(controller.sharedVehicle());
    options.addView(sharedSwitch);
    sharedSwitch.setOnCheckedChangeListener(
        (b, on) -> {
          controller.settings.edit().putBoolean("sharedVehicle", on).apply();
          if (on) controller.cancelPendingStop("공유 차량 모드 켜짐");
          controller.note(
              on ? "공유 차량 모드 켜짐 · 자동 잠금은 유지, 자동 종료는 하지 않습니다" : "공유 차량 모드 꺼짐");
        });
    stopDelay = button(options, "", v -> stopDelayDialog());
    stopDelay.setTag("stopDelay");
    text(
        options,
        "자동 종료 조건: 차량 보조 앱의 실제 P단 2초 연속 확인(필수), 이 휴대폰이 연 운행이며 차량 보조 앱에서 주행(P 해제)을 확인한 경우,"
            + " 신호 세기로 이탈 확인, 최신 정차·잠금·모든 문 닫힘. 잠금 후 대기 시간 동안 기어·브레이크 변화나 취소가 있으면 종료하지 않고,"
            + " 대기 후 다시 확인합니다. 다른 사람이 열었거나 앱이 운행 중 재시작되면 종료하지 않습니다.\n"
            + "공유 차량 모드에서는 자동 잠금·창문 닫기만 하고 자동 종료는 하지 않습니다.",
        13,
        MUTED);
    text(options, "탑승 공조 · READY 상태 진단", 16, 0xffffc77d);
    text(
        options,
        "자동 해제 후 90초 안에 문 닫힘→열림을 확인하면 공조를 시작합니다. 기존 2초 후 OFF는 제거했습니다. 종료는 차량에서 직접 조작하며 차량의 원격 공조 시간"
            + " 제한이 적용됩니다. 주차브레이크 미제공 시 최신 전원 OFF·속도 0에서만 시작합니다. 전원 ON과 READY는 다르며, OK 표시값이 없으면"
            + " 미확인으로 표시합니다.",
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
    button(
        bluetooth,
        "BLE 다시 검색",
        v -> {
          if (controller.monitoring)
            startForegroundService(new Intent(this, ProximityService.class).setAction("RESCAN"));
          else {
            resumePending = true;
            tryAutoResume();
          }
        });
    button(
        bluetooth,
        "배터리 · 백그라운드 설정",
        v ->
            startActivity(
                new Intent(
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:" + getPackageName()))));
    text(
        bluetooth,
        "블루투스는 거리 감지용입니다. 도어 제어에는 휴대폰과 차량의 인터넷 연결이 필요합니다. 전원을 끄면 신호가 사라지는 오디오 기기는 사용할 수 없습니다.",
        13,
        MUTED);
    LinearLayout events = card("알림 · 진단 로그 관리");
    button(
        events,
        "휴대폰 알림 설정",
        v -> {
          if (Build.VERSION.SDK_INT >= 33
              && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                  != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 21);
          else
            startActivity(
                new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, getPackageName()));
        });
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
    LinearLayout updates = card("앱 업데이트");
    updateStatus = text(updates, controller.updater.status, 13, MUTED);
    Switch updateSwitch = new Switch(this);
    updateSwitch.setText("앱 실행 시 새 버전 확인·설치 화면 열기");
    updateSwitch.setTextColor(TEXT);
    updateSwitch.setMinHeight(dp(48));
    updateSwitch.setChecked(controller.settings.getBoolean("autoUpdate", true));
    updates.addView(updateSwitch);
    updateSwitch.setOnCheckedChangeListener(
        (b, on) -> {
          controller.settings.edit().putBoolean("autoUpdate", on).apply();
          if (on) controller.updater.check(false);
        });
    button(updates, "지금 업데이트", v -> requestUpdate());
    text(
        updates,
        "앱을 열 때 GitHub 새 버전을 확인합니다(5분 간격). 다운로드·서명 검증이 끝나면 Android 설치 화면을 바로 엽니다. 계정과 설정은 유지됩니다.",
        12,
        MUTED);
    for (LinearLayout section :
        new LinearLayout[] {
          bridgeCard, proximity, options, updates, link, bluetooth, events
        }) {
      body.removeView(section);
      settingsBody.addView(section);
    }
    button(settingsBody, "처음 설정 안내", v -> showSetup());
    button(settingsBody, "사용 안내 · 오픈소스", v -> about());
    text(settingsBody, "D-Autolock 0.3.17 · 비공식 개인용 앱", 12, MUTED);
    setupBanner = new LinearLayout(this);
    setupBanner.setOrientation(LinearLayout.VERTICAL);
    button(setupBanner, "처음 설정 이어하기", v -> showSetup());
    body.addView(setupBanner);
    setupBanner.setVisibility(View.GONE);
    buildSetup();
    styleSwitches(shell);
  }

  private void climateOffDelayDialog() {
    LinearLayout f = form();
    text(f, "탑승 시 공조 시작 후 공조를 끄기까지의 시간입니다. 너무 짧으면 차량 전원이 함께 꺼질 수 있어 5초 이상을 권장합니다.", 13, MUTED);
    SeekBar delay =
        settingSlider(
            f, "자동 끄기 시간", "climateOffSeconds", 5, 120, controller.climateOffSeconds(), "초",
            "5초 ← → 120초", value -> {});
    new AlertDialog.Builder(this)
        .setTitle("탑승 공조 자동 끄기 시간")
        .setView(f)
        .setNegativeButton("취소", null)
        .setNeutralButton(
            "기본값 10초",
            (d, w) -> {
              controller.settings.edit().putInt("climateOffSeconds", 10).apply();
              controller.note("탑승 공조 자동 끄기 10초");
            })
        .setPositiveButton(
            "저장",
            (d, w) -> {
              int seconds = delay.getProgress() + 5;
              controller.settings.edit().putInt("climateOffSeconds", seconds).apply();
              controller.note("탑승 공조 자동 끄기 " + seconds + "초");
            })
        .show();
  }

  private void stopDelayDialog() {
    LinearLayout f = form();
    text(f, "잠금 후 이 시간 동안 기어·브레이크 변화와 취소를 기다린 뒤 P단·정차·잠금을 다시 확인하고 종료합니다.", 13, MUTED);
    SeekBar delay =
        settingSlider(
            f, "자동 종료 대기", "stopDelaySeconds", 5, 120, controller.stopDelaySeconds(), "초",
            "5초 ← → 120초", value -> {});
    new AlertDialog.Builder(this)
        .setTitle("자동 종료 대기 시간")
        .setView(f)
        .setNegativeButton("취소", null)
        .setNeutralButton(
            "기본값 15초",
            (d, w) -> {
              controller.settings.edit().putInt("stopDelaySeconds", 15).apply();
              controller.note("자동 종료 대기 시간 15초");
            })
        .setPositiveButton(
            "저장",
            (d, w) -> {
              int seconds = delay.getProgress() + 5;
              controller.settings.edit().putInt("stopDelaySeconds", seconds).apply();
              controller.note("자동 종료 대기 시간 " + seconds + "초");
            })
        .show();
  }

  /** System screens (settings, Bluetooth, installer) may be missing or blocked: never crash. */
  @Override
  public void startActivity(Intent intent, Bundle options) {
    try {
      super.startActivity(intent, options);
    } catch (ActivityNotFoundException | SecurityException e) {
      controller.diagnostics.record("ACTIVITY_START_ERROR", e.getClass().getSimpleName());
      controller.note("이 휴대폰에서 해당 화면을 열 수 없습니다");
    }
  }

  @Override
  public void startActivityForResult(Intent intent, int request, Bundle options) {
    try {
      super.startActivityForResult(intent, request, options);
    } catch (ActivityNotFoundException | SecurityException e) {
      controller.diagnostics.record("ACTIVITY_START_ERROR", e.getClass().getSimpleName());
      controller.note("이 휴대폰에서 해당 화면을 열 수 없습니다");
    }
  }

  private void requestUpdate() {
    Toast.makeText(this, "앱 업데이트 확인 중…", Toast.LENGTH_SHORT).show();
    controller.updater.request();
  }

  /** Vehicle controls run only on a second tap within 3 seconds, so a stray touch does nothing. */
  private View.OnClickListener doubleTap(TextView label, String hint, Runnable action) {
    return v -> {
      if (armedCommand == v) {
        disarmCommand();
        action.run();
        return;
      }
      disarmCommand();
      armedCommand = v;
      armedText = label;
      armedLabel = label.getText();
      armedDescription = v.getContentDescription();
      label.setText(hint);
      v.setContentDescription(armedDescription + ". 3초 안에 한 번 더 누르면 실행합니다");
      GradientDrawable ring = new GradientDrawable();
      ring.setCornerRadius(dp(14));
      ring.setStroke(dp(2), 0xffffffff);
      v.setForeground(ring);
      v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
      handler.postDelayed(disarmCommand, 3000);
    };
  }

  private void disarmCommand() {
    handler.removeCallbacks(disarmCommand);
    if (armedCommand == null) return;
    armedText.setText(armedLabel);
    armedCommand.setContentDescription(armedDescription);
    armedCommand.setForeground(null);
    armedCommand = null;
  }

  private void setTreeEnabled(View view, boolean enabled) {
    view.setEnabled(enabled);
    if (view instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
        setTreeEnabled(((ViewGroup) view).getChildAt(i), enabled);
  }

  private ImageView icon(LinearLayout parent, int drawable, int color, int size) {
    ImageView image = new ImageView(this);
    image.setImageResource(drawable);
    image.setColorFilter(color);
    image.setScaleType(ImageView.ScaleType.FIT_CENTER);
    parent.addView(image, new LinearLayout.LayoutParams(dp(size), dp(size)));
    return image;
  }

  private ImageView iconButton(
      LinearLayout parent, int drawable, String description, String tag, View.OnClickListener click) {
    ImageView image = new ImageView(this);
    image.setImageResource(drawable);
    image.setColorFilter(MUTED);
    image.setScaleType(ImageView.ScaleType.CENTER);
    image.setPadding(dp(8), dp(8), dp(8), dp(8));
    image.setContentDescription(description);
    image.setTag(tag);
    image.setOnClickListener(click);
    image.setBackground(background(0x00000000, 18));
    parent.addView(image, new LinearLayout.LayoutParams(dp(38), dp(38)));
    return image;
  }

  /** Replaces a card's plain title with a row: title on the left, the returned row for extras. */
  private LinearLayout cardHeader(LinearLayout card) {
    View title = card.getChildAt(0);
    card.removeView(title);
    LinearLayout header = new LinearLayout(this);
    header.setGravity(Gravity.CENTER_VERTICAL);
    card.addView(header, 0);
    header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
    return header;
  }

  private LinearLayout statusItem(LinearLayout row, int drawable, int color, String tag) {
    LinearLayout item = new LinearLayout(this);
    item.setOrientation(LinearLayout.VERTICAL);
    item.setGravity(Gravity.CENTER);
    item.setTag(tag);
    icon(item, drawable, color, 20);
    row.addView(item, new LinearLayout.LayoutParams(dp(44), -2));
    return item;
  }

  private void buildStatusAndSignal() {
    LinearLayout status = card("");
    status.removeViewAt(0);
    status.setTag("alertsCard");
    status.setPadding(dp(12), dp(12), dp(8), dp(12));
    LinearLayout row = new LinearLayout(this);
    row.setGravity(Gravity.CENTER_VERTICAL);
    status.addView(row);
    lockTile = new FrameLayout(this);
    lockTile.setTag("doorTile");
    lockIcon = new ImageView(this);
    lockTile.addView(lockIcon, new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER));
    row.addView(lockTile, new LinearLayout.LayoutParams(dp(44), dp(44)));
    LinearLayout titles = new LinearLayout(this);
    titles.setOrientation(LinearLayout.VERTICAL);
    titles.setPadding(dp(10), 0, dp(4), 0);
    row.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
    doorTitle = text(titles, "도어 미확인", 17, TEXT);
    doorTitle.setTag("doorTitle");
    doorTitle.setTypeface(null, Typeface.BOLD);
    doorTitle.setPadding(0, 0, 0, 0);
    doorTitle.setSingleLine(true);
    checkedTime = text(titles, "업데이트 대기", 11, MUTED);
    checkedTime.setTag("statusCheckedTime");
    checkedTime.setPadding(0, dp(2), 0, 0);
    checkedTime.setMaxLines(2); // Never cut the time off; wrap on narrow phones.
    batteryText = text(statusItem(row, R.drawable.ic_battery, MINT, "batteryItem"), "--%", 12, TEXT);
    powerText = text(statusItem(row, R.drawable.ic_power, 0xff66cce0, "powerItem"), "미확인", 12, TEXT);
    windowText = text(statusItem(row, R.drawable.ic_window, 0xff8fb4ff, "windowItem"), "미확인", 12, TEXT);
    for (TextView t : new TextView[] {batteryText, powerText, windowText}) {
      t.setPadding(0, dp(2), 0, 0);
      t.setMaxLines(1);
      t.setGravity(Gravity.CENTER);
      t.setLayoutParams(new LinearLayout.LayoutParams(-2, -2));
    }
    refreshIcon = new ImageView(this);
    refreshIcon.setTag("statusRefresh");
    refreshIcon.setImageResource(R.drawable.ic_refresh);
    refreshIcon.setScaleType(ImageView.ScaleType.CENTER);
    refreshIcon.setContentDescription("차량 상태 새로고침");
    refreshIcon.setOnClickListener(
        v -> {
          v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
          controller.refreshNow();
          update();
        });
    row.addView(refreshIcon, new LinearLayout.LayoutParams(dp(34), dp(44)));
    message = text(status, "", 12, MUTED); // Recent alert text lives in the activity log now.
    message.setVisibility(View.GONE);
    status.setOnClickListener(v -> showVehicleDetails());
    chargeCard = card("충전 상태");
    chargeCard.setTag("chargeCard");
    body.removeView(chargeCard);
    body.addView(chargeCard, 0); // Above the status bar, only while charging.
    chargeHeadline = text(chargeCard, "", 20, TEXT);
    chargeHeadline.setTypeface(null, Typeface.BOLD);
    chargeHeadline.setTag("chargeHeadline");
    chargeBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
    chargeBar.setMax(100);
    chargeBar.setProgressTintList(android.content.res.ColorStateList.valueOf(MINT));
    chargeBar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0xff2a3340));
    chargeCard.addView(chargeBar, new LinearLayout.LayoutParams(-1, dp(14)));
    chargeDetail = text(chargeCard, "", 14, TEXT);
    chargeDetail.setTag("chargeDetail");
    chargeCard.setVisibility(View.GONE);
    securityBanner = new LinearLayout(this);
    securityBanner.setTag("securityAlert");
    securityBanner.setGravity(Gravity.CENTER_VERTICAL);
    securityBanner.setPadding(dp(12), dp(10), dp(12), dp(10));
    GradientDrawable alarm = background(0xff3a1416, 16);
    alarm.setStroke(dp(1), DoorNotifications.ALERT_RED);
    securityBanner.setBackground(alarm);
    icon(securityBanner, R.drawable.ic_warning, DoorNotifications.ALERT_RED, 26);
    securityText = new TextView(this);
    securityText.setTextColor(0xffff6b6b);
    securityText.setTextSize(14);
    securityText.setTypeface(null, Typeface.BOLD);
    securityText.setPadding(dp(10), 0, 0, 0);
    securityBanner.addView(securityText, new LinearLayout.LayoutParams(0, -2, 1));
    securityBanner.setOnClickListener(v -> finishConfirm());
    LinearLayout.LayoutParams bannerParams = new LinearLayout.LayoutParams(-1, -2);
    bannerParams.setMargins(0, dp(10), 0, 0);
    body.addView(securityBanner, 0, bannerParams);
    securityBanner.setVisibility(View.GONE);

    LinearLayout dash = card("BLE 신호 상태");
    dash.setTag("signalCard");
    LinearLayout header = cardHeader(dash);
    iconButton(header, R.drawable.ic_tune, "거리 감도 / 대기 시간", "thresholdEdit", v -> thresholdDialog());
    signalDot = new View(this);
    signalDot.setTag("signalDot");
    LinearLayout.LayoutParams dot = new LinearLayout.LayoutParams(dp(9), dp(9));
    dot.setMargins(dp(4), 0, dp(4), 0);
    header.addView(signalDot, dot);
    LinearLayout reading = new LinearLayout(this);
    reading.setGravity(Gravity.CENTER_VERTICAL);
    dash.addView(reading);
    signal = text(reading, "신호 대기", 16, MINT);
    signal.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
    signalDbm = text(reading, "— dBm", 14, MUTED);
    signalDbm.setTag("signalDbm");
    signalGauge = new SignalGauge(this);
    dash.addView(signalGauge, new LinearLayout.LayoutParams(-1, dp(46)));
    LinearLayout signalRow = new LinearLayout(this);
    signalRow.setGravity(Gravity.CENTER_VERTICAL);
    dash.addView(signalRow);
    autoDetails = text(signalRow, "자동 도어 OFF", 13, MINT);
    autoDetails.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
    signalMore = button(signalRow, "＋", v -> setSignalExpanded(!signalExpanded));
    signalMore.setTag("signalMore");
    signalMore.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(40)));
    signalExtra = new LinearLayout(this);
    signalExtra.setTag("signalExtra");
    signalExtra.setOrientation(LinearLayout.VERTICAL);
    dash.addView(signalExtra);
    text(signalExtra, "주황: 잠금 기준 / 초록: 열기 기준\n신호 세기는 실제 거리와 다를 수 있습니다.", 12, MUTED);
    signalDetails = text(signalExtra, "", 12, MUTED);
    controlDetails = text(signalExtra, "", 12, MUTED);
    readyDetails = text(signalExtra, "", 12, MUTED);
    setSignalExpanded(false);
  }

  private LinearLayout bigTile(
      LinearLayout row, int drawable, String title, String sub, int[] colors, String tag,
      CloudClient.Command[] needs, Runnable action) {
    LinearLayout tile = new LinearLayout(this);
    tile.setTag(tag);
    tile.setGravity(Gravity.CENTER_VERTICAL);
    tile.setPadding(dp(12), dp(8), dp(8), dp(8));
    GradientDrawable gradient = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, colors);
    gradient.setCornerRadius(dp(14));
    tile.setBackground(gradient);
    tile.setClickable(true);
    tile.setContentDescription(title + " · " + sub);
    icon(tile, drawable, 0xffffffff, 28);
    LinearLayout texts = new LinearLayout(this);
    texts.setOrientation(LinearLayout.VERTICAL);
    texts.setPadding(dp(10), 0, 0, 0);
    tile.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
    TextView name = text(texts, title, 16, 0xffffffff);
    name.setTypeface(null, Typeface.BOLD);
    name.setPadding(0, 0, 0, 0);
    TextView detail = text(texts, sub, 12, 0xddffffff);
    detail.setPadding(0, dp(1), 0, 0);
    tile.setOnClickListener(doubleTap(detail, "한 번 더 누르세요", action));
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(66), 1);
    p.setMargins(row.getChildCount() == 0 ? 0 : dp(4), 0, row.getChildCount() == 0 ? dp(4) : 0, 0);
    row.addView(tile, p);
    commands.add(tile);
    commandNeeds.add(needs);
    return tile;
  }

  private LinearLayout smallTile(
      LinearLayout row, int drawable, String label, int color, int fill, String tag,
      CloudClient.Command[] needs, Runnable action) {
    LinearLayout tile = new LinearLayout(this);
    tile.setTag(tag);
    tile.setOrientation(LinearLayout.VERTICAL);
    tile.setGravity(Gravity.CENTER);
    GradientDrawable shape = background(fill, 12);
    shape.setStroke(dp(1), (color & 0x00ffffff) | 0x55000000);
    tile.setBackground(shape);
    tile.setClickable(true);
    tile.setContentDescription(label);
    icon(tile, drawable, color, 22);
    TextView name = text(tile, label, 12, color);
    name.setPadding(0, dp(4), 0, 0);
    name.setMaxLines(1); // setSingleLine + centered gravity scrolls the text out of view.
    name.setLayoutParams(new LinearLayout.LayoutParams(-2, -2));
    tile.setOnClickListener(doubleTap(name, "한 번 더", action));
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(62), 1);
    p.setMargins(row.getChildCount() == 0 ? 0 : dp(3), dp(8), 0, 0);
    row.addView(tile, p);
    commands.add(tile);
    commandNeeds.add(needs);
    return tile;
  }

  private void climateConfirm(String title, String action, Runnable run) {
    new AlertDialog.Builder(this)
        .setTitle(title)
        .setMessage(
            action
                + "P단 주차를 확인하세요. 23°C 원격 공조를 한 번 요청해 차량 전원을 켭니다(자동 OFF 없음, 차량의 원격 공조 시간 제한 적용)."
                + " 원격 공조는 주행 READY가 아니므로 출발 전 계기판을 확인하세요.")
        .setNegativeButton("취소", null)
        .setPositiveButton("주차 확인 · 실행", (d, w) -> run.run())
        .show();
  }

  private void finishConfirm() {
    new AlertDialog.Builder(this)
        .setTitle("하차 마무리")
        .setMessage(
            "차량이 P단이며 주차브레이크가 체결됐고 주변과 탑승자가 안전한지 직접 확인하세요. 최신 정차 상태를 조회해 시동을 한 번 끄고(Stop),"
                + " 전원 OFF가 확인되면 도어를 잠급니다. 확인은 이번 요청에만 30초간 유효하며, 시동 꺼짐이 확인되지 않으면 잠그지 않습니다.")
        .setNegativeButton("취소", null)
        .setPositiveButton("P단·주차브레이크 확인 · 실행", (d, w) -> controller.departureFinish())
        .show();
  }

  private void buildControlsAutomationLog() {
    LinearLayout controls = card("차량 제어");
    controls.setTag("controlsCard");
    LinearLayout bigRow = new LinearLayout(this);
    bigRow.setBaselineAligned(false);
    controls.addView(bigRow);
    bigTile(
        bigRow, R.drawable.ic_car, "출차 준비", "해제+시동", new int[] {0xff3f7be8, 0xff55bdce},
        "control_prepare",
        new CloudClient.Command[] {CloudClient.Command.UNLOCK, CloudClient.Command.CLIMATE_ON},
        () -> climateConfirm("출차 준비", "도어를 연 뒤 열림이 확인되면 시동(원격 공조)을 켭니다. ", controller::departurePrepare));
    bigTile(
        bigRow, R.drawable.ic_walk, "하차 마무리", "시동끔+잠금", new int[] {0xff5874f3, 0xff7d6cf0},
        "control_finish",
        new CloudClient.Command[] {CloudClient.Command.STOP, CloudClient.Command.LOCK},
        this::finishConfirm);
    LinearLayout smallRow = new LinearLayout(this);
    smallRow.setBaselineAligned(false);
    controls.addView(smallRow);
    smallTile(
        smallRow, R.drawable.ic_lock_open, "도어 열기", 0xff62dca7, 0xff1b3029, "control_unlock",
        new CloudClient.Command[] {CloudClient.Command.UNLOCK},
        () -> controller.command(CloudClient.Command.UNLOCK, false, () -> true));
    smallTile(
        smallRow, R.drawable.ic_lock, "도어 잠금", 0xff8fa8ff, 0xff1e2747, "control_lock",
        new CloudClient.Command[] {CloudClient.Command.LOCK},
        () -> controller.command(CloudClient.Command.LOCK, false, () -> true));
    smallTile(
        smallRow, R.drawable.ic_power, "시동 켜기", 0xff66cce0, 0xff1b343b, "control_power_on",
        new CloudClient.Command[] {CloudClient.Command.CLIMATE_ON},
        () -> climateConfirm("시동 켜기 · 원격 공조", "", controller::manualClimateStart));
    smallTile(
        smallRow, R.drawable.ic_power_off, "시동 끄기", 0xffddaa67, 0xff35281f, "control_power_off",
        new CloudClient.Command[] {CloudClient.Command.STOP},
        () -> confirm(CloudClient.Command.STOP));
    cancelStop = button(controls, "", v -> controller.cancelPendingStop("앱에서 사용자 취소"));
    cancelStop.setTag("cancelStop");
    cancelStop.setTextColor(0xffffc77d);
    cancelStop.setBackground(background(0xff4a2f22, 12));
    cancelStop.setVisibility(View.GONE);
    text(controls, "실수 방지: 3초 안에 두 번 누르면 실행합니다.", 12, MUTED);

    LinearLayout automationCard = card("자동화");
    automationCard.setTag("automationCard");
    LinearLayout row = new LinearLayout(this);
    row.setGravity(Gravity.CENTER_VERTICAL);
    automationCard.addView(row);
    LinearLayout texts = new LinearLayout(this);
    texts.setOrientation(LinearLayout.VERTICAL);
    row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
    TextView name = text(texts, "자동화 켜기", 17, TEXT);
    name.setTypeface(null, Typeface.BOLD);
    name.setPadding(0, 0, 0, 0);
    text(texts, "가까우면 해제 · 멀어지면 잠금", 12, MUTED).setPadding(0, dp(2), 0, 0);
    automation = new Switch(this);
    automation.setTag("automationSwitch");
    automation.setContentDescription("자동화 켜기");
    automation.setMinHeight(dp(48));
    row.addView(automation);
    automationReason = text(automationCard, "", 12, MUTED);
    automationReason.setTag("automationReason");
    automation.setOnCheckedChangeListener(
        (b, on) -> {
          if (updating) return;
          if (!on) {
            controller.automationOff();
            update();
            return;
          }
          if (!controller.setupReady()) {
            controller.note("계정·차량·제어 PIN·BYD BLE 기기를 먼저 설정하세요");
            update();
            return;
          }
          if (!permissions(true)) {
            update();
            return;
          }
          controller.settings.edit().putBoolean("autoStart", true).apply();
          stopPicker();
          if (controller.monitoring) controller.auto(true);
          else controller.startMonitoring(true, false);
          controller.note("자동화 켜짐 · 가까우면 해제 · 멀어지면 잠금");
        });

    LinearLayout activityLog = card("활동 로그");
    activityLog.setTag("logCard");
    LinearLayout logHeader = cardHeader(activityLog);
    iconButton(logHeader, R.drawable.ic_open_full, "활동 전체 보기", "activityAll", v -> showActivity());
    iconButton(logHeader, R.drawable.ic_document, "상세 진단 로그", "diagnosticsOpen", v -> showDiagnostics());
    activityRows = new LinearLayout(this);
    activityRows.setTag("activityRows");
    activityRows.setOrientation(LinearLayout.VERTICAL);
    activityLog.addView(activityRows);
  }

  /** Spins while a status read runs; on finish completes the current turn instead of snapping. */
  private void spin(boolean reading) {
    boolean running = refreshSpin != null && refreshSpin.isRunning();
    refreshIcon.setContentDescription(reading ? "차량 상태 새로고침 중" : "차량 상태 새로고침");
    if (reading && !running) {
      refreshIcon.animate().cancel();
      refreshIcon.setRotation(0);
      refreshSpin = android.animation.ObjectAnimator.ofFloat(refreshIcon, "rotation", 0f, 360f);
      refreshSpin.setDuration(900);
      refreshSpin.setRepeatCount(android.animation.ValueAnimator.INFINITE);
      refreshSpin.setInterpolator(new android.view.animation.LinearInterpolator());
      refreshSpin.start();
    } else if (!reading && running) {
      refreshSpin.cancel();
      float rotation = refreshIcon.getRotation() % 360f;
      refreshIcon
          .animate()
          .rotation(360f)
          .setDuration((long) ((360f - rotation) / 360f * 900))
          .setInterpolator(new android.view.animation.DecelerateInterpolator())
          .withEndAction(() -> refreshIcon.setRotation(0))
          .start();
    }
  }

  private static int activityColor(ActivityFeed.Kind kind) {
    switch (kind) {
      case SUCCESS:
        return 0xff62dca7;
      case FAILURE:
        return 0xffff7b7b;
      case WAIT:
        return 0xffffc77d;
      default:
        return 0xff8fb4ff;
    }
  }

  private void addActivityRow(LinearLayout parent, ActivityFeed.Entry e, long now) {
    ActivityFeed.Kind kind = ActivityFeed.kind(e.text);
    int color = activityColor(kind);
    LinearLayout row = new LinearLayout(this);
    row.setPadding(0, dp(7), 0, dp(7));
    TextView badge = new TextView(this);
    badge.setText(kind.label);
    badge.setTextSize(11);
    badge.setTextColor(color);
    badge.setTypeface(null, Typeface.BOLD);
    badge.setGravity(Gravity.CENTER);
    GradientDrawable pill = background((color & 0x00ffffff) | 0x26000000, 10);
    badge.setBackground(pill);
    badge.setPadding(dp(6), dp(3), dp(6), dp(3));
    LinearLayout.LayoutParams badgeParams = new LinearLayout.LayoutParams(dp(42), -2);
    badgeParams.setMargins(0, dp(2), dp(10), 0);
    row.addView(badge, badgeParams);
    LinearLayout lines = new LinearLayout(this);
    lines.setOrientation(LinearLayout.VERTICAL);
    row.addView(lines, new LinearLayout.LayoutParams(0, -2, 1));
    TextView what = new TextView(this);
    what.setText(ActivityFeed.friendly(e.text) + (e.count > 1 ? "  (×" + e.count + ")" : ""));
    what.setTextColor(TEXT);
    what.setTextSize(13);
    lines.addView(what);
    TextView when = new TextView(this);
    when.setText(ActivityFeed.time(e.time, now));
    when.setTextColor(MUTED);
    when.setTextSize(11);
    lines.addView(when);
    parent.addView(row);
  }

  private void renderActivity(long now) {
    List<ActivityFeed.Entry> rows = ActivityFeed.collapse(controller.activity(), 5);
    StringBuilder key = new StringBuilder().append(now / 30000);
    for (ActivityFeed.Entry e : rows) key.append('|').append(e.time).append(e.count);
    if (key.toString().equals(activityKey)) return;
    activityKey = key.toString();
    activityRows.removeAllViews();
    if (rows.isEmpty()) text(activityRows, "아직 활동 기록이 없습니다.", 13, MUTED);
    for (ActivityFeed.Entry e : rows) addActivityRow(activityRows, e, now);
  }

  private void showActivity() {
    long now = System.currentTimeMillis();
    LinearLayout list = form();
    list.setPadding(dp(18), dp(8), dp(18), dp(8));
    text(list, "최근 30건 · 한국 시간 · 최신순. 판단 근거와 차량 응답은 상세 진단 로그에 있습니다.", 12, MUTED);
    List<ActivityFeed.Entry> rows = ActivityFeed.collapse(controller.activity(), 30);
    if (rows.isEmpty()) text(list, "아직 활동 기록이 없습니다.", 13, MUTED);
    for (ActivityFeed.Entry e : rows) addActivityRow(list, e, now);
    ScrollView scroll = new ScrollView(this);
    scroll.setTag("activityList");
    scroll.addView(list);
    new AlertDialog.Builder(this)
        .setTitle("활동 로그")
        .setView(scroll)
        .setNeutralButton("상세 진단 로그", (d, w) -> showDiagnostics())
        .setPositiveButton("닫기", null)
        .show();
  }

  private void setSignalExpanded(boolean expanded) {
    signalExpanded = expanded;
    signalExtra.setVisibility(expanded ? View.VISIBLE : View.GONE);
    signalMore.setText(expanded ? "−" : "＋");
    signalMore.setContentDescription(expanded ? "BLE 상세 접기" : "BLE 상세 펼치기");
  }

  private void showVehicleDetails() {
    new AlertDialog.Builder(this)
        .setTitle("차량 상태 · 자세히")
        .setMessage(
            controller.vehicleName
                + "\n\n"
                + controller.message
                + "\n\n"
                + controller.lastControl
                + "\n"
                + controller.climateStatus
                + "\n"
                + controller.windowsStatus
                + "\n"
                + controller.stopStatus
                + "\n"
                + controller.readyStatus
                + "\n\n도어 열림은 잠금 해제 상태입니다. 시동 켜짐은 차량 전원 ON이며 주행 READY는 계기판에서 확인하세요."
                + " 오래된 정보는 메인에 미확인으로 표시합니다.")
        .setPositiveButton("닫기", null)
        .show();
  }

  private void showPage(String destination) {
    page = destination;
    homeScroll.setVisibility(page.equals("home") ? View.VISIBLE : View.GONE);
    settingsScroll.setVisibility(page.equals("settings") ? View.VISIBLE : View.GONE);
    setupScroll.setVisibility(page.equals("setup") ? View.VISIBLE : View.GONE);
    back.setVisibility(page.equals("home") ? View.GONE : View.VISIBLE);
    titleSpacer.setVisibility(View.GONE);
    logo.setVisibility(page.equals("home") ? View.VISIBLE : View.GONE);
    title.setText(page.equals("home") ? "D-Autolock" : page.equals("settings") ? "설정" : "처음 설정");
  }

  @Override
  public void onBackPressed() {
    if (!page.equals("home")) showPage("home");
    else super.onBackPressed();
  }

  @Override
  protected void onSaveInstanceState(Bundle state) {
    state.putString("page", page);
    state.putBoolean("signalExpanded", signalExpanded);
    super.onSaveInstanceState(state);
  }

  private boolean setupPending() {
    return controller.settings.getBoolean("setupRequired", false)
        && !controller.settings.getBoolean("setupComplete", false);
  }

  private void showSetup() {
    controller.settings.edit().putBoolean("setupIntroSeen", true).apply();
    setupStep =
        setupPending() ? Math.max(0, Math.min(6, controller.settings.getInt("setupStep", 0))) : 0;
    showPage("setup");
    updateSetup();
  }

  private void buildSetup() {
    setupStep = Math.max(0, Math.min(6, controller.settings.getInt("setupStep", 0)));
    LinearLayout content = form();
    content.setPadding(dp(20), dp(16), dp(20), dp(24));
    setupScroll.addView(content);
    setupProgress = text(content, "", 14, MINT);
    setupTitle = text(content, "", 23, TEXT);
    setupInstructions = text(content, "", 16, MUTED);
    setupStatus = text(content, "", 14, MINT);
    setupStatus.setTag("setupStatus");
    setupAction =
        button(
            content,
            "",
            v -> {
              switch (setupStep) {
                case 0:
                  if (permissions(true))
                    startActivity(
                        new Intent(
                            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            android.net.Uri.parse("package:" + getPackageName())));
                  break;
                case 1:
                  loginDialog();
                  break;
                case 2:
                  if (controller.vehicles.length() == 0) controller.refreshVehicles();
                  else vehicleDialog();
                  break;
                case 3:
                  pickDevice();
                  break;
                case 4:
                  showPage("home");
                  break;
                case 5:
                  bridgeDialog();
                  break;
                case 6:
                  thresholdDialog();
                  break;
              }
            });
    setupAction.setTag("setupAction");
    setupNext =
        button(
            content,
            "다음",
            v -> {
              if (!setupCanContinue()) return;
              if (setupStep == 6) {
                if (!permissions(true)) return;
                controller
                    .settings
                    .edit()
                    .putBoolean("setupComplete", true)
                    .putBoolean("autoStart", true)
                    .apply();
                resumePending = false;
                controller.startMonitoring(true, false);
                controller.requestStatusRefresh("setup_complete");
                showPage("home");
                update();
                return;
              }
              setupStep++;
              controller.settings.edit().putInt("setupStep", setupStep).apply();
              updateSetup();
              setupScroll.scrollTo(0, 0);
            });
    setupNext.setTag("setupNext");
    setupPrevious =
        button(
            content,
            "이전",
            v -> {
              if (setupStep > 0) setupStep--;
              controller.settings.edit().putInt("setupStep", setupStep).apply();
              updateSetup();
            });
    button(content, "나중에 · 메인 화면으로", v -> showPage("home"));
    text(content, "진행 단계는 저장됩니다. 메뉴 → 처음 설정 안내에서 이어서 진행할 수 있습니다.", 12, MUTED);
  }

  private boolean bluetoothPermissionGranted() {
    return Build.VERSION.SDK_INT >= 31
        ? checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
                == PackageManager.PERMISSION_GRANTED
            && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED
        : checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED;
  }

  private boolean setupCanContinue() {
    if (controller.initializing || controller.busy()) return false;
    switch (setupStep) {
      case 0:
        return bluetoothPermissionGranted();
      case 1:
        return controller.cloud.protocol.isLoggedIn() || controller.hasSavedLogin();
      case 2:
        return !controller.vin.isEmpty();
      case 3:
        return BluetoothAdapter.checkBluetoothAddress(controller.settings.getString("address", ""));
      case 4:
      case 6:
        return controller.setupReady();
      default:
        return true; // Bridge is optional for door automation.
    }
  }

  private void updateSetup() {
    if (setupTitle == null) return;
    String[] titles = {
      "권한과 백그라운드",
      "BYD Sub 계정 연결",
      "공유 차량 선택",
      "차량 BLE 선택",
      "수동 도어 확인",
      "차량 보조 앱 · 선택",
      "감도 설정과 자동 시작"
    };
    String[] instructions = {
      "주변 기기 권한을 허용하세요. Android 11 이하는 위치 권한이 필요합니다.\n\n"
          + "알림을 허용하고 앱 배터리를 ‘제한 없음’으로 설정하면 화면을 꺼도 동작을 확인하기 편합니다.",
      "공식 BYD AUTO에서 Sub 계정으로 차량 공유를 승인한 뒤 로그인하세요. 원격 제어 PIN 6자리도 필요합니다.\n\n"
          + "이 Sub 계정을 다른 제어 앱에서 동시에 사용하면 연결이 끊길 수 있습니다. 계정은 암호화해 저장합니다.",
      "연결할 Dolphin을 선택하세요. 차량 목록이 없으면 먼저 불러옵니다.\n\n선택 차량의 기능과 공유 권한을 확인합니다.",
      "차량 가까이에서 ‘BYD BLE’처럼 실제 BLE 신호가 수신되는 기기를 선택하세요. 일반 오디오 Bluetooth와 구분해야 합니다.\n\n"
          + "검색 목록에서 dBm 수신값을 확인하세요.",
      "차량 옆에서 메인 화면의 ‘도어 열기’와 ‘도어 잠금’을 눌러 실제 동작을 확인하세요.\n\n"
          + "확인이 끝나면 ‘처음 설정 이어하기’로 돌아와 다음 단계로 진행하세요. 설정 완료 전에는 자동 도어를 시작하지 않습니다.",
      "기어 P 정보를 이용한 자동 Stop이 필요하면 차량에 Bridge를 설치하세요. 차량 화면의 QR을 스캔하고 페어링된 차량 Bluetooth를 선택합니다.\n\n"
          + "도어 열기·잠금만 사용한다면 건너뛸 수 있습니다. 차량 Bridge에는 BYD 계정을 입력하지 않습니다.",
      "거리 감도와 접근·이탈 대기 시간을 조절하세요. 우선 기본값으로 확인한 뒤 차량 옆에서 조금씩 조절하세요.\n\n"
          + "완료하면 거리 관찰과 실제 자동 도어 제어를 시작합니다. 공조·창문·Stop 옵션은 설정에서 변경할 수 있습니다."
    };
    String[] actions = {
      "권한 / 배터리 설정",
      "Sub 계정 로그인 / 변경",
      controller.vehicles.length() == 0 ? "차량 목록 불러오기" : "공유 차량 선택",
      "블루투스 기기 검색 / 선택",
      "메인에서 수동 확인",
      "차량 보조 앱 연결 / QR",
      "거리 감도 / 대기 시간"
    };
    setupProgress.setText("STEP " + (setupStep + 1) + " / 7");
    setupTitle.setText(titles[setupStep]);
    setupInstructions.setText(instructions[setupStep]);
    setupAction.setText(actions[setupStep]);
    setupAction.setEnabled(!controller.busy() && !controller.initializing);
    setupAction.setAlpha(setupAction.isEnabled() ? 1f : .45f);
    boolean canContinue = setupCanContinue();
    setupStatus.setText(
        controller.busy()
            ? "연결 처리 중…"
            : setupStep == 5
                ? (controller.vehicleLink.configured()
                    ? "QR 등록됨 · 실제 P 수신을 확인하세요"
                    : "선택 사항 · 나중에 연결할 수 있습니다")
                : setupStep == 4
                    ? "실제 차량 동작을 확인한 뒤 다음을 누르세요"
                    : canContinue ? "준비 완료 · 다음 단계로 진행하세요" : "위 항목을 설정하면 다음 단계로 진행할 수 있습니다");
    setupNext.setText(
        setupStep == 6
            ? "설정 완료 · 자동 도어 시작"
            : setupStep == 5 && !controller.vehicleLink.configured()
                ? "건너뛰기 · 다음"
                : setupStep == 4 ? "수동 확인 완료 · 다음" : "다음");
    setupNext.setEnabled(canContinue);
    setupNext.setAlpha(canContinue ? 1f : .45f);
    setupPrevious.setEnabled(setupStep > 0);
    setupPrevious.setAlpha(setupStep > 0 ? 1f : .45f);
  }

  private void styleSwitches(View view) {
    if (view instanceof Switch) {
      Switch toggle = (Switch) view;
      toggle.setTextSize(14);
      toggle.setTrackTintList(
          new android.content.res.ColorStateList(
              new int[][] {new int[] {android.R.attr.state_checked}, new int[] {}},
              new int[] {0xff54ba62, 0xff3d4653}));
      toggle.setThumbTintList(android.content.res.ColorStateList.valueOf(TEXT));
    } else if (view instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) view;
      for (int i = 0; i < group.getChildCount(); i++) styleSwitches(group.getChildAt(i));
    }
  }

  private void update() {
    if (isFinishing()) return;
    if (foreground && bridgeResumePending && !controller.initializing) {
      bridgeResumePending = false;
      if (Build.VERSION.SDK_INT < 31
          || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
              == PackageManager.PERMISSION_GRANTED) controller.startVehicleLink();
    }
    com.dautolock.app.core.VehicleSnapshot shown = controller.snapshot;
    long shownAt = System.currentTimeMillis();
    String[] labels = DashboardStatus.vehicle(shown, shownAt);
    String door = DashboardStatus.doorTitle(shown, shownAt);
    doorTitle.setText(door);
    boolean open = door.equals("열려 있음"), closed = door.equals("잠겨 있음");
    lockIcon.setImageResource(open ? R.drawable.ic_lock_open : R.drawable.ic_lock);
    lockTile.setBackground(
        background(open ? 0xff4a3220 : closed ? 0xff1f3350 : 0xff2a313b, 14));
    lockIcon.setColorFilter(open ? 0xffffa94d : closed ? 0xff8fb4ff : MUTED);
    lockTile.setContentDescription(labels[0]);
    batteryText.setText(DashboardStatus.battery(shown, shownAt));
    powerText.setText(labels[1].replace("시동 ", ""));
    powerText.setContentDescription(labels[1]);
    windowText.setText(labels[2].replace("창문 ", ""));
    windowText.setContentDescription(labels[2]);
    signal.setText(controller.signal);
    signalGauge.reading(
        controller.averageRssi,
        controller.settings.getInt("near", Controller.DEFAULT_NEAR),
        controller.settings.getInt("far", Controller.DEFAULT_FAR));
    signalDetails.setText(controller.signalDetail);
    bridgeStatus.setText(controller.vehicleLink.describe());
    if (thresholdPreview != null) thresholdPreview.run();
    autoDetails.setText(
        !controller.monitoring
            ? "관찰 중지 · 자동 도어 OFF"
            : (controller.autoEnabled ? "자동 도어 ON" : "관찰 중 · 자동 도어 OFF")
                + (controller.cloud.backoffMillis() > 0
                    ? " · BYD 응답 대기"
                    : Double.isNaN(controller.averageRssi)
                        ? " · 신호 없음"
                        : controller.busy() ? " · 차량 확인 중" : ""));
    controlDetails.setText(controller.autoDetail);
    message.setText(DashboardStatus.brief(controller.message));
    readyDetails.setText(controller.readyStatus);
    long now = System.currentTimeMillis();
    String checked = DashboardStatus.updated(controller.snapshot, now);
    checkedTime.setText(controller.statusReading ? "확인 중… · " + checked : checked);
    boolean receiving = !Double.isNaN(controller.averageRssi);
    signalDbm.setText(receiving ? Math.round(controller.averageRssi) + " dBm" : "— dBm");
    signalDot.setBackground(background(receiving ? MINT : 0xff4a5361, 5));
    updating = true;
    automation.setChecked(controller.monitoring && controller.autoEnabled);
    updating = false;
    automationReason.setText(
        controller.monitoring && controller.autoEnabled
            ? controller.settings.getBoolean("autoStop", true)
                    && !controller.sharedVehicle()
                    && controller.vehicleLink.configured()
                    && !controller.vehicleLink.connected()
                ? "켜짐 · 차량 보조 앱 미연결: 자동 시동 끄기를 하려면 차량에서 D-Autolock Bridge를 실행하세요"
                : "켜짐 · BLE 신호로 자동 열기·잠금을 판단합니다"
            : controller.setupReady()
                ? "꺼짐 · 자동 열기·잠금·종료·탑승 공조가 모두 중단됩니다"
                : "꺼짐 · 계정·차량·BLE 기기를 설정하면 켤 수 있습니다");
    spin(controller.statusReading);
    String alert = controller.securityAlert;
    securityBanner.setVisibility(alert == null ? View.GONE : View.VISIBLE);
    if (alert != null)
      securityText.setText("수동으로 잠그고 시동을 끄세요\n" + alert + "\n(눌러서 하차 마무리 실행)");
    com.dautolock.app.core.VehicleSnapshot state = controller.snapshot;
    String[] charge = DashboardStatus.charging(state, now);
    chargeCard.setVisibility(charge == null ? View.GONE : View.VISIBLE);
    if (charge != null) {
      chargeHeadline.setText("⚡ " + charge[0]);
      chargeBar.setProgress(state.battery == null ? 0 : (int) Math.round(state.battery));
      chargeDetail.setText(charge[1] + "\n" + charge[2] + "\n" + charge[3]);
    }
    refreshIcon.setAlpha(controller.vin.isEmpty() ? .45f : 1f);
    renderActivity(now);
    updateStatus.setText(controller.updater.status);
    if (foreground && !controller.busy() && controller.updater.takeInstallRequest())
      controller.updater.install(this, () -> !controller.busy(), controller::stop);
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
            + controller.settings.getInt("near", Controller.DEFAULT_NEAR)
            + " / 이탈 "
            + controller.settings.getInt("far", Controller.DEFAULT_FAR)
            + " dBm\n접근 "
            + controller.settings.getInt("nearWaitSeconds", Controller.DEFAULT_NEAR_WAIT)
            + "초 / 이탈 "
            + controller.settings.getInt("farWaitSeconds", Controller.DEFAULT_FAR_WAIT)
            + "초 / 신호 끊김 "
            + controller.settings.getInt("lossLockSeconds", Controller.DEFAULT_LOSS)
            + "초");
    capabilities.setText(
        "차량 기능 · 잠금 "
            + controller.feature(CloudClient.Command.LOCK)
            + " / 해제 "
            + controller.feature(CloudClient.Command.UNLOCK)
            + " / Stop "
            + controller.feature(CloudClient.Command.STOP));
    boolean ready = !controller.busy() && !controller.vin.isEmpty();

    chooseVehicle.setEnabled(!controller.busy() && controller.vehicles.length() > 0);
    logout.setEnabled(!controller.busy() && !controller.initializing);
    login.setEnabled(!controller.busy() && !controller.initializing);
    for (int i = 0; i < commands.size(); i++) {
      boolean enabled = ready;
      for (CloudClient.Command need : commandNeeds.get(i))
        enabled &=
            need == CloudClient.Command.CLIMATE_ON
                ? CloudClient.hasClimate(controller.capabilities)
                : CloudClient.hasFeature(controller.capabilities, need.feature);
      setTreeEnabled(commands.get(i), enabled);
      commands.get(i).setAlpha(enabled ? 1f : .45f);
    }
    if (armedCommand != null && !armedCommand.isEnabled()) disarmCommand();
    long due = controller.stopDueAt;
    handler.removeCallbacks(stopCountdown);
    if (due >= 0) {
      long left = Math.max(0, (due - SystemClock.elapsedRealtime() + 999) / 1000);
      cancelStop.setText("자동 종료 취소 · " + left + "초 후 종료 확인");
      cancelStop.setVisibility(View.VISIBLE);
      if (foreground) handler.postDelayed(stopCountdown, 1000);
    } else cancelStop.setVisibility(View.GONE);
    boolean shared = controller.sharedVehicle();
    stopSwitch.setEnabled(!shared);
    stopSwitch.setAlpha(shared ? .45f : 1f);
    stopDelay.setText("자동 종료 대기 시간 · " + controller.stopDelaySeconds() + "초");
    updateSetup();
    if (foreground
        && !controller.initializing
        && setupPending()
        && !controller.settings.getBoolean("setupIntroSeen", false)) showSetup();
    setupBanner.setVisibility(setupPending() ? View.VISIBLE : View.GONE);
    tryAutoResume();
  }

  private void tryAutoResume() {
    if (!foreground
        || setupPending()
        || !resumePending
        || controller.initializing
        || controller.busy()
        || !controller.settings.getBoolean("autoStart", true)
        || !controller.setupReady()) return;
    resumePending = false;
    if (!permissions(true)) return;
    if (controller.monitoring) {
      if (!controller.autoEnabled) controller.auto(true);
    } else controller.startMonitoring(true, false);
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
                ? "차량이 P단이며 주차브레이크가 체결됐고, 주변과 탑승자가 안전한지 직접 확인하세요. 최신 정차 상태를 조회한 뒤 종료를 한 번 요청합니다."
                    + " BYD가 주차브레이크 정보를 제공하지 않으면 이번 직접 확인을 사용합니다. 해제·유효하지 않은 값이 오면 차단합니다."
                    + " 확인은 이번 요청에만 30초간 유효하며 자동 종료에는 적용되지 않습니다. 종료 후 계기판 전원 OFF를 확인하세요."
                : "선택한 차량에 " + cmd.label + " 명령을 전송합니다.")
        .setNegativeButton("취소", null)
        .setPositiveButton(
            cmd == CloudClient.Command.STOP ? "P단·주차브레이크 확인 · 종료" : "실행",
            (d, w) -> {
              if (cmd == CloudClient.Command.STOP) controller.manualStopAfterParkingConfirmation();
              else controller.command(cmd, false, () -> true);
            })
        .show();
  }

  private void thresholdDialog() {
    LinearLayout f = form();
    text(
        f,
        "문이 동작할 신호 기준을 조절합니다. 수신 신호나 게이지 세기 자체는 바뀌지 않습니다. −50 dBm은 −75 dBm보다 강한 신호입니다. dBm은 미터 거리가"
            + " 아닙니다.",
        13,
        MUTED);
    TextView preview = text(f, "", 15, MINT);
    preview.setTag("thresholdPreview");
    text(f, "아래 값은 저장 전 미리보기입니다. 관찰 중이면 현재 평균 신호와 비교합니다. 접근·이탈 기준은 5 dBm 이상 간격을 유지합니다.", 13, MUTED);
    SeekBar[] sensitivity = new SeekBar[2];
    sensitivity[0] =
        settingSlider(
            f,
            "접근 · 잠금 해제 기준",
            "near",
            -92,
            -30,
            controller.settings.getInt("near", Controller.DEFAULT_NEAR),
            " dBm 이상",
            "멀리서도 해제 ← → 가까워야 해제",
            value -> {
              if (sensitivity[1] != null
                  && value < sensitivity[1].getProgress() - 100 + ProximityEngine.MIN_GAP_DB)
                sensitivity[1].setProgress(value - ProximityEngine.MIN_GAP_DB + 100);
              if (thresholdPreview != null) thresholdPreview.run();
            });
    sensitivity[1] =
        settingSlider(
            f,
            "이탈 · 도어 잠금 기준",
            "far",
            -100,
            -38,
            controller.settings.getInt("far", Controller.DEFAULT_FAR),
            " dBm 이하",
            "더 멀어져야 잠금 ← → 가까운 곳부터 잠금",
            value -> {
              if (value > sensitivity[0].getProgress() - 92 - ProximityEngine.MIN_GAP_DB)
                sensitivity[0].setProgress(value + ProximityEngine.MIN_GAP_DB + 92);
              if (thresholdPreview != null) thresholdPreview.run();
            });
    SeekBar nearWait =
        settingSlider(
            f,
            "접근 후 잠금 해제 대기",
            "nearWait",
            0,
            15,
            controller.settings.getInt("nearWaitSeconds", Controller.DEFAULT_NEAR_WAIT),
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
            controller.settings.getInt("farWaitSeconds", Controller.DEFAULT_FAR_WAIT),
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
            controller.settings.getInt("lossLockSeconds", Controller.DEFAULT_LOSS),
            "초",
            "5초 ← → 60초",
            value -> {});
    Runnable recommended =
        () -> {
          sensitivity[0].setProgress(Controller.DEFAULT_NEAR + 92);
          sensitivity[1].setProgress(Controller.DEFAULT_FAR + 100);
          nearWait.setProgress(Controller.DEFAULT_NEAR_WAIT);
          farWait.setProgress(Controller.DEFAULT_FAR_WAIT);
          lossWait.setProgress(Controller.DEFAULT_LOSS - 5);
        };
    button(f, "추천값 적용 · −75 / −80 dBm", v -> recommended.run());
    text(
        f,
        "추천값: 접근 −75 / 이탈 −80 dBm, 접근 1초 / 이탈 5초 / 신호 끊김 10초. 접근 기준은 1~2 m, 이탈 기준은 더 가까이서 잠기도록 −80으로 올렸습니다. 차 옆에서도 신호가 −80 dBm 근처까지 흔들리는"
            + " 실제 기록을 반영했습니다. 휴대폰 위치와 주변 환경에 맞춰 조정하세요.",
        13,
        MUTED);
    text(
        f,
        "자동 해제 후 2분 동안은 20초 연속 멀어져야 잠그는 반복 방지가 함께 적용됩니다.\n"
            + "0초도 유효 신호 4회와 차량 상태 조회가 필요합니다. 조회 중 작은 신호 흔들림은 최대 10초·4 dBm 범위에서 허용합니다. 저장하면 자동 시작"
            + " 설정에 따라 새 기준으로 관찰·자동 제어를 다시 시작합니다.",
        13,
        MUTED);
    ScrollView scroll = new ScrollView(this);
    scroll.addView(f);
    AlertDialog d =
        new AlertDialog.Builder(this)
            .setTitle("감도 · 대기 시간")
            .setView(scroll)
            .setNegativeButton("취소", null)
            .setNeutralButton("추천값", null)
            .setPositiveButton("저장", null)
            .create();
    d.setOnShowListener(
        x -> {
          d.getButton(-3).setOnClickListener(v -> recommended.run());
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
    thresholdPreview =
        () -> {
          double average = controller.averageRssi;
          int near = sensitivity[0].getProgress() - 92;
          int far = sensitivity[1].getProgress() - 100;
          String comparison;
          if (!controller.monitoring || Double.isNaN(average))
            comparison = "현재 평균 — · 거리 관찰과 BLE 수신이 필요합니다";
          else {
            String condition =
                average >= near
                    ? "해제 신호 기준 충족"
                    : average <= far ? "잠금 신호 기준 충족" : "두 기준 사이 · 신호 기준 미충족";
            comparison =
                "현재 평균 " + String.format(Locale.KOREA, "%.1f", average) + " dBm\n" + condition;
          }
          preview.setText(
              comparison
                  + "\n선택 기준: 해제 ≥ "
                  + near
                  + " / 잠금 ≤ "
                  + far
                  + " dBm"
                  + "\n신호 기준만 비교합니다. 실제 동작에는 대기 시간·차량 상태·BYD 응답이 필요합니다."
                  + "\n현재 자동 판단: "
                  + controller.autoDetail);
        };
    d.setOnDismissListener(x -> thresholdPreview = null);
    thresholdPreview.run();
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
      missing.add(Manifest.permission.POST_NOTIFICATIONS);
      controller.settings.edit().putBoolean("notificationAsked", true).apply();
    }
    if (!missing.isEmpty()) {
      requestPermissions(missing.toArray(new String[0]), 20);
      return false;
    }
    return true;
  }

  private void bridgeDialog() {
    LinearLayout box = form();
    text(box, controller.vehicleLink.describe(), 15, TEXT);
    text(
        box,
        "차량 DiLink에 D-Autolock Bridge를 설치하고 ‘조회·연결 시작’ → ‘휴대폰 연결 QR 표시’를 누르세요. QR 스캔 후 페어링된 차량"
            + " Bluetooth를 선택합니다. 거리 감지용 BYD BLE 선택은 그대로 유지됩니다.",
        14,
        MUTED);
    AlertDialog dialog =
        new AlertDialog.Builder(this)
            .setTitle("차량 보조 앱 연결")
            .setView(box)
            .setNegativeButton("닫기", null)
            .create();
    button(
        box,
        "차량 화면의 QR 스캔",
        v -> {
          if (controller.vin.isEmpty() || controller.busy()) {
            controller.note("계정·차량 연결 완료 후 QR을 등록하세요");
            return;
          }
          if (!permissions(false)) return;
          dialog.dismiss();
          try {
            startActivityForResult(new Intent(this, BridgeQrActivity.class), QR_REQUEST);
          } catch (RuntimeException e) {
            controller.note("QR 스캔 화면을 열지 못했습니다");
          }
        });
    button(
        box,
        "저장된 연결로 재연결",
        v -> {
          if (permissions(false)) controller.startVehicleLink();
          dialog.dismiss();
        });
    button(
        box,
        "Bluetooth 시스템 설정",
        v -> startActivity(new Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)));
    button(
        box,
        "차량 보조 앱 연결 해제",
        v ->
            new AlertDialog.Builder(this)
                .setTitle("차량 상태 연결 해제")
                .setMessage("저장된 연결키를 삭제합니다. 자동 Stop에 차량 P단을 사용하려면 QR을 다시 등록해야 합니다.")
                .setNegativeButton("취소", null)
                .setPositiveButton(
                    "해제",
                    (d, w) -> {
                      controller.stop();
                      try {
                        controller.vehicleLink.forget();
                        controller.note("차량 상태 연결을 해제했습니다");
                      } catch (Exception e) {
                        controller.note("연결 해제 실패 · 다시 시도하세요");
                      }
                      dialog.dismiss();
                    })
                .show());
    dialog.show();
  }

  private void selectBridgeDevice(String qr) {
    try {
      if (Build.VERSION.SDK_INT >= 31
          && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
              != PackageManager.PERMISSION_GRANTED)
        throw new Exception("주변 기기 권한을 허용한 뒤 QR을 다시 스캔하세요");
      com.dautolock.link.Pairing.parse(qr);
      BluetoothManager manager = getSystemService(BluetoothManager.class);
      BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
      if (adapter == null || !adapter.isEnabled())
        throw new Exception("Bluetooth를 켜고 QR을 다시 스캔하세요");
      ArrayList<BluetoothDevice> peers = new ArrayList<>(adapter.getBondedDevices());
      if (peers.isEmpty()) throw new Exception("시스템 Bluetooth 설정에서 차량과 페어링한 뒤 QR을 다시 스캔하세요");
      String[] labels = new String[peers.size()];
      for (int i = 0; i < peers.size(); i++)
        labels[i] =
            (peers.get(i).getName() == null ? "이름 없음" : peers.get(i).getName())
                + "\n"
                + peers.get(i).getAddress();
      new AlertDialog.Builder(this)
          .setTitle("QR을 표시한 차량의 Bluetooth 선택")
          .setItems(
              labels,
              (d, index) -> {
                if (controller.busy()) {
                  controller.note("차량 명령 완료 후 다시 연결하세요");
                  return;
                }
                controller.stop();
                try {
                  controller.vehicleLink.configure(qr, peers.get(index).getAddress());
                  controller.note("차량 QR 등록 완료 · 기어가 계기판과 일치하는지 확인하세요");
                  controller.startVehicleLink();
                  resumePending = true;
                  tryAutoResume();
                } catch (Exception e) {
                  controller.note("QR 연결 저장 실패 · " + e.getClass().getSimpleName());
                }
              })
          .setNegativeButton("취소", null)
          .show();
    } catch (Exception e) {
      new AlertDialog.Builder(this)
          .setTitle("차량 연결 확인")
          .setMessage(e.getMessage())
          .setPositiveButton("확인", null)
          .show();
    }
  }

  @Override
  public void onRequestPermissionsResult(int r, String[] p, int[] g) {
    super.onRequestPermissionsResult(r, p, g);
    if (r == 20) {
      boolean bluetoothGranted = true;
      for (int i = 0; i < p.length; i++)
        if (!Manifest.permission.POST_NOTIFICATIONS.equals(p[i])
            && (i >= g.length || g[i] != PackageManager.PERMISSION_GRANTED))
          bluetoothGranted = false;
      if (bluetoothGranted) {
        resumePending = true;
        tryAutoResume();
      } else controller.note("자동 시작 보류 · 주변 기기 권한이 필요합니다");
    }
    if (r == 21)
      controller.note(
          DoorNotifications.enabled(this) ? "동작 알림 켜짐" : "알림 권한이 꺼져 있습니다. 휴대폰 설정에서 허용하세요");
    updateSetup();
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
        data -> {
          String recent = LogDisplay.newestKorean(data, 24000);
          runOnUiThread(
              () -> {
                if (isFinishing() || isDestroyed()) return;
                TextView text = new TextView(this);
                text.setTag("diagnosticText");
                text.setText(recent);
                text.setTextIsSelectable(true);
                text.setTextColor(TEXT);
                text.setTextSize(12);
                text.setPadding(dp(16), dp(12), dp(16), dp(12));
                ScrollView scroll = new ScrollView(this);
                scroll.addView(text);
                new AlertDialog.Builder(this)
                    .setTitle("전체 로그 · 한국 시간 · 최신순")
                    .setView(scroll)
                    .setPositiveButton("닫기", null)
                    .show();
              });
        });
  }

  @Override
  protected void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    if (request == QR_REQUEST) {
      String qr = data == null ? null : data.getStringExtra(BridgeQrActivity.RESULT);
      if (result == RESULT_OK && qr != null) selectBridgeDevice(qr);
      return;
    }
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
