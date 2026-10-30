package com.dautolock.app;

import static com.dautolock.app.Palette.*;

import android.Manifest;
import android.app.*;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.*;
import android.text.Editable;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.method.PasswordTransformationMethod;
import android.text.style.StyleSpan;
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
  private static final int QR_REQUEST = 52;
  private Controller controller;
  private LinearLayout body;
  private TextView message, account, device, capabilities, checkedTime;
  private ImageView refreshIcon;
  private LinearLayout chargeCard;
  private TextView chargeHeadline, chargeDetail;
  private ProgressBar chargeBar;
  private android.animation.ObjectAnimator refreshSpin;
  private LinearLayout activityRows;
  private String activityKey = "";
  private View armedCommand, armedRing;
  private TextView armedText;
  private CharSequence armedDescription;
  private Button cancelStop, stopDelay;
  private Switch stopSwitch;
  private final Runnable stopCountdown = this::update;
  private CharSequence armedLabel;
  private final Runnable disarmCommand = this::disarmCommand;
  private TextView signalDetails, autoDetails, autoReason, controlDetails, readyDetails;
  private TextView beaconDevice;
  private TextView updateStatus;
  private TextView bridgeStatus;
  private Button monitor, chooseVehicle, logout, login, menu;
  private ScrollView homeScroll, settingsScroll, setupScroll, bleScroll;
  private LinearLayout settingsBody, signalExtra, setupBanner;
  private TextView title, setupTitle, setupInstructions, setupStatus, setupProgress;
  private Button setupAction, setupNext, setupPrevious;
  private ImageView back;
  private View signalMore;
  private String page = "home";
  private boolean signalExpanded;
  private int setupStep;
  // Header BLE pill, car hero with its two signal badges, and the three status cards.
  private LinearLayout bleStatusPill;
  private TextView bleStatusText;
  private ImageView bleStatusIcon, batteryIcon, powerIcon, windowIcon, stateChevron;
  private CarHeroView carHero;
  private Badge primaryBadge, beaconBadge;
  private View beaconAdd, stateDot;
  private TextView powerSub, windowSub;
  private ProgressBar batteryBar;
  // BLE settings page: index 0 is the car BLE, 1 the auxiliary beacon. Edits stay drafts until 저장.
  private final TextView[] bleTabs = new TextView[2], liveValue = new TextView[2],
      liveChip = new TextView[2], livePreview = new TextView[2], nearValue = new TextView[2],
      farValue = new TextView[2], gapInfo = new TextView[2];
  private final LinearLayout[] bleSection = new LinearLayout[2];
  private final SignalHistory[] history = new SignalHistory[2];
  private final RangeSlider[] range = new RangeSlider[2];
  private final boolean[] bleDirty = new boolean[2];
  private LinearLayout bleBeaconEmpty, bleBeaconSet;
  private TextView beaconName;
  private SeekBar nearWait, farWait, lossWait;
  private Switch combineSwitch, bleCombine;
  private boolean bleBeaconTab;
  private String bleReturn = "home";
  private long lastHistoryAt;
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
  private View titleSpacer;
  private ImageView lockIcon;
  private FrameLayout lockTile, logoTile;
  private TextView doorTitle, batteryText, powerText, windowText, automationReason;
  private Switch automation;
  private LinearLayout securityBanner;
  private TextView securityText;
  private final Runnable observer = this::update;
  private boolean foreground, resumePending;
  private boolean bridgeResumePending;
  private BluetoothLeScanner pickerScanner;
  private ScanCallback pickerCallback;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private Runnable pickerTimeout;

  @Override
  public void onCreate(Bundle saved) {
    super.onCreate(saved);
    controller = ((DApplication) getApplication()).controller();
    build();
    if (saved != null) {
      signalExpanded = saved.getBoolean("signalExpanded");
      setSignalExpanded(signalExpanded);
      String restored = saved.getString("page", "home");
      if (restored.equals("ble")) openBle(saved.getBoolean("bleBeacon"));
      else showPage(restored);
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

  /** A white card shape with the faint outline the light theme uses. */
  private GradientDrawable surface(int radius) {
    GradientDrawable d = background(CARD, radius);
    d.setStroke(dp(1), STROKE);
    return d;
  }

  private GradientDrawable oval(int color) {
    GradientDrawable d = new GradientDrawable();
    d.setShape(GradientDrawable.OVAL);
    d.setColor(color);
    return d;
  }

  /** Touch feedback over any background, so plain shapes still answer a press. */
  private Drawable pressable(Drawable shape) {
    return new RippleDrawable(ColorStateList.valueOf(0x1f1d5bd6), shape, null);
  }

  private void lift(View view, float elevationDp) {
    view.setElevation(elevationDp * getResources().getDisplayMetrics().density);
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

  /**
   * Korean wraps between any two syllables, so a short line can end in "잠 / 가요". A word joiner
   * between the letters of each word leaves the spaces as the only places a line may break.
   */
  private static String keepWords(String value) {
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (i > 0 && c != ' ' && value.charAt(i - 1) != ' ') out.append('⁠');
      out.append(c);
    }
    return out.toString();
  }

  private TextView bold(LinearLayout parent, String value, int size, int color) {
    TextView t = text(parent, value, size, color);
    t.setTypeface(null, Typeface.BOLD);
    t.setPadding(0, 0, 0, 0);
    return t;
  }

  private LinearLayout card(String title) {
    return card(body, title);
  }

  /** A white rounded card appended to {@code parent}; a null title leaves it untitled. */
  private LinearLayout card(LinearLayout parent, String title) {
    LinearLayout box = new LinearLayout(this);
    box.setOrientation(LinearLayout.VERTICAL);
    box.setPadding(dp(16), dp(14), dp(16), dp(14));
    box.setBackground(surface(20));
    lift(box, 1.5f);
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
    p.setMargins(0, dp(12), 0, 0);
    parent.addView(box, p);
    if (title != null) text(box, title, 15, TEXT).setTypeface(null, Typeface.BOLD);
    return box;
  }

  private Button button(LinearLayout box, String title, View.OnClickListener listener) {
    Button b = new Button(this);
    b.setText(title);
    b.setTextColor(TEXT);
    b.setAllCaps(false);
    b.setTextSize(13);
    b.setBackgroundTintList(null);
    b.setStateListAnimator(null); // Flat light theme: no raised press shadow.
    b.setBackground(pressable(background(FIELD, 12)));
    b.setPadding(dp(8), dp(4), dp(8), dp(4));
    b.setOnClickListener(listener);
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(46));
    params.topMargin = dp(6);
    box.addView(b, params);
    return b;
  }

  private Button primaryButton(LinearLayout box, String title, View.OnClickListener listener) {
    Button b = button(box, title, listener);
    b.setTextColor(0xffffffff);
    b.setTextSize(15);
    b.setTypeface(null, Typeface.BOLD);
    b.setBackground(pressable(background(ACCENT, 16)));
    return b;
  }

  private void build() {
    LinearLayout shell = new LinearLayout(this);
    shell.setOrientation(LinearLayout.VERTICAL);
    shell.setBackground(
        new GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM, new int[] {BG_TOP, BG, BG}));
    LinearLayout bar = new LinearLayout(this);
    bar.setGravity(Gravity.CENTER_VERTICAL);
    bar.setPadding(dp(20), dp(8), dp(20), dp(4));
    shell.addView(bar, new LinearLayout.LayoutParams(-1, dp(60)));
    back = new ImageView(this);
    back.setImageResource(R.drawable.ic_back);
    back.setColorFilter(TEXT);
    back.setScaleType(ImageView.ScaleType.CENTER);
    back.setContentDescription("뒤로");
    back.setBackground(pressable(oval(0x00000000)));
    back.setOnClickListener(v -> navigateBack());
    back.setVisibility(View.GONE);
    bar.addView(back, new LinearLayout.LayoutParams(dp(40), dp(40)));
    LinearLayout brand = new LinearLayout(this);
    brand.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
    bar.addView(brand, new LinearLayout.LayoutParams(0, -1, 1));
    logoTile = new FrameLayout(this);
    logoTile.setBackground(background(ACCENT, 10));
    ImageView logo = new ImageView(this);
    logo.setImageResource(R.drawable.ic_logo);
    logo.setColorFilter(0xffffffff);
    logo.setContentDescription(null);
    logoTile.addView(logo, new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER));
    brand.addView(logoTile, new LinearLayout.LayoutParams(dp(34), dp(34)));
    title = bold(brand, "D-Autolock", 19, TEXT);
    title.setMaxLines(1); // Not setSingleLine: that scrolls horizontally and disables autosize.
    title.setPadding(dp(10), 0, 0, 0);
    title.setGravity(Gravity.CENTER_VERTICAL);
    title.setLayoutParams(new LinearLayout.LayoutParams(0, -1, 1));
    // Narrow phones: shrink the name rather than cut it next to the BLE pill and menu.
    title.setAutoSizeTextTypeUniformWithConfiguration(14, 19, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
    bleStatusPill = new LinearLayout(this);
    bleStatusPill.setTag("bleStatus");
    bleStatusPill.setGravity(Gravity.CENTER_VERTICAL);
    bleStatusPill.setPadding(dp(10), 0, dp(12), 0);
    bleStatusPill.setBackground(pressable(surface(18)));
    bleStatusPill.setOnClickListener(v -> openBle(false));
    bleStatusIcon = icon(bleStatusPill, R.drawable.ic_bluetooth, SUBTLE, 14);
    bleStatusText = bold(bleStatusPill, "BLE 미연결", 12, SUBTLE);
    bleStatusText.setPadding(dp(3), 0, 0, 0);
    LinearLayout.LayoutParams pill = new LinearLayout.LayoutParams(-2, dp(34));
    pill.setMargins(dp(8), 0, dp(8), 0);
    bar.addView(bleStatusPill, pill);
    menu =
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
    menu.setTextSize(18);
    menu.setPadding(0, 0, 0, 0);
    menu.setBackground(pressable(surface(12)));
    menu.setLayoutParams(new LinearLayout.LayoutParams(dp(38), dp(38)));
    // Balances the back arrow on sub pages so their title sits in the middle.
    titleSpacer = new View(this);
    bar.addView(titleSpacer, new LinearLayout.LayoutParams(dp(40), dp(40)));
    titleSpacer.setVisibility(View.GONE);
    FrameLayout pages = new FrameLayout(this);
    shell.addView(pages, new LinearLayout.LayoutParams(-1, 0, 1));
    homeScroll = new ScrollView(this);
    homeScroll.setTag("homePage");
    homeScroll.setFillViewport(true);
    body = new LinearLayout(this);
    body.setOrientation(LinearLayout.VERTICAL);
    body.setPadding(dp(20), 0, dp(20), dp(24));
    body.setClipToPadding(false); // Card shadows reach into the side gutters.
    homeScroll.addView(body);
    pages.addView(homeScroll);
    settingsScroll = new ScrollView(this);
    settingsScroll.setTag("settingsPage");
    settingsBody = new LinearLayout(this);
    settingsBody.setOrientation(LinearLayout.VERTICAL);
    settingsBody.setPadding(dp(20), 0, dp(20), dp(28));
    settingsBody.setClipToPadding(false);
    settingsScroll.addView(settingsBody);
    pages.addView(settingsScroll);
    settingsScroll.setVisibility(View.GONE);
    setupScroll = new ScrollView(this);
    setupScroll.setTag("setupPage");
    pages.addView(setupScroll);
    setupScroll.setVisibility(View.GONE);
    buildBlePage(pages);
    setContentView(shell);
    buildStatusAndSignal();
    LinearLayout bridgeCard = card("차량 보조 앱 연결 / QR");
    bridgeStatus = text(bridgeCard, "차량 보조 앱 미등록", 13, MUTED);
    button(bridgeCard, "차량 보조 앱 연결 / QR", v -> bridgeDialog());
    // Not "자동 도어": that is now the main screen's automation card.
    LinearLayout proximity = card("자동 도어 · 거리 관찰");
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
                          + "초 뒤 공조를 한 번 끕니다(수동 '시동 켜기'·'원격 Ready'에는 적용되지 않음).\n\n"
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
    Switch parkUnlockSwitch = new Switch(this);
    parkUnlockSwitch.setTag("unlockOnPark");
    parkUnlockSwitch.setText("주차(P) 전환 시 도어 잠금 해제");
    parkUnlockSwitch.setTextColor(TEXT);
    parkUnlockSwitch.setPadding(0, dp(12), 0, dp(12));
    parkUnlockSwitch.setMinHeight(dp(56));
    parkUnlockSwitch.setChecked(controller.settings.getBoolean("unlockOnPark", false));
    options.addView(parkUnlockSwitch);
    parkUnlockSwitch.setOnCheckedChangeListener(
        (b, on) -> {
          if (updating) return;
          if (!on) {
            controller.settings.edit().putBoolean("unlockOnPark", false).apply();
            controller.note("주차 시 자동 잠금 해제 꺼짐");
            return;
          }
          new AlertDialog.Builder(this)
              .setTitle("주차 시 자동 잠금 해제")
              .setMessage(
                  "차량 보조 앱(Bridge)이 실제 기어를 P로 2초 연속 확인하면 도어를 한 번 자동으로 엽니다."
                      + " 주행한 뒤 주차했을 때만 동작하고, 휴대폰이 차 근처(보조 앱 연결)일 때만 동작합니다.\n\n"
                      + "주의: 주차 직후 시동이 켜져 있어도 열립니다. 공공장소 주차 시 자동으로 문이 열릴 수 있으니"
                      + " 필요할 때만 켜세요. 차량 보조 앱(QR) 연결이 되어 있어야 합니다.")
              .setNegativeButton("취소", null)
              .setPositiveButton(
                  "켜기",
                  (d, w) -> {
                    controller.settings.edit().putBoolean("unlockOnPark", true).apply();
                    controller.note("주차 시 자동 잠금 해제 켜짐 · 보조 앱이 P 확인 시 도어 열림");
                  })
              // Sync the switch to the saved value whether confirmed, cancelled or dismissed.
              .setOnDismissListener(
                  d -> {
                    updating = true;
                    parkUnlockSwitch.setChecked(
                        controller.settings.getBoolean("unlockOnPark", false));
                    updating = false;
                  })
              .show();
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
    text(options, "탑승 공조 · READY 상태 진단", 16, LOCK);
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
    button(bluetooth, "블루투스 기기 검색 / 선택", v -> pickDevice(false));
    beaconDevice = text(bluetooth, "보조 BLE(비콘): 없음", 13, MUTED);
    button(bluetooth, "보조 BLE(비콘) 검색 / 선택", v -> pickDevice(true));
    button(bluetooth, "보조 BLE 작동 거리", v -> openBle(true));
    button(
        bluetooth,
        "보조 BLE 해제",
        v ->
            new AlertDialog.Builder(this)
                .setMessage("보조 BLE(비콘)를 해제할까요? 주 BLE(차량)만 사용합니다.")
                .setNegativeButton("취소", null)
                .setPositiveButton("해제", (d, w) -> controller.clearBeacon())
                .show());
    combineSwitch = combineToggle(bluetooth, "beaconCombine");
    text(
        bluetooth,
        "보조 BLE 결합을 켜면 주 BLE 또는 비콘 중 하나만 가까워도 열리고(OR), 둘 다 멀어져야 잠깁니다(AND)."
            + " 비콘이 한동안 안 잡히면 자동으로 주 BLE 단독 잠금으로 넘어갑니다(죽은 비콘 안전장치). 끄면 비콘은 관찰 전용입니다.",
        12,
        MUTED);
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
        "신호·판단·제어 결과를 휴대폰에 자동 저장합니다(최대 약 1.5 MB). 계정·비밀번호·PIN·VIN·기기 주소와 서버 원문은 포함하지 않습니다. "
            + "‘진단 로그 파일 저장’을 누르면 파일로 저장한 뒤 기존 로그를 비우고 새로 기록합니다.",
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
    String version = null;
    try {
      version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
    } catch (PackageManager.NameNotFoundException ignored) {
    }
    text(
        settingsBody,
        "D-Autolock" + (version == null ? "" : " " + version) + " · 비공식 개인용 앱",
        12,
        MUTED);
    setupBanner = new LinearLayout(this);
    setupBanner.setOrientation(LinearLayout.VERTICAL);
    primaryButton(setupBanner, "처음 설정 이어하기", v -> showSetup());
    LinearLayout.LayoutParams banner = new LinearLayout.LayoutParams(-1, -2);
    banner.topMargin = dp(10);
    body.addView(setupBanner, banner);
    setupBanner.setVisibility(View.GONE);
    TextView foot = text(body, "BLE 신호는 차량 주변 환경에 따라 달라질 수 있어요", 11, MUTED);
    foot.setGravity(Gravity.CENTER);
    foot.setPadding(0, dp(18), 0, 0);
    buildSetup();
    styleSwitches(shell);
  }

  /** The beacon OR/AND combine switch. It appears in settings and on the BLE page; update() syncs both. */
  private Switch combineToggle(LinearLayout parent, String tag) {
    Switch toggle = new Switch(this);
    toggle.setTag(tag);
    toggle.setText("보조 비콘으로 열기·잠금 (열림 OR · 잠김 AND)");
    toggle.setTextColor(TEXT);
    toggle.setMinHeight(dp(56));
    toggle.setChecked(controller.settings.getBoolean("beaconCombine", true));
    parent.addView(toggle);
    toggle.setOnCheckedChangeListener(
        (b, on) -> {
          if (updating) return;
          controller.settings.edit().putBoolean("beaconCombine", on).apply();
          controller.note(on ? "보조 비콘 결합 켜짐 · 열림 OR / 잠김 AND" : "보조 비콘 결합 꺼짐 · 관찰 전용");
          update();
        });
    return toggle;
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

  /**
   * Vehicle controls run only on a second tap within 3 seconds, so a stray touch does nothing.
   * While armed, {@code ring} (the circle or the tile) gets an outline and the label asks again.
   */
  private View.OnClickListener doubleTap(
      TextView label, String hint, View ring, boolean round, Runnable action) {
    return v -> {
      if (armedCommand == v) {
        disarmCommand();
        action.run();
        return;
      }
      disarmCommand();
      armedCommand = v;
      armedRing = ring;
      armedText = label;
      armedLabel = label.getText();
      armedDescription = v.getContentDescription();
      label.setText(hint);
      v.setContentDescription(armedDescription + ". 3초 안에 한 번 더 누르면 실행합니다");
      GradientDrawable mark = new GradientDrawable();
      if (round) mark.setShape(GradientDrawable.OVAL);
      else mark.setCornerRadius(dp(18));
      mark.setStroke(dp(2), TEXT);
      ring.setForeground(mark);
      v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
      handler.postDelayed(disarmCommand, 3000);
    };
  }

  private void disarmCommand() {
    handler.removeCallbacks(disarmCommand);
    if (armedCommand == null) return;
    armedText.setText(armedLabel);
    armedCommand.setContentDescription(armedDescription);
    armedRing.setForeground(null);
    armedCommand = null;
    armedRing = null;
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
    image.setColorFilter(SUBTLE);
    image.setScaleType(ImageView.ScaleType.FIT_CENTER);
    image.setPadding(dp(10), dp(10), dp(10), dp(10)); // An 18 dp glyph, 38 dp touch target.
    image.setContentDescription(description);
    image.setTag(tag);
    image.setOnClickListener(click);
    image.setBackground(pressable(oval(0x00000000)));
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

  /** A floating pill on the car picture: which signal, its smoothed dBm, coloured by band. */
  private static final class Badge {
    final LinearLayout root;
    final View dot;
    final TextView value;
    final String name;

    Badge(LinearLayout root, View dot, TextView value, String name) {
      this.root = root;
      this.dot = dot;
      this.value = value;
      this.name = name;
    }
  }

  private Badge badge(
      FrameLayout parent, String name, String tag, int gravity, View.OnClickListener click) {
    LinearLayout root = new LinearLayout(this);
    root.setTag(tag);
    root.setGravity(Gravity.CENTER_VERTICAL);
    root.setPadding(dp(10), dp(6), dp(4), dp(6));
    root.setBackground(pressable(surface(14)));
    lift(root, 2);
    root.setOnClickListener(click);
    View dot = new View(this);
    root.addView(dot, new LinearLayout.LayoutParams(dp(7), dp(7)));
    LinearLayout texts = new LinearLayout(this);
    texts.setOrientation(LinearLayout.VERTICAL);
    texts.setPadding(dp(7), 0, dp(1), 0);
    root.addView(texts);
    text(texts, name, 10, SUBTLE).setPadding(0, 0, 0, 0);
    TextView value = bold(texts, "끊김", 13, IDLE);
    icon(root, R.drawable.ic_back, MUTED, 14).setRotation(180); // A "›" hint that it opens.
    FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(-2, -2, gravity);
    p.topMargin = dp(2);
    parent.addView(root, p);
    return new Badge(root, dot, value, name);
  }

  /** One of the three equal status cards under the car; the icon is tagged {@code tag + "Icon"}. */
  private LinearLayout statusCard(LinearLayout row, String name, int drawable, String tag) {
    LinearLayout card = new LinearLayout(this);
    card.setOrientation(LinearLayout.VERTICAL);
    card.setTag(tag);
    card.setPadding(dp(12), dp(10), dp(10), dp(12));
    card.setBackground(pressable(surface(16)));
    lift(card, 1.5f);
    card.setOnClickListener(v -> showVehicleDetails());
    LinearLayout head = new LinearLayout(this);
    head.setGravity(Gravity.CENTER_VERTICAL);
    card.addView(head);
    TextView label = text(head, name, 12, SUBTLE);
    label.setPadding(0, 0, 0, 0);
    label.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
    icon(head, drawable, SUBTLE, 16).setTag(tag + "Icon");
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -1, 1);
    p.setMargins(row.getChildCount() == 0 ? 0 : dp(8), 0, 0, 0);
    row.addView(card, p);
    return card;
  }

  private TextView statusValue(LinearLayout card, String value) {
    TextView t = bold(card, value, 19, TEXT);
    t.setSingleLine(true);
    t.setPadding(0, dp(6), 0, 0);
    return t;
  }

  private void buildStatusAndSignal() {
    LinearLayout status = new LinearLayout(this);
    status.setOrientation(LinearLayout.VERTICAL);
    status.setTag("alertsCard");
    status.setPadding(0, dp(6), 0, 0);
    body.addView(status, new LinearLayout.LayoutParams(-1, -2));
    LinearLayout row = new LinearLayout(this);
    row.setGravity(Gravity.CENTER_VERTICAL);
    status.addView(row);
    lockTile = new FrameLayout(this);
    lockTile.setTag("doorTile");
    lift(lockTile, 2);
    lockIcon = new ImageView(this);
    lockIcon.setColorFilter(0xffffffff);
    lockTile.addView(lockIcon, new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER));
    row.addView(lockTile, new LinearLayout.LayoutParams(dp(44), dp(44)));
    doorTitle = bold(row, "도어 미확인", 24, TEXT);
    doorTitle.setTag("doorTitle");
    doorTitle.setSingleLine(true);
    doorTitle.setPadding(dp(12), 0, dp(8), 0);
    doorTitle.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
    // The icon spins while reading, so the card shape lives on a still container behind it.
    FrameLayout refreshBox = new FrameLayout(this);
    refreshBox.setBackground(surface(14));
    lift(refreshBox, 1.5f);
    refreshIcon = new ImageView(this);
    refreshIcon.setTag("statusRefresh");
    refreshIcon.setImageResource(R.drawable.ic_refresh);
    refreshIcon.setColorFilter(ACCENT);
    refreshIcon.setScaleType(ImageView.ScaleType.FIT_CENTER);
    refreshIcon.setPadding(dp(10), dp(10), dp(10), dp(10)); // A 20 dp glyph in the 40 dp box.
    refreshIcon.setBackground(pressable(oval(0x00000000)));
    refreshIcon.setContentDescription("차량 상태 새로고침");
    refreshIcon.setOnClickListener(
        v -> {
          v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
          controller.refreshNow();
          update();
        });
    refreshBox.addView(refreshIcon, new FrameLayout.LayoutParams(-1, -1));
    row.addView(refreshBox, new LinearLayout.LayoutParams(dp(40), dp(40)));
    checkedTime = text(status, "업데이트 대기", 12, MUTED);
    checkedTime.setTag("statusCheckedTime");
    checkedTime.setPadding(dp(1), dp(8), 0, 0);
    checkedTime.setMaxLines(2); // Never cut the time off; wrap on narrow phones.
    message = text(status, "", 12, MUTED); // Recent alert text lives in the activity log now.
    message.setVisibility(View.GONE);
    status.setOnClickListener(v -> showVehicleDetails());
    chargeCard = card("충전 상태");
    chargeCard.setTag("chargeCard");
    body.removeView(chargeCard);
    body.addView(chargeCard, 0); // Above the door header, only while charging.
    chargeHeadline = text(chargeCard, "", 20, TEXT);
    chargeHeadline.setTypeface(null, Typeface.BOLD);
    chargeHeadline.setTag("chargeHeadline");
    chargeBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
    chargeBar.setMax(100);
    chargeBar.setProgressTintList(ColorStateList.valueOf(SUCCESS));
    chargeBar.setProgressBackgroundTintList(ColorStateList.valueOf(IDLE_SOFT));
    chargeCard.addView(chargeBar, new LinearLayout.LayoutParams(-1, dp(14)));
    chargeDetail = text(chargeCard, "", 14, TEXT);
    chargeDetail.setTag("chargeDetail");
    chargeCard.setVisibility(View.GONE);
    securityBanner = new LinearLayout(this);
    securityBanner.setTag("securityAlert");
    securityBanner.setGravity(Gravity.CENTER_VERTICAL);
    securityBanner.setPadding(dp(14), dp(12), dp(14), dp(12));
    GradientDrawable alarm = background(DANGER_SOFT, 18);
    alarm.setStroke(dp(1), DANGER);
    securityBanner.setBackground(alarm);
    icon(securityBanner, R.drawable.ic_warning, DANGER, 26);
    securityText = new TextView(this);
    securityText.setTextColor(0xffa61b1b);
    securityText.setTextSize(14);
    securityText.setTypeface(null, Typeface.BOLD);
    securityText.setPadding(dp(10), 0, 0, 0);
    securityBanner.addView(securityText, new LinearLayout.LayoutParams(0, -2, 1));
    securityBanner.setOnClickListener(v -> finishConfirm());
    LinearLayout.LayoutParams bannerParams = new LinearLayout.LayoutParams(-1, -2);
    bannerParams.setMargins(0, dp(10), 0, 0);
    body.addView(securityBanner, 0, bannerParams);
    securityBanner.setVisibility(View.GONE);

    // The car with a badge where each signal comes from: car BLE up front, the beacon at the back.
    FrameLayout hero = new FrameLayout(this);
    hero.setTag("signalCard");
    hero.setClipChildren(false);
    carHero = new CarHeroView(this);
    carHero.setPadding(0, dp(12), 0, 0);
    hero.addView(carHero, new FrameLayout.LayoutParams(-1, -2));
    primaryBadge =
        badge(hero, "차량 BLE", "thresholdEdit", Gravity.TOP | Gravity.START, v -> openBle(false));
    beaconBadge =
        badge(hero, "보조 비콘", "beaconThresholdEdit", Gravity.TOP | Gravity.END, v -> openBle(true));
    TextView add = new TextView(this);
    add.setTag("beaconAdd");
    add.setText("+ 보조 비콘");
    add.setTextSize(11);
    add.setTextColor(ACCENT);
    add.setTypeface(null, Typeface.BOLD);
    add.setPadding(dp(10), dp(6), dp(10), dp(6));
    GradientDrawable dashed = background(0xccffffff, 14);
    dashed.setStroke(dp(1), ACCENT, dp(4), dp(3));
    add.setBackground(pressable(dashed));
    add.setContentDescription("보조 비콘 추가 · BLE 신호 설정 열기");
    add.setOnClickListener(v -> openBle(true));
    FrameLayout.LayoutParams addParams =
        new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
    addParams.topMargin = dp(6);
    hero.addView(add, addParams);
    beaconAdd = add;
    LinearLayout.LayoutParams heroParams = new LinearLayout.LayoutParams(-1, -2);
    heroParams.topMargin = dp(12);
    body.addView(hero, heroParams);

    LinearLayout cards = new LinearLayout(this);
    cards.setTag("statusRow");
    cards.setBaselineAligned(false);
    LinearLayout.LayoutParams cardsParams = new LinearLayout.LayoutParams(-1, -2);
    cardsParams.topMargin = dp(12);
    body.addView(cards, cardsParams);
    LinearLayout battery = statusCard(cards, "배터리", R.drawable.ic_battery, "batteryItem");
    batteryIcon = battery.findViewWithTag("batteryItemIcon");
    batteryText = statusValue(battery, "--%");
    batteryBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
    batteryBar.setMax(100);
    batteryBar.setProgressBackgroundTintList(ColorStateList.valueOf(IDLE_SOFT));
    LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(-1, dp(4));
    barParams.topMargin = dp(8);
    battery.addView(batteryBar, barParams);
    LinearLayout power = statusCard(cards, "시동", R.drawable.ic_power, "powerItem");
    powerIcon = power.findViewWithTag("powerItemIcon");
    powerText = statusValue(power, "미확인");
    powerSub = text(power, "", 11, MUTED);
    powerSub.setPadding(0, dp(2), 0, 0);
    LinearLayout window = statusCard(cards, "창문", R.drawable.ic_window, "windowItem");
    windowIcon = window.findViewWithTag("windowItemIcon");
    windowText = statusValue(window, "미확인");
    windowSub = text(window, "", 11, MUTED);
    windowSub.setPadding(0, dp(2), 0, 0);
  }

  /** A wide action tile; {@code filled} is the blue primary one, otherwise a white card. */
  private LinearLayout bigTile(
      LinearLayout row, int drawable, String title, String sub, boolean filled, String tag,
      CloudClient.Command[] needs, Runnable action) {
    LinearLayout tile = new LinearLayout(this);
    tile.setTag(tag);
    tile.setGravity(Gravity.CENTER_VERTICAL);
    tile.setPadding(dp(14), dp(6), dp(8), dp(6));
    tile.setBackground(pressable(filled ? background(ACCENT, 18) : surface(18)));
    lift(tile, filled ? 3 : 1.5f);
    tile.setClickable(true);
    tile.setContentDescription(title + " · " + sub);
    icon(tile, drawable, filled ? 0xffffffff : TEXT, 22);
    LinearLayout texts = new LinearLayout(this);
    texts.setOrientation(LinearLayout.VERTICAL);
    texts.setPadding(dp(10), 0, 0, 0);
    tile.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
    TextView name = bold(texts, title, 14, filled ? 0xffffffff : TEXT);
    name.setMaxLines(1);
    TextView detail = text(texts, sub, 11, filled ? 0xe6ffffff : SUBTLE);
    detail.setPadding(0, dp(2), 0, 0);
    tile.setOnClickListener(doubleTap(detail, "한 번 더 누르세요", tile, false, action));
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(64), 1);
    p.setMargins(row.getChildCount() == 0 ? 0 : dp(5), 0, row.getChildCount() == 0 ? dp(5) : 0, 0);
    row.addView(tile, p);
    commands.add(tile);
    commandNeeds.add(needs);
    return tile;
  }

  /** A white circle with its label underneath; the whole column is the touch target. */
  private LinearLayout roundButton(
      LinearLayout row, int drawable, String label, int color, String tag,
      CloudClient.Command[] needs, Runnable action) {
    LinearLayout column = new LinearLayout(this);
    column.setTag(tag);
    column.setOrientation(LinearLayout.VERTICAL);
    column.setGravity(Gravity.CENTER_HORIZONTAL);
    column.setClickable(true);
    column.setContentDescription(label);
    FrameLayout circle = new FrameLayout(this);
    GradientDrawable face = oval(CARD);
    face.setStroke(dp(1), STROKE);
    circle.setBackground(pressable(face));
    circle.setDuplicateParentStateEnabled(true); // The ripple follows a press on the column.
    lift(circle, 2);
    ImageView image = new ImageView(this);
    image.setImageResource(drawable);
    image.setColorFilter(color);
    circle.addView(image, new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER));
    column.addView(circle, new LinearLayout.LayoutParams(dp(54), dp(54)));
    TextView name = text(column, label, 12, TEXT);
    name.setPadding(0, dp(7), 0, 0);
    name.setMaxLines(1); // setSingleLine + centered gravity scrolls the text out of view.
    name.setLayoutParams(new LinearLayout.LayoutParams(-2, -2));
    column.setOnClickListener(doubleTap(name, "한 번 더", circle, true, action));
    row.addView(column, new LinearLayout.LayoutParams(0, -2, 1));
    commands.add(column);
    commandNeeds.add(needs);
    return column;
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
        .setTitle("주차 마무리")
        .setMessage(
            "차량이 P단이며 주차브레이크가 체결됐고 주변과 탑승자가 안전한지 직접 확인하세요. 최신 정차 상태를 조회해 시동을 한 번 끄고(Stop),"
                + " 전원 OFF가 확인되면 도어를 잠급니다. 확인은 이번 요청에만 30초간 유효하며, 시동 꺼짐이 확인되지 않으면 잠그지 않습니다.")
        .setNegativeButton("취소", null)
        .setPositiveButton("P단·주차브레이크 확인 · 실행", (d, w) -> controller.departureFinish())
        .show();
  }

  private void buildControlsAutomationLog() {
    LinearLayout controls = new LinearLayout(this);
    controls.setOrientation(LinearLayout.VERTICAL);
    controls.setTag("controlsCard");
    LinearLayout.LayoutParams controlsParams = new LinearLayout.LayoutParams(-1, -2);
    controlsParams.topMargin = dp(20);
    body.addView(controls, controlsParams);
    LinearLayout head = new LinearLayout(this);
    head.setGravity(Gravity.CENTER_VERTICAL);
    controls.addView(head);
    bold(head, "차량 제어", 16, TEXT).setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
    text(head, "3초 안에 두 번 탭하면 실행", 11, MUTED).setPadding(dp(8), 0, 0, 0);
    LinearLayout roundRow = new LinearLayout(this);
    roundRow.setBaselineAligned(false);
    roundRow.setClipChildren(false);
    LinearLayout.LayoutParams roundParams = new LinearLayout.LayoutParams(-1, -2);
    roundParams.topMargin = dp(12);
    controls.addView(roundRow, roundParams);
    roundButton(
        roundRow, R.drawable.ic_lock_open, "도어 열기", TEXT, "control_unlock",
        new CloudClient.Command[] {CloudClient.Command.UNLOCK},
        () -> controller.command(CloudClient.Command.UNLOCK, false, () -> true));
    roundButton(
        roundRow, R.drawable.ic_lock, "도어 잠금", TEXT, "control_lock",
        new CloudClient.Command[] {CloudClient.Command.LOCK},
        () -> controller.command(CloudClient.Command.LOCK, false, () -> true));
    roundButton(
        roundRow, R.drawable.ic_power, "시동 켜기", OPEN, "control_power_on",
        new CloudClient.Command[] {CloudClient.Command.CLIMATE_ON},
        () -> climateConfirm("시동 켜기 · 원격 공조", "", controller::manualClimateStart));
    roundButton(
        roundRow, R.drawable.ic_power, "시동 끄기", LOCK, "control_power_off",
        new CloudClient.Command[] {CloudClient.Command.STOP},
        () -> confirm(CloudClient.Command.STOP));
    LinearLayout bigRow = new LinearLayout(this);
    bigRow.setBaselineAligned(false);
    bigRow.setClipChildren(false);
    LinearLayout.LayoutParams bigParams = new LinearLayout.LayoutParams(-1, -2);
    bigParams.topMargin = dp(16);
    controls.addView(bigRow, bigParams);
    bigTile(
        bigRow, R.drawable.ic_car, "원격 Ready", "문 열고 예열", true, "control_prepare",
        new CloudClient.Command[] {CloudClient.Command.UNLOCK, CloudClient.Command.CLIMATE_ON},
        () -> climateConfirm("원격 Ready", "도어를 연 뒤 열림이 확인되면 원격 공조(예열)를 켭니다. ", controller::departurePrepare));
    bigTile(
        bigRow, R.drawable.ic_walk, "주차 마무리", "시동 끄고 잠금", false, "control_finish",
        new CloudClient.Command[] {CloudClient.Command.STOP, CloudClient.Command.LOCK},
        this::finishConfirm);
    cancelStop = button(controls, "", v -> controller.cancelPendingStop("앱에서 사용자 취소"));
    cancelStop.setTag("cancelStop");
    cancelStop.setTextColor(LOCK);
    cancelStop.setTypeface(null, Typeface.BOLD);
    cancelStop.setBackground(pressable(background(LOCK_SOFT, 14)));
    ((LinearLayout.LayoutParams) cancelStop.getLayoutParams()).topMargin = dp(12);
    cancelStop.setVisibility(View.GONE);

    LinearLayout automationCard = card(body, null);
    automationCard.setTag("automationCard");
    LinearLayout row = new LinearLayout(this);
    row.setGravity(Gravity.CENTER_VERTICAL);
    automationCard.addView(row);
    FrameLayout bolt = new FrameLayout(this);
    bolt.setBackground(background(ACCENT_SOFT, 12));
    ImageView boltIcon = new ImageView(this);
    boltIcon.setImageResource(R.drawable.ic_bolt);
    boltIcon.setColorFilter(ACCENT);
    bolt.addView(boltIcon, new FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER));
    row.addView(bolt, new LinearLayout.LayoutParams(dp(40), dp(40)));
    LinearLayout texts = new LinearLayout(this);
    texts.setOrientation(LinearLayout.VERTICAL);
    texts.setPadding(dp(12), 0, dp(8), 0);
    row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
    bold(texts, "자동 도어", 15, TEXT);
    text(texts, keepWords("가까워지면 열고 · 멀어지면 잠가요"), 12, SUBTLE).setPadding(0, dp(2), 0, 0);
    automation = new Switch(this);
    automation.setTag("automationSwitch");
    automation.setContentDescription("자동 도어 켜기");
    automation.setMinHeight(dp(48));
    row.addView(automation);
    // "현재 상태" row; tapping it opens the BLE diagnostics that used to sit in the signal card.
    LinearLayout state = new LinearLayout(this);
    state.setTag("signalMore");
    state.setGravity(Gravity.CENTER_VERTICAL);
    state.setPadding(dp(14), dp(11), dp(10), dp(11));
    state.setBackground(pressable(background(FIELD, 14)));
    state.setOnClickListener(v -> setSignalExpanded(!signalExpanded));
    LinearLayout.LayoutParams stateParams = new LinearLayout.LayoutParams(-1, -2);
    stateParams.topMargin = dp(12);
    automationCard.addView(state, stateParams);
    text(state, "현재 상태", 13, SUBTLE)
        .setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
    stateDot = new View(this);
    state.addView(stateDot, new LinearLayout.LayoutParams(dp(7), dp(7)));
    autoDetails = bold(state, "신호 없음 · 대기 중", 13, TEXT);
    autoDetails.setPadding(dp(7), 0, dp(4), 0);
    stateChevron = icon(state, R.drawable.ic_back, MUTED, 16);
    signalMore = state;
    signalExtra = new LinearLayout(this);
    signalExtra.setTag("signalExtra");
    signalExtra.setOrientation(LinearLayout.VERTICAL);
    signalExtra.setPadding(dp(4), dp(6), dp(4), 0);
    automationCard.addView(signalExtra);
    text(
        signalExtra,
        "차 그림의 배지: 청록 = 열림 구간, 주황 = 잠금 구간. 신호 세기는 실제 거리와 다를 수 있습니다.",
        12,
        MUTED);
    signalDetails = text(signalExtra, "", 12, MUTED);
    controlDetails = text(signalExtra, "", 12, MUTED);
    readyDetails = text(signalExtra, "", 12, MUTED);
    setSignalExpanded(false);
    automationReason = text(automationCard, "", 11, MUTED);
    automationReason.setTag("automationReason");
    automationReason.setPadding(dp(4), dp(8), dp(4), 0);
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
        return Palette.SUCCESS;
      case FAILURE:
        return DANGER;
      case WAIT:
        return Palette.LOCK;
      default:
        return ACCENT;
    }
  }

  private void addActivityRow(LinearLayout parent, ActivityFeed.Entry e, long now) {
    ActivityFeed.Kind kind = ActivityFeed.kind(e.text);
    int color = activityColor(kind);
    LinearLayout row = new LinearLayout(this);
    row.setPadding(0, dp(7), 0, dp(7));
    TextView badge = new TextView(this);
    badge.setText(kind.label);
    badge.setTextSize(10);
    badge.setTextColor(color);
    badge.setTypeface(null, Typeface.BOLD);
    badge.setGravity(Gravity.CENTER);
    GradientDrawable pill = background((color & 0x00ffffff) | 0x1f000000, 10);
    badge.setBackground(pill);
    badge.setPadding(dp(6), dp(3), dp(6), dp(3));
    LinearLayout.LayoutParams badgeParams = new LinearLayout.LayoutParams(dp(38), -2);
    badgeParams.setMargins(0, dp(2), dp(10), 0);
    row.addView(badge, badgeParams);
    LinearLayout lines = new LinearLayout(this);
    lines.setOrientation(LinearLayout.VERTICAL);
    row.addView(lines, new LinearLayout.LayoutParams(0, -2, 1));
    TextView what = new TextView(this);
    what.setText(ActivityFeed.friendly(e.text) + (e.count > 1 ? "  (×" + e.count + ")" : ""));
    what.setTextColor(TEXT);
    what.setTextSize(12);
    lines.addView(what);
    TextView when = new TextView(this);
    when.setText(ActivityFeed.time(e.time, now));
    when.setTextColor(MUTED);
    when.setTextSize(10);
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
    stateChevron.setRotation(expanded ? 90 : -90); // The back chevron turned to point up or down.
    signalMore.setContentDescription(
        "현재 상태 " + autoDetails.getText() + (expanded ? " · BLE 상세 접기" : " · BLE 상세 펼치기"));
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
    boolean home = page.equals("home");
    homeScroll.setVisibility(home ? View.VISIBLE : View.GONE);
    settingsScroll.setVisibility(page.equals("settings") ? View.VISIBLE : View.GONE);
    setupScroll.setVisibility(page.equals("setup") ? View.VISIBLE : View.GONE);
    bleScroll.setVisibility(page.equals("ble") ? View.VISIBLE : View.GONE);
    // Home shows the brand, BLE pill and menu; sub pages a back arrow and a centred title.
    back.setVisibility(home ? View.GONE : View.VISIBLE);
    titleSpacer.setVisibility(home ? View.GONE : View.VISIBLE);
    logoTile.setVisibility(home ? View.VISIBLE : View.GONE);
    bleStatusPill.setVisibility(home ? View.VISIBLE : View.GONE);
    menu.setVisibility(home ? View.VISIBLE : View.GONE);
    title.setGravity(home ? Gravity.CENTER_VERTICAL | Gravity.START : Gravity.CENTER);
    title.setAutoSizeTextTypeUniformWithConfiguration(
        14, home ? 19 : 16, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
    title.setPadding(home ? dp(10) : 0, 0, 0, 0);
    title.setText(
        home
            ? "D-Autolock"
            : page.equals("settings") ? "설정" : page.equals("setup") ? "처음 설정" : "BLE 신호 설정");
  }

  private void navigateBack() {
    if (page.equals("ble")) showPage(bleReturn); // Unsaved threshold drafts are dropped.
    else if (!page.equals("home")) showPage("home");
  }

  @Override
  public void onBackPressed() {
    if (page.equals("home")) super.onBackPressed();
    else navigateBack();
  }

  @Override
  protected void onSaveInstanceState(Bundle state) {
    state.putString("page", page);
    state.putBoolean("signalExpanded", signalExpanded);
    state.putBoolean("bleBeacon", bleBeaconTab);
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
    setupProgress = text(content, "", 14, ACCENT);
    setupTitle = text(content, "", 23, TEXT);
    setupInstructions = text(content, "", 16, SUBTLE);
    setupStatus = text(content, "", 14, ACCENT);
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
                  pickDevice(false);
                  break;
                case 4:
                  showPage("home");
                  break;
                case 5:
                  bridgeDialog();
                  break;
                case 6:
                  openBle(false);
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
      toggle.setTextSize(13);
      // Solid pill track with a white knob inside it, instead of Material's translucent track.
      // The track's padding keeps the knob 3 dp from the ends; the knob's own vertical inset
      // keeps it round, since Switch stretches the thumb to the full track height.
      GradientDrawable knob = oval(0xffffffff);
      knob.setSize(dp(18), dp(18));
      toggle.setThumbDrawable(new android.graphics.drawable.InsetDrawable(knob, 0, dp(3), 0, dp(3)));
      toggle.setThumbTintList(ColorStateList.valueOf(0xffffffff));
      GradientDrawable pill = background(0xffffffff, 12);
      pill.setSize(dp(42), dp(24));
      android.graphics.drawable.LayerDrawable track =
          new android.graphics.drawable.LayerDrawable(new Drawable[] {pill});
      track.setPadding(dp(3), dp(3), dp(3), dp(3));
      toggle.setTrackDrawable(track);
      toggle.setTrackTintList(
          new ColorStateList(
              new int[][] {
                new int[] {-android.R.attr.state_enabled},
                new int[] {android.R.attr.state_checked},
                new int[] {}
              },
              new int[] {0xffdfe4eb, ACCENT, 0xffc7cfdb}));
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
    boolean open = door.equals("도어 열림"), closed = door.equals("도어 잠김");
    lockIcon.setImageResource(open ? R.drawable.ic_lock_open : R.drawable.ic_lock);
    lockTile.setBackground(background(open ? LOCK : closed ? ACCENT : IDLE, 12));
    lockTile.setContentDescription(labels[0]);
    showStatusCards(shown, shownAt, labels);
    bridgeStatus.setText(controller.vehicleLink.describe());
    boolean beaconOn = controller.beaconConfigured();
    showSignals(beaconOn);
    signalDetails.setText(
        controller.signal
            + "\n"
            + controller.signalDetail
            + (beaconOn ? "\n보조 " + controller.beaconSignal + "\n" + controller.beaconDetail : ""));
    controlDetails.setText(controller.autoDetail);
    message.setText(DashboardStatus.brief(controller.message));
    readyDetails.setText(controller.readyStatus);
    long now = System.currentTimeMillis();
    String checked = DashboardStatus.updated(controller.snapshot, now);
    checkedTime.setText(controller.statusReading ? "확인 중… · " + checked : checked);
    beaconDevice.setText(
        "보조 BLE(비콘): "
            + (beaconOn ? controller.settings.getString("beaconName", "등록됨") : "없음"));
    // update() runs about once a second while monitoring; that sets the history bar pace.
    if (now - lastHistoryAt >= 900) {
      lastHistoryAt = now;
      history[0].push(controller.averageRssi, range[0].near(), range[0].far());
      history[1].push(controller.beaconRssi, range[1].near(), range[1].far());
    }
    if (page.equals("ble")) refreshBle();
    updating = true;
    automation.setChecked(controller.monitoring && controller.autoEnabled);
    boolean combine = controller.settings.getBoolean("beaconCombine", true);
    combineSwitch.setChecked(combine);
    bleCombine.setChecked(combine);
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
      securityText.setText("수동으로 잠그고 시동을 끄세요\n" + alert + "\n(눌러서 주차 마무리 실행)");
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

  private void showStatusCards(
      com.dautolock.app.core.VehicleSnapshot shown, long now, String[] labels) {
    String battery = DashboardStatus.battery(shown, now);
    boolean known = !battery.equals("--%");
    int level = known ? Integer.parseInt(battery.substring(0, battery.length() - 1)) : 0;
    int tone = !known ? IDLE : level < 20 ? DANGER : level <= 40 ? LOCK : SUCCESS;
    batteryText.setText(battery);
    batteryText.setTextColor(known ? tone : TEXT);
    batteryIcon.setColorFilter(known ? tone : SUBTLE);
    batteryBar.setProgress(level);
    batteryBar.setProgressTintList(ColorStateList.valueOf(tone));
    String power = labels[1].replace("시동 ", ""), window = labels[2].replace("창문 ", "");
    powerText.setText(power);
    powerText.setContentDescription(labels[1]);
    powerSub.setText(power.equals("꺼짐") ? "대기 중" : power.equals("켜짐") ? "전원 ON" : "정보 없음");
    powerIcon.setColorFilter(power.equals("켜짐") ? OPEN : SUBTLE);
    windowText.setText(window);
    windowText.setContentDescription(labels[2]);
    windowSub.setText(window.equals("닫힘") ? "전체" : window.equals("열림") ? "열린 창 있음" : "정보 없음");
    windowIcon.setColorFilter(window.equals("열림") ? LOCK : SUBTLE);
  }

  /** Car badges, the header BLE pill and the automation card's "현재 상태" line. */
  private void showSignals(boolean beaconOn) {
    boolean on = controller.monitoring;
    SignalBand primary =
        SignalBand.of(
            on ? controller.averageRssi : Double.NaN,
            controller.settings.getInt("near", Controller.DEFAULT_NEAR),
            controller.settings.getInt("far", Controller.DEFAULT_FAR));
    SignalBand beacon =
        !beaconOn
            ? null
            : SignalBand.of(
                on ? controller.beaconRssi : Double.NaN,
                controller.settings.getInt("beaconNear", Controller.DEFAULT_BEACON_NEAR),
                controller.settings.getInt("beaconFar", Controller.DEFAULT_BEACON_FAR));
    carHero.bands(primary, beacon);
    showBadge(primaryBadge, primary, controller.averageRssi, on);
    beaconBadge.root.setVisibility(beaconOn ? View.VISIBLE : View.GONE);
    beaconAdd.setVisibility(beaconOn ? View.GONE : View.VISIBLE);
    if (beaconOn) showBadge(beaconBadge, beacon, controller.beaconRssi, on);
    boolean any = primary != SignalBand.LOST || (beacon != null && beacon != SignalBand.LOST);
    String ble = !on ? "BLE 꺼짐" : any ? "BLE 수신 중" : "BLE 미연결";
    int tone = on && any ? ACCENT : SUBTLE;
    bleStatusText.setText(ble);
    bleStatusText.setTextColor(tone);
    bleStatusIcon.setColorFilter(tone);
    bleStatusPill.setContentDescription(ble + " · 눌러서 BLE 신호 설정");
    String state;
    int dot;
    if (!on) {
      state = "감지 꺼짐";
      dot = IDLE;
    } else if (controller.cloud.backoffMillis() > 0) {
      state = "BYD 응답 대기";
      dot = LOCK;
    } else if (controller.busy()) {
      state = "차량 확인 중";
      dot = ACCENT;
    } else if (!any) {
      state = "신호 없음 · 대기 중";
      dot = IDLE;
    } else if (primary == SignalBand.OPEN || beacon == SignalBand.OPEN) {
      state = "가까움 · 열림 구간"; // Either signal opens (beacon OR-unlock).
      dot = OPEN;
    } else if ((primary == SignalBand.LOCK || primary == SignalBand.LOST)
        && (beacon == null || beacon == SignalBand.LOCK || beacon == SignalBand.LOST)) {
      state = "멀어짐 · 잠금 구간"; // Both clear (beacon AND-lock).
      dot = LOCK;
    } else {
      state = "중간 거리 · 대기 중";
      dot = ACCENT;
    }
    autoDetails.setText(state);
    tint(stateDot, dot);
    signalMore.setContentDescription(
        "현재 상태 " + state + (signalExpanded ? " · BLE 상세 접기" : " · BLE 상세 펼치기"));
  }

  private void showBadge(Badge badge, SignalBand band, double rssi, boolean on) {
    String value =
        !on
            ? "꺼짐"
            : band == SignalBand.LOST ? "끊김" : RangeSlider.dbm((int) Math.round(rssi)) + " dBm";
    badge.value.setText(value);
    badge.value.setTextColor(band.color);
    tint(badge.dot, band == SignalBand.MIDDLE ? ACCENT : band.color);
    badge.root.setContentDescription(
        badge.name
            + " "
            + (band == SignalBand.LOST ? value : value + ", " + band.label)
            + " · 눌러서 BLE 신호 설정");
  }

  /** Colours a small round marker, reusing its shape between updates. */
  private void tint(View dot, int color) {
    if (dot.getBackground() instanceof GradientDrawable)
      ((GradientDrawable) dot.getBackground()).setColor(color);
    else dot.setBackground(oval(color));
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

  // BLE 신호 설정: a full page with a 차량 BLE tab and a 보조 비콘 tab. Each tab shows the live
  // signal, then the thresholds as a draft; nothing is saved until 저장, and back discards it.

  private void buildBlePage(FrameLayout pages) {
    bleScroll = new ScrollView(this);
    bleScroll.setTag("blePage");
    bleScroll.setFillViewport(true);
    LinearLayout content = new LinearLayout(this);
    content.setOrientation(LinearLayout.VERTICAL);
    content.setPadding(dp(20), dp(4), dp(20), dp(28));
    content.setClipToPadding(false);
    bleScroll.addView(content);
    pages.addView(bleScroll);
    bleScroll.setVisibility(View.GONE);
    LinearLayout tabs = new LinearLayout(this);
    tabs.setPadding(dp(4), dp(4), dp(4), dp(4));
    tabs.setBackground(background(0xffe2e8f2, 16));
    content.addView(tabs, new LinearLayout.LayoutParams(-1, dp(46)));
    for (int i = 0; i < 2; i++) {
      boolean beacon = i == 1;
      TextView tab = new TextView(this);
      tab.setTag(beacon ? "bleTabBeacon" : "bleTabPrimary");
      tab.setText(beacon ? "보조 비콘" : "차량 BLE");
      tab.setTextSize(13);
      tab.setGravity(Gravity.CENTER);
      tab.setOnClickListener(v -> selectBleTab(beacon));
      tabs.addView(tab, new LinearLayout.LayoutParams(0, -1, 1));
      bleTabs[i] = tab;
      bleSection[i] = new LinearLayout(this);
      bleSection[i].setOrientation(LinearLayout.VERTICAL);
      content.addView(bleSection[i]);
    }
    liveCard(bleSection[0], 0);
    thresholdCard(bleSection[0], 0, Controller.DEFAULT_NEAR, Controller.DEFAULT_FAR);
    LinearLayout waits = card(bleSection[0], "대기 시간");
    nearWait =
        settingSlider(
            waits, "접근 후 잠금 해제 대기", "nearWait", 0, 15, Controller.DEFAULT_NEAR_WAIT, "초",
            "0초 ← → 15초", value -> bleDirty[0] = true);
    farWait =
        settingSlider(
            waits, "이탈 후 잠금 대기", "farWait", 0, 30, Controller.DEFAULT_FAR_WAIT, "초",
            "0초 ← → 30초", value -> bleDirty[0] = true);
    lossWait =
        settingSlider(
            waits, "BLE 신호 끊김 후 잠금", "lossWait", 5, 60, Controller.DEFAULT_LOSS, "초",
            "5초 ← → 60초", value -> bleDirty[0] = true);
    text(
        waits,
        "자동 해제 후 2분 동안은 20초 연속 멀어져야 잠그는 반복 방지가 함께 적용됩니다. 0초도 유효 신호 4회와 차량 상태 조회가 필요합니다."
            + " 저장하면 자동 시작 설정에 따라 새 기준으로 관찰·자동 제어를 다시 시작합니다.",
        12,
        MUTED);

    bleBeaconEmpty = card(bleSection[1], "보조 비콘이 없어요");
    text(
        bleBeaconEmpty,
        "트렁크 등 차 뒤쪽에 BLE 비콘을 두면 뒤·옆에서 다가갈 때도 더 빨리 열 수 있어요. 차량 BLE와 비콘 중 하나만 가까워도 열리고,"
            + " 둘 다 멀어져야 잠깁니다.",
        12,
        SUBTLE);
    Button addDevice = button(bleBeaconEmpty, "+  신호 기기 추가", v -> pickDevice(true));
    addDevice.setTag("beaconAddDevice");
    addDevice.setTextColor(ACCENT);
    addDevice.setTypeface(null, Typeface.BOLD);
    GradientDrawable dashed = background(CARD, 16);
    dashed.setStroke(dp(1), 0xffb9c6dd, dp(5), dp(4));
    addDevice.setBackground(pressable(dashed));
    LinearLayout.LayoutParams addParams = (LinearLayout.LayoutParams) addDevice.getLayoutParams();
    addParams.height = dp(48);
    addParams.topMargin = dp(10);
    bleBeaconSet = new LinearLayout(this);
    bleBeaconSet.setOrientation(LinearLayout.VERTICAL);
    bleSection[1].addView(bleBeaconSet);
    liveCard(bleBeaconSet, 1);
    thresholdCard(bleBeaconSet, 1, Controller.DEFAULT_BEACON_NEAR, Controller.DEFAULT_BEACON_FAR);
    LinearLayout device = card(bleBeaconSet, "보조 비콘 기기");
    beaconName = text(device, "", 13, TEXT);
    bleCombine = combineToggle(device, "bleBeaconCombine");
    text(
        device,
        "켜면 차량 BLE 또는 비콘 중 하나만 가까워도 열리고, 둘 다 멀어져야 잠깁니다. 비콘이 한동안 안 잡히면 차량 BLE만으로 잠급니다.",
        12,
        MUTED);
    button(device, "다른 비콘 선택", v -> pickDevice(true));
    button(
        device,
        "보조 비콘 해제",
        v ->
            new AlertDialog.Builder(this)
                .setMessage("보조 BLE(비콘)를 해제할까요? 주 BLE(차량)만 사용합니다.")
                .setNegativeButton("취소", null)
                .setPositiveButton("해제", (d, w) -> controller.clearBeacon())
                .show());

    Button save = primaryButton(content, "저장", v -> saveBle());
    save.setTag("bleSave");
    LinearLayout.LayoutParams saveParams = (LinearLayout.LayoutParams) save.getLayoutParams();
    saveParams.height = dp(50);
    saveParams.topMargin = dp(20);
    loadBleDrafts();
    selectBleTab(false);
  }

  private void liveCard(LinearLayout parent, int i) {
    LinearLayout card = card(parent, null);
    LinearLayout head = new LinearLayout(this);
    head.setGravity(Gravity.CENTER_VERTICAL);
    card.addView(head);
    text(head, "실시간 신호 세기", 13, SUBTLE).setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
    liveChip[i] = bold(head, "● 끊김", 11, SUBTLE);
    liveChip[i].setPadding(dp(10), dp(4), dp(10), dp(4));
    LinearLayout reading = new LinearLayout(this); // Baseline-aligned: big number, small unit.
    LinearLayout.LayoutParams readingParams = new LinearLayout.LayoutParams(-2, -2);
    readingParams.topMargin = dp(6);
    card.addView(reading, readingParams);
    liveValue[i] = bold(reading, "—", 32, TEXT);
    text(reading, "dBm", 15, MUTED).setPadding(dp(6), 0, 0, 0);
    history[i] = new SignalHistory(this);
    LinearLayout.LayoutParams barsParams = new LinearLayout.LayoutParams(-1, -2);
    barsParams.topMargin = dp(10);
    card.addView(history[i], barsParams);
    livePreview[i] = text(card, "", 12, MUTED);
    livePreview[i].setTag(i == 0 ? "thresholdPreview" : "beaconPreview");
    livePreview[i].setPadding(0, dp(10), 0, 0);
  }

  private void thresholdCard(LinearLayout parent, int i, int nearDefault, int farDefault) {
    LinearLayout card = card(parent, "임계값");
    RangeSlider slider = new RangeSlider(this);
    slider.setTag(i == 0 ? "thresholdRange" : "beaconRange");
    LinearLayout.LayoutParams sliderParams = new LinearLayout.LayoutParams(-1, -2);
    sliderParams.topMargin = dp(10);
    card.addView(slider, sliderParams);
    range[i] = slider;
    slider.listener(
        (far, near) -> {
          bleDirty[i] = true;
          showThresholds(i);
          refreshPreview(i);
        });
    String far = i == 0 ? "far" : "beaconFar", near = i == 0 ? "near" : "beaconNear";
    farValue[i] =
        stepper(
            card, LOCK, "잠금 임계값", "이 값보다 약해지면 잠금", far,
            () -> slider.moveFar(slider.far() - 1), () -> slider.moveFar(slider.far() + 1));
    nearValue[i] =
        stepper(
            card, OPEN, "열림 임계값", "이 값보다 강해지면 열림", near,
            () -> slider.moveNear(slider.near() - 1), () -> slider.moveNear(slider.near() + 1));
    LinearLayout info = new LinearLayout(this);
    info.setGravity(Gravity.CENTER_VERTICAL);
    info.setPadding(dp(12), dp(10), dp(12), dp(10));
    info.setBackground(background(FIELD, 12));
    LinearLayout.LayoutParams infoParams = new LinearLayout.LayoutParams(-1, -2);
    infoParams.topMargin = dp(14);
    card.addView(info, infoParams);
    icon(info, R.drawable.ic_info, ACCENT, 16);
    gapInfo[i] = text(info, "", 11, SUBTLE);
    gapInfo[i].setTag(i == 0 ? "gapInfo" : "beaconGapInfo");
    gapInfo[i].setPadding(dp(10), 0, 0, 0);
    gapInfo[i].setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
    Button preset =
        button(
            card,
            "추천값 적용 · " + RangeSlider.dbm(nearDefault) + " / " + RangeSlider.dbm(farDefault) + " dBm",
            v -> {
              slider.set(farDefault, nearDefault);
              if (i == 0) {
                nearWait.setProgress(Controller.DEFAULT_NEAR_WAIT);
                farWait.setProgress(Controller.DEFAULT_FAR_WAIT);
                lossWait.setProgress(Controller.DEFAULT_LOSS - 5);
              }
              bleDirty[i] = true;
              showThresholds(i);
              refreshPreview(i);
            });
    preset.setTextColor(ACCENT);
    preset.setBackground(pressable(background(0x00000000, 14)));
  }

  /** A threshold row: coloured dot, name and meaning, then a [− value +] box. Returns the value. */
  private TextView stepper(
      LinearLayout card, int color, String name, String hint, String tag, Runnable minus,
      Runnable plus) {
    LinearLayout row = new LinearLayout(this);
    row.setGravity(Gravity.CENTER_VERTICAL);
    LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
    rowParams.topMargin = dp(12);
    card.addView(row, rowParams);
    View dot = new View(this);
    dot.setBackground(oval(color));
    LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(dp(8), dp(8));
    dotParams.rightMargin = dp(10);
    row.addView(dot, dotParams);
    LinearLayout texts = new LinearLayout(this);
    texts.setOrientation(LinearLayout.VERTICAL);
    row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
    bold(texts, name, 13, TEXT);
    text(texts, hint, 11, SUBTLE).setPadding(0, dp(2), dp(6), 0);
    LinearLayout box = new LinearLayout(this);
    GradientDrawable frame = background(FIELD, 12);
    frame.setStroke(dp(1), STROKE);
    box.setBackground(frame);
    box.setPadding(dp(1), dp(1), dp(1), dp(1)); // Keep the white middle inside the outline.
    row.addView(box, new LinearLayout.LayoutParams(dp(108), dp(40)));
    step(box, "−", name + " 1 dB 낮추기", tag + "Minus", minus);
    TextView value = bold(box, "", 15, TEXT);
    value.setTag(tag + "Value");
    value.setGravity(Gravity.CENTER);
    value.setBackgroundColor(CARD);
    value.setLayoutParams(new LinearLayout.LayoutParams(dp(42), -1));
    step(box, "+", name + " 1 dB 높이기", tag + "Plus", plus);
    return value;
  }

  private void step(LinearLayout box, String sign, String description, String tag, Runnable action) {
    TextView b = new TextView(this);
    b.setText(sign);
    b.setTextSize(17);
    b.setTextColor(TEXT);
    b.setGravity(Gravity.CENTER);
    b.setTag(tag);
    b.setContentDescription(description);
    b.setBackground(pressable(background(0x00000000, 14)));
    b.setOnClickListener(v -> action.run());
    box.addView(b, new LinearLayout.LayoutParams(0, -1, 1));
  }

  private void showThresholds(int i) {
    farValue[i].setText(RangeSlider.dbm(range[i].far()));
    nearValue[i].setText(RangeSlider.dbm(range[i].near()));
    SpannableStringBuilder note = new SpannableStringBuilder("두 값 사이 ");
    int start = note.length();
    note.append(String.valueOf(range[i].near() - range[i].far())).append(" dB 구간");
    note.setSpan(new StyleSpan(Typeface.BOLD), start, note.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    note.append("에서는 상태를 바꾸지 않아요.\n문 앞에서 잠금·열림이 반복되면 간격을 넓혀 보세요.");
    gapInfo[i].setText(note);
  }

  /** Compares the live average with the draft thresholds, not the saved ones. */
  private void refreshPreview(int i) {
    double rssi = i == 0 ? controller.averageRssi : controller.beaconRssi;
    String preview;
    if (!controller.monitoring || Double.isNaN(rssi))
      preview =
          "현재 평균 — · "
              + (controller.monitoring
                  ? "휴대폰을 들고 차 주변을 걸으면 여기에 값이 표시돼요"
                  : "자동 도어를 켜면 여기에 값이 표시돼요");
    else {
      int near = range[i].near(), far = range[i].far();
      preview =
          "현재 평균 "
              + String.format(Locale.KOREA, "%.1f", rssi).replace('-', '−')
              + " dBm · "
              + (rssi >= near
                  ? "해제 신호 기준 충족"
                  : rssi <= far ? "잠금 신호 기준 충족" : "두 기준 사이 · 신호 기준 미충족");
    }
    livePreview[i].setText(preview);
  }

  private void refreshBle() {
    boolean beaconOn = controller.beaconConfigured();
    bleBeaconEmpty.setVisibility(beaconOn ? View.GONE : View.VISIBLE);
    bleBeaconSet.setVisibility(beaconOn ? View.VISIBLE : View.GONE);
    beaconName.setText(controller.settings.getString("beaconName", "등록된 비콘"));
    for (int i = 0; i < 2; i++) {
      double rssi = i == 0 ? controller.averageRssi : controller.beaconRssi;
      boolean receiving = controller.monitoring && !Double.isNaN(rssi);
      liveValue[i].setText(receiving ? RangeSlider.dbm((int) Math.round(rssi)) : "—");
      SignalBand band =
          SignalBand.of(receiving ? rssi : Double.NaN, range[i].near(), range[i].far());
      liveChip[i].setText(
          !controller.monitoring ? "● 감지 꺼짐" : receiving ? "● " + band.label : "● 끊김");
      liveChip[i].setTextColor(
          band == SignalBand.LOST ? SUBTLE : band == SignalBand.MIDDLE ? ACCENT : band.color);
      liveChip[i].setBackground(
          background(
              band == SignalBand.OPEN
                  ? OPEN_SOFT
                  : band == SignalBand.LOCK
                      ? LOCK_SOFT
                      : band == SignalBand.MIDDLE ? ACCENT_SOFT : IDLE_SOFT,
              14));
      refreshPreview(i);
    }
  }

  private void selectBleTab(boolean beacon) {
    bleBeaconTab = beacon;
    for (int i = 0; i < 2; i++) {
      boolean on = (i == 1) == beacon;
      bleSection[i].setVisibility(on ? View.VISIBLE : View.GONE);
      bleTabs[i].setBackground(on ? background(CARD, 12) : null);
      bleTabs[i].setTextColor(on ? TEXT : SUBTLE);
      bleTabs[i].setTypeface(null, on ? Typeface.BOLD : Typeface.NORMAL);
      bleTabs[i].setSelected(on);
    }
  }

  /** Opens BLE 신호 설정 on one tab, with drafts freshly loaded from the saved settings. */
  private void openBle(boolean beacon) {
    if (!page.equals("ble")) bleReturn = page;
    loadBleDrafts();
    selectBleTab(beacon);
    showPage("ble");
    bleScroll.scrollTo(0, 0);
    refreshBle();
  }

  /** Drafts start from the saved settings; the history bars also colour against them. */
  private void loadBleDrafts() {
    range[0].set(
        controller.settings.getInt("far", Controller.DEFAULT_FAR),
        controller.settings.getInt("near", Controller.DEFAULT_NEAR));
    range[1].set(
        controller.settings.getInt("beaconFar", Controller.DEFAULT_BEACON_FAR),
        controller.settings.getInt("beaconNear", Controller.DEFAULT_BEACON_NEAR));
    nearWait.setProgress(controller.settings.getInt("nearWaitSeconds", Controller.DEFAULT_NEAR_WAIT));
    farWait.setProgress(controller.settings.getInt("farWaitSeconds", Controller.DEFAULT_FAR_WAIT));
    lossWait.setProgress(controller.settings.getInt("lossLockSeconds", Controller.DEFAULT_LOSS) - 5);
    showThresholds(0);
    showThresholds(1);
    bleDirty[0] = bleDirty[1] = false; // Loading the drafts is not an edit.
  }

  /** Saves only the tabs that were edited, so an untouched car BLE does not restart scanning. */
  private void saveBle() {
    try {
      if (bleDirty[0])
        controller.thresholds(
            range[0].near(),
            range[0].far(),
            nearWait.getProgress(),
            farWait.getProgress(),
            lossWait.getProgress() + 5);
    } catch (Exception e) {
      Toast.makeText(this, "감도와 대기 시간 범위를 확인하세요", Toast.LENGTH_SHORT).show();
      return;
    }
    bleDirty[0] = false;
    try {
      if (bleDirty[1] && controller.beaconConfigured())
        controller.beaconThresholds(range[1].near(), range[1].far());
    } catch (Exception e) {
      Toast.makeText(this, "접근·이탈 기준은 5 dBm 이상 간격이 필요합니다", Toast.LENGTH_SHORT).show();
      return;
    }
    bleDirty[1] = false;
    navigateBack();
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
    TextView caption = text(parent, title + " · " + value + suffix, 13, TEXT);
    SeekBar bar = new SeekBar(this);
    bar.setTag(tag);
    bar.setMax(max - min);
    bar.setProgress(value - min);
    bar.setMinimumHeight(dp(48));
    bar.setProgressTintList(android.content.res.ColorStateList.valueOf(ACCENT));
    bar.setThumbTintList(android.content.res.ColorStateList.valueOf(ACCENT));
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
    parent.addView(bar, new LinearLayout.LayoutParams(-1, dp(44)));
    text(parent, direction, 11, MUTED);
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
    // Remove only this picker's own timeout, not every queued callback — the shared handler also
    // drives the control-button disarm and the Stop countdown, which must not be cancelled here.
    if (pickerTimeout != null) handler.removeCallbacks(pickerTimeout);
    if (pickerScanner != null && pickerCallback != null)
      try {
        pickerScanner.stopScan(pickerCallback);
      } catch (SecurityException ignored) {
      }
    pickerScanner = null;
    pickerCallback = null;
  }

  private void pickDevice(boolean beacon) {
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
              .setTitle(beacon ? "보조 BLE(비콘) 선택" : "차량 블루투스 선택")
              .setView(f)
              .setNegativeButton("닫기", null)
              .create();
      list.setOnItemClickListener(
          (p, v, index, id) -> {
            String address = addresses.get(index);
            String label = names.get(address);
            if (beacon) controller.beaconDevice(label, address);
            else controller.device(label, address);
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
      pickerTimeout =
          () -> {
            stopPicker();
            status.setText("검색 완료 · 차량의 BLE 기기를 선택하세요");
          };
      handler.postDelayed(pickerTimeout, 20000);
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
