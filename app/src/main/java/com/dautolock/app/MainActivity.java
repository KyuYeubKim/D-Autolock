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
import com.dautolock.app.core.ThresholdCalibrator;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

public final class MainActivity extends Activity {
  private static final int QR_REQUEST = 52;
  private Controller controller;
  private LinearLayout body;
  private TextView message, account, device, capabilities, checkedTime, odometerText;
  private ImageView refreshIcon;
  private LinearLayout chargeCard;
  private TextView chargeHeadline, chargeDetail;
  private ProgressBar chargeBar;
  private android.animation.ObjectAnimator refreshSpin;
  private LinearLayout activityRows;
  private String activityKey = "";
  private View armedCommand;
  private Runnable armedUndo;
  private Button cancelStop, stopDelay;
  private Switch stopSwitch;
  private final Runnable stopCountdown = this::update;
  private final Runnable disarmCommand = this::disarmCommand;
  private TextView signalDetails, autoDetails, autoReason, controlDetails, readyDetails;
  private TextView carAudioState;
  private com.dautolock.app.watchkey.WatchKey watchKey;
  private com.dautolock.app.watchkey.WatchKeyProvision watchKeyRun;
  private Switch watchKeySwitch;
  private TextView watchKeyStatus;
  private ImageView watchKeyQr;
  private Button watchKeyRegister;
  private LinearLayout watchBleSection;
  private TextView watchBleStatus;
  private Button watchBleUnlock, watchBleLock;
  private Switch watchKeyAutoSwitch;
  private com.dautolock.app.watchble.WatchBleClient watchBleRun;
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
  // Settings: the grouped menu page and one sub-page per row ("settings/account" …).
  private FrameLayout pageFrame;
  private final Map<String, ScrollView> detailPages = new LinkedHashMap<>();
  private final Map<String, String> detailTitles = new HashMap<>();
  private final Map<String, TextView> menuSubs = new HashMap<>();
  private String appVersion = "";
  // 위치로 자동 설정 wizard: step 0 intro, 1..6 open spots, 7 the driver's seat, 8..13 lock spots,
  // 14 result.
  private static final int CAL_SPOTS = 6, CAL_INSIDE = CAL_SPOTS + 1, CAL_RESULT = 2 + 2 * CAL_SPOTS;
  private static final String[][] CAL_OPEN_PLACES = {
    {"운전석 문 앞", "운전석 문손잡이 앞, 문을 열 때 서는 자리에 서세요."},
    {"운전석 뒷문 앞", "뒷좌석에 짐을 싣거나 탈 때 서는 자리예요."},
    {"트렁크 뒤", "트렁크를 열 때 서는 자리, 범퍼 바로 뒤에 서세요."},
    {"조수석 뒷문 앞", "반대편 뒷문손잡이 앞에 서세요."},
    {"조수석 문 앞", "조수석 문손잡이 앞에 서세요."},
    {"차 앞", "보닛 앞, 차를 마주 보고 서세요."}
  };
  private static final String[] CAL_INSIDE_PLACE = {
    "운전석에 앉아서",
    "문을 닫고 운전석에 앉으세요. 휴대폰은 운전할 때 두는 곳(주머니·컵홀더·거치대 등)에 두세요. 차 안에서는 잠기지 않도록 기준을 잡는 데 써요."
  };
  private static final String[][] CAL_LOCK_PLACES = {
    {"운전석 쪽, 걸어 나간 곳", "운전석 문에서 5~8 m쯤 걸어 나간 자리예요. 이쯤이면 잠겨 있었으면 하는 곳에 서세요."},
    {"트렁크 뒤, 걸어 나간 곳", "트렁크에서 뒤로 5~8 m쯤 떨어진 자리예요."},
    {"조수석 쪽, 걸어 나간 곳", "조수석 문에서 5~8 m쯤 떨어진 자리예요."},
    {"차 앞, 걸어 나간 곳", "보닛 앞에서 5~8 m쯤 떨어진 자리예요."},
    {"평소 걸어 나가는 방향", "현관이나 엘리베이터 쪽 등, 주차 후 늘 걸어가는 방향의 잠겼으면 하는 지점이에요."},
    {"그보다 조금 더 먼 곳", "바로 앞 위치에서 몇 걸음 더 간 자리예요. 여기서는 꼭 잠겨 있어야 해요."}
  };
  private ScrollView calScroll;
  private ProgressBar calProgress;
  private TextView calStepLabel, calStepTitle, calStepBody, calLive, calMeasured, calNotice, calResult;
  private Button calPrimary, calBack, calSkip;
  private final ThresholdCalibrator.Spot[] calOpen = new ThresholdCalibrator.Spot[CAL_SPOTS],
      calLock = new ThresholdCalibrator.Spot[CAL_SPOTS];
  private ThresholdCalibrator.Spot calInside;
  private int calStep;
  private ThresholdCalibrator.Result calResultValue;
  private String calReturn = "ble";
  private boolean calPausedAuto, calStartedWatch;
  private Runnable calMeasure; // Non-null while a 4-second measurement runs.
  private long calMeasureUntil;
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
      if (restored.equals("ble") || restored.equals("calibrate")) openBle(saved.getBoolean("bleBeacon"));
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
    if (page.equals("calibrate")) pauseForCalibration();
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
    // Never leave automation paused behind the user's back.
    if (page.equals("calibrate")) resumeAfterCalibration();
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
    pageFrame = pages;
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
    buildCalibratePage(pages);
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
    button(proximity, "거리 감도 · 대기 시간 (BLE 신호 설정)", v -> openBle(false));
    button(proximity, "위치로 자동 설정 (열림·잠금 위치 측정)", v -> openCalibrate());
    Switch carAudioSwitch = new Switch(this);
    carAudioSwitch.setTag("carAudioHold");
    carAudioSwitch.setText("차 블루투스에 연결돼 있으면 자동 잠금·열림 멈춤");
    carAudioSwitch.setTextColor(TEXT);
    carAudioSwitch.setMinHeight(dp(56));
    carAudioSwitch.setChecked(controller.settings.getBoolean("carAudioHold", true));
    proximity.addView(carAudioSwitch);
    carAudioSwitch.setOnCheckedChangeListener(
        (b, on) -> controller.settings.edit().putBoolean("carAudioHold", on).apply());
    text(
        proximity,
        "휴대폰 음악·통화가 차 블루투스(차량 보조 앱으로 등록한 기기)에 연결돼 있으면 차 안에 있는 것으로 보고 자동 잠금·열림과 자동 시동 끄기를 하지 않습니다. 시동을 켠 채 내려 멀어지면 연결이 끊긴 뒤 평소대로 잠급니다.",
        12,
        MUTED);
    carAudioState = text(proximity, "", 12, MUTED);
    carAudioState.setTag("carAudioState");
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
    LinearLayout options = card("탑승·하차 동작");
    Switch startSwitch = new Switch(this);
    startSwitch.setText("앱 실행·감도 저장 시 자동 제어 시작");
    startSwitch.setTextColor(TEXT);
    startSwitch.setMinHeight(dp(56));
    startSwitch.setChecked(controller.settings.getBoolean("autoStart", true));
    proximity.addView(startSwitch); // An automation setting, so it lives with 자동 도어와 감도.
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
                          + "초 뒤 공조를 한 번 끕니다(수동 '시동 켜기'에는 적용되지 않음).\n\n"
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
    LinearLayout link = card("BYD AUTO 계정");
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
    LinearLayout bluetooth = card("차량 블루투스");
    device = text(bluetooth, "기기 선택 전", 15, TEXT);
    button(bluetooth, "블루투스 기기 검색 / 선택", v -> pickDevice(false));
    button(bluetooth, "차량 BLE 작동 거리", v -> openBle(false));
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
        "보조 BLE 결합을 켜면 주 BLE 또는 비콘 중 하나만 가까워도 열리고, 하나만 멀어져도 잠깁니다(둘 다 OR). 단 다른 쪽이 아직 가까우면 잠그지 않습니다."
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
    text(
        bluetooth,
        "블루투스는 거리 감지용입니다. 도어 제어에는 휴대폰과 차량의 인터넷 연결이 필요합니다. 전원을 끄면 신호가 사라지는 오디오 기기는 사용할 수 없습니다.",
        13,
        MUTED);
    LinearLayout notify = card("알림 및 백그라운드");
    button(
        notify,
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
    button(
        notify,
        "배터리 · 백그라운드 설정",
        v ->
            startActivity(
                new Intent(
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:" + getPackageName()))));
    Switch chargeSwitch = new Switch(this);
    chargeSwitch.setTag("chargeNotify");
    chargeSwitch.setText("충전 완료 알림");
    chargeSwitch.setTextColor(TEXT);
    chargeSwitch.setMinHeight(dp(56));
    chargeSwitch.setChecked(controller.chargeNotify());
    notify.addView(chargeSwitch);
    chargeSwitch.setOnCheckedChangeListener(
        (b, on) -> {
          controller.settings.edit().putBoolean("chargeNotify", on).apply();
          if (!on) controller.forgetCharge();
        });
    text(
        notify,
        "충전 중이면 차가 알려준 완료 예상 시각에 한 번만 상태를 확인하고, 차가 잠긴 채 충전이 끝났으면 알려줍니다. 계속 조회하지 않습니다.",
        12,
        MUTED);
    Switch parkedOnSwitch = new Switch(this);
    parkedOnSwitch.setTag("parkedOnNotify");
    parkedOnSwitch.setText("P 상태로 5분 넘게 시동 켜짐 알림");
    parkedOnSwitch.setTextColor(TEXT);
    parkedOnSwitch.setMinHeight(dp(56));
    parkedOnSwitch.setChecked(controller.parkedOnNotify());
    notify.addView(parkedOnSwitch);
    parkedOnSwitch.setOnCheckedChangeListener(
        (b, on) -> {
          controller.settings.edit().putBoolean("parkedOnNotify", on).apply();
          if (!on) DoorNotifications.cancelParkedOn(this);
        });
    text(
        notify,
        "차량 보조 앱이 기어 P를 확인한 뒤 5분이 지나면 BYD 상태를 한 번 조회해, 시동이 아직 켜져 있으면 알려줍니다. 차를 두고 내려도 알려주지만, 휴대폰이 차 안에서 P를 확인한 적이 있어야 합니다.",
        12,
        MUTED);
    text(
        notify,
        "알림을 허용하고 앱 배터리를 ‘제한 없음’으로 두면 화면을 꺼도 자동 도어가 계속 동작하고, 열기·잠금 결과를 알림으로 받을 수 있습니다.",
        12,
        MUTED);
    LinearLayout events = card("진단 로그");
    button(events, "활동 전체 보기", v -> showActivity());
    button(events, "진단 로그 보기", v -> showDiagnostics());
    button(events, "진단 로그 공유", v -> shareDiagnostics()).setTag("diagnosticsShare");
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
            + "‘진단 로그 공유’는 텔레그램 등으로 바로 보내고, 돌아오면 휴대폰의 로그를 비울지 묻습니다. "
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
    try {
      appVersion = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
    } catch (PackageManager.NameNotFoundException ignored) {
    }
    text(updates, "D-Autolock " + appVersion + " · 비공식 개인용 앱", 12, MUTED);
    LinearLayout watchCard = buildWatchKeyCard();
    // Settings: a grouped menu; each row opens a page holding the cards built above.
    detail("account", "계정과 차량", link);
    detail("bluetooth", "블루투스와 비콘", bluetooth);
    detail("bridge", "차량 보조 앱", bridgeCard);
    detail("auto", "자동 도어와 감도", proximity);
    detail("trip", "탑승과 하차", options);
    detail("notify", "알림 및 백그라운드", notify);
    detail("logs", "로그 및 진단", events);
    detail("update", "앱 업데이트", updates);
    detail("watchkey", "블루투스 키 (실험실)", watchCard);
    LinearLayout connection = menuGroup("연결");
    menuRow(connection, R.drawable.ic_person, "account");
    menuRow(connection, R.drawable.ic_bluetooth, "bluetooth");
    menuRow(connection, R.drawable.ic_qr, "bridge");
    LinearLayout automatic = menuGroup("자동화");
    menuRow(automatic, R.drawable.ic_bolt, "auto");
    menuRow(automatic, R.drawable.ic_car_outline, "trip");
    LinearLayout manage = menuGroup("앱 관리");
    menuRow(manage, R.drawable.ic_bell, "notify");
    menuRow(manage, R.drawable.ic_checklist, "logs");
    menuRow(manage, R.drawable.ic_download, "update");
    LinearLayout lab = menuGroup("실험실");
    menuRow(lab, R.drawable.ic_bluetooth, "watchkey");
    LinearLayout links = new LinearLayout(this);
    links.setGravity(Gravity.CENTER);
    LinearLayout.LayoutParams linksParams = new LinearLayout.LayoutParams(-1, -2);
    linksParams.topMargin = dp(18);
    settingsBody.addView(links, linksParams);
    footLink(links, "처음 설정 안내", v -> showSetup());
    text(links, "·", 12, MUTED).setPadding(dp(4), 0, dp(4), 0);
    footLink(links, "앱 정보", v -> about());
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

  /** Stage A of the Bluetooth (watch) key: issuance + status only, behind the watchKeyEnabled flag. */
  private LinearLayout buildWatchKeyCard() {
    watchKey =
        new com.dautolock.app.watchkey.WatchKey(this, controller.settings, controller.diagnostics);
    LinearLayout card = card("블루투스 키 (실험실)");
    watchKeySwitch = new Switch(this);
    watchKeySwitch.setTag("watchKeyEnabled");
    watchKeySwitch.setText("블루투스 키 사용");
    watchKeySwitch.setTextColor(TEXT);
    watchKeySwitch.setMinHeight(dp(56));
    watchKeySwitch.setChecked(watchKey.enabled());
    card.addView(watchKeySwitch);
    watchKeySwitch.setOnCheckedChangeListener(
        (b, on) -> {
          if (updating) return;
          watchKey.setEnabled(on);
          update();
        });
    text(
        card,
        "스마트워치 디지털 키 방식으로 차와 블루투스로 직접 통신하는 실험 기능입니다. 지금(1단계)은 키 발급과 상태 확인까지만 하고, 열림·잠금 판정은 바꾸지 않습니다. 끄면 이전과 똑같이 동작합니다.",
        12,
        MUTED);
    text(
        card,
        "주의: 이 앱이 당신의 갤럭시 워치(SM-R925N) 모델로 BYD 계정에 워치로 등록됩니다. 실제로 쓰는 워치 키가 밀려날 수 있고, 차에 블루투스 본딩이 남으며, 서버가 비정상 사용으로 제한할 수 있습니다. 본인 차·본인 계정·개인용으로만 사용하세요.",
        12,
        0xffb00020);
    watchKeyStatus = text(card, "", 13, TEXT);
    watchKeyStatus.setPadding(0, dp(8), 0, 0);
    watchKeyStatus.setTag("watchKeyStatus");
    watchKeyQr = new ImageView(this);
    LinearLayout.LayoutParams qp = new LinearLayout.LayoutParams(dp(220), dp(220));
    qp.topMargin = dp(10);
    qp.gravity = Gravity.CENTER_HORIZONTAL;
    card.addView(watchKeyQr, qp);
    watchKeyQr.setVisibility(View.GONE);
    text(
        card,
        "발급을 누르면 QR이 나옵니다. 공식 BYD 앱으로 그 QR을 스캔해 승인하면 키가 발급됩니다.",
        12,
        MUTED);
    watchKeyRegister = primaryButton(card, "블루투스 키 발급", v -> startWatchKeyRegister());
    button(card, "키 삭제 / 초기화", v -> clearWatchKey());

    // Stage B: manual BLE lock/unlock test (only shown once a key is issued).
    watchBleSection = new LinearLayout(this);
    watchBleSection.setOrientation(LinearLayout.VERTICAL);
    LinearLayout.LayoutParams sp =
        new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    sp.topMargin = dp(12);
    card.addView(watchBleSection, sp);
    text(watchBleSection, "수동 BLE 시험 (2단계)", 14, TEXT);
    text(
        watchBleSection,
        "발급된 키로 차에 블루투스로 직접 연결해 한 번 잠그거나 엽니다. 실제로 차가 잠기고/열리며, 차에 블루투스 본딩이 남습니다. 자동 도어와는 무관한 수동 시험이고, 안 되면 평소처럼 클라우드로 동작합니다. 차 근처에서 눌러 주세요.",
        12,
        MUTED);
    watchBleStatus = text(watchBleSection, "", 13, TEXT);
    watchBleStatus.setPadding(0, dp(6), 0, 0);
    watchBleStatus.setTag("watchBleStatus");
    watchBleUnlock = primaryButton(watchBleSection, "BLE로 도어 열기", v -> runWatchBleTest(false));
    watchBleLock = button(watchBleSection, "BLE로 도어 잠그기", v -> runWatchBleTest(true));

    // Stage C: use the watch BLE key as the actuator for automatic/manual lock·unlock·engine.
    LinearLayout autoRow = new LinearLayout(this);
    autoRow.setOrientation(LinearLayout.VERTICAL);
    LinearLayout.LayoutParams ap =
        new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    ap.topMargin = dp(12);
    watchBleSection.addView(autoRow, ap);
    text(autoRow, "자동에서 블루투스 키 사용 (3단계)", 14, TEXT);
    watchKeyAutoSwitch = new Switch(this);
    watchKeyAutoSwitch.setTag("watchKeyAuto");
    watchKeyAutoSwitch.setText("열기·잠금·시동에 블루투스 키 우선");
    watchKeyAutoSwitch.setTextColor(TEXT);
    watchKeyAutoSwitch.setMinHeight(dp(56));
    watchKeyAutoSwitch.setChecked(controller.settings.getBoolean("watchKeyAuto", true));
    autoRow.addView(watchKeyAutoSwitch);
    watchKeyAutoSwitch.setOnCheckedChangeListener(
        (b, on) -> {
          if (updating) return;
          controller.settings.edit().putBoolean("watchKeyAuto", on).apply();
        });
    text(
        autoRow,
        "켜면 자동/수동 도어 열기·잠금과 시동 켜기·끄기를 블루투스 키로 먼저 보냅니다(빠르고 인터넷 없이도 동작). 키가 멀거나 실패하면 자동으로 클라우드로 보냅니다. 거리 판정(근접/이탈)과 안전 검사(전원·기어·정차·이탈)는 그대로입니다. 끄면 전부 클라우드로만 보냅니다.",
        12,
        MUTED);
    return card;
  }

  private void runWatchBleTest(boolean lock) {
    if (watchBleRun != null) return;
    if (watchKey == null || !watchKey.hasKey()) {
      Toast.makeText(this, "먼저 블루투스 키를 발급하세요", Toast.LENGTH_SHORT).show();
      return;
    }
    new AlertDialog.Builder(this)
        .setTitle(lock ? "BLE로 도어 잠그기" : "BLE로 도어 열기")
        .setMessage(
            (lock ? "지금 차를 블루투스로 잠급니다." : "지금 차를 블루투스로 엽니다.")
                + " 차 근처에 있는지 확인하세요. 계속할까요?")
        .setPositiveButton(
            "실행",
            (d, w) -> {
              watchBleUnlock.setEnabled(false);
              watchBleLock.setEnabled(false);
              watchBleStatus.setText("시작…");
              watchBleRun =
                  watchKey.bleTest(
                      lock,
                      new com.dautolock.app.watchble.WatchBleClient.Listener() {
                        @Override
                        public void onProgress(String message) {
                          runOnUiThread(() -> watchBleStatus.setText(message));
                        }

                        @Override
                        public void onResult(boolean success, String message, int doorStates) {
                          runOnUiThread(
                              () -> {
                                watchBleRun = null;
                                watchBleStatus.setText((success ? "✓ " : "✗ ") + message);
                                boolean has = watchKey != null && watchKey.hasKey();
                                watchBleUnlock.setEnabled(has);
                                watchBleLock.setEnabled(has);
                              });
                        }
                      });
              if (watchBleRun == null) {
                boolean has = watchKey != null && watchKey.hasKey();
                watchBleUnlock.setEnabled(has);
                watchBleLock.setEnabled(has);
              }
            })
        .setNegativeButton("취소", null)
        .show();
  }

  private void startWatchKeyRegister() {
    if (!watchKey.enabled()) {
      Toast.makeText(this, "먼저 '블루투스 키 사용'을 켜 주세요", Toast.LENGTH_SHORT).show();
      return;
    }
    if (watchKeyRun != null) return;
    watchKeyRegister.setEnabled(false);
    watchKeyQr.setVisibility(View.GONE);
    watchKeyStatus.setText("시작 중…");
    watchKeyRun =
        watchKey.register(
            new com.dautolock.app.watchkey.WatchKeyProvision.Listener() {
              @Override
              public void onStatus(String m) {
                watchKeyStatus.setText(m);
              }

              @Override
              public void onQrReady(com.dautolock.app.watchkey.QrInfo qr) {
                android.graphics.Bitmap b =
                    com.dautolock.app.watchkey.WatchQr.bitmap(qr.payload, dp(220));
                if (b != null) {
                  watchKeyQr.setImageBitmap(b);
                  watchKeyQr.setVisibility(View.VISIBLE);
                }
              }

              @Override
              public void onApproved() {
                watchKeyStatus.setText("승인됨 · 키 발급 중…");
              }

              @Override
              public void onIssued(String s) {
                watchKeyQr.setVisibility(View.GONE);
                watchKeyRun = null;
                watchKeyRegister.setEnabled(true);
                update();
              }

              @Override
              public void onError(String m) {
                watchKeyQr.setVisibility(View.GONE);
                watchKeyStatus.setText(m);
                watchKeyRun = null;
                watchKeyRegister.setEnabled(true);
              }
            },
            r -> runOnUiThread(r));
  }

  private void clearWatchKey() {
    new AlertDialog.Builder(this)
        .setTitle("블루투스 키 삭제")
        .setMessage(
            "저장된 블루투스 키를 지웁니다. 공식 BYD 앱에서도 등록된 워치 키를 함께 해제하는 것이 좋습니다. 계속할까요?")
        .setPositiveButton(
            "삭제",
            (d, w) -> {
              watchKey.clear();
              update();
            })
        .setNegativeButton("취소", null)
        .show();
  }

  /** A settings sub-page ("settings/" + key) that takes over the given cards from the home body. */
  private void detail(String key, String title, LinearLayout... cards) {
    ScrollView scroll = new ScrollView(this);
    scroll.setTag("settings_" + key);
    LinearLayout column = new LinearLayout(this);
    column.setOrientation(LinearLayout.VERTICAL);
    column.setPadding(dp(20), 0, dp(20), dp(28));
    column.setClipToPadding(false);
    scroll.addView(column);
    pageFrame.addView(scroll);
    scroll.setVisibility(View.GONE);
    for (LinearLayout card : cards) {
      body.removeView(card);
      column.addView(card); // Keeps the card's own margins.
    }
    detailPages.put("settings/" + key, scroll);
    detailTitles.put("settings/" + key, title);
  }

  /** A grey section label and the white rounded group its rows go into. */
  private LinearLayout menuGroup(String label) {
    TextView header = text(settingsBody, label, 12, MUTED);
    header.setPadding(dp(4), dp(16), 0, dp(8));
    LinearLayout group = new LinearLayout(this);
    group.setOrientation(LinearLayout.VERTICAL);
    group.setBackground(surface(18));
    group.setClipToOutline(true); // Row ripples follow the rounded corners.
    lift(group, 1.5f);
    settingsBody.addView(group, new LinearLayout.LayoutParams(-1, -2));
    return group;
  }

  /** One menu row: icon, title, a live summary line (see update()) and a chevron. */
  private void menuRow(LinearLayout group, int drawable, String key) {
    String page = "settings/" + key;
    if (group.getChildCount() > 0) {
      View divider = new View(this);
      divider.setBackgroundColor(STROKE);
      LinearLayout.LayoutParams line = new LinearLayout.LayoutParams(-1, dp(1));
      line.leftMargin = dp(50);
      group.addView(divider, line);
    }
    LinearLayout row = new LinearLayout(this);
    row.setTag("menu_" + key);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setPadding(dp(16), dp(13), dp(10), dp(13));
    row.setBackground(pressable(background(0x00000000, 0)));
    row.setOnClickListener(
        v -> {
          showPage(page);
          detailPages.get(page).scrollTo(0, 0);
        });
    icon(row, drawable, SUBTLE, 18);
    LinearLayout texts = new LinearLayout(this);
    texts.setOrientation(LinearLayout.VERTICAL);
    texts.setPadding(dp(16), 0, dp(8), 0);
    row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
    bold(texts, detailTitles.get(page), 14, TEXT);
    TextView sub = text(texts, "", 11, MUTED);
    sub.setPadding(0, dp(2), 0, 0);
    sub.setMaxLines(1);
    sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
    menuSubs.put(key, sub);
    icon(row, R.drawable.ic_back, MUTED, 16).setRotation(180);
    group.addView(row);
  }

  private void footLink(LinearLayout parent, String label, View.OnClickListener click) {
    TextView link = text(parent, label, 12, SUBTLE);
    link.setPadding(dp(8), dp(8), dp(8), dp(8));
    link.setBackground(pressable(background(0x00000000, 10)));
    link.setOnClickListener(click);
  }

  /** Each settings row's summary, kept current by update(). */
  private void showMenuSummaries(boolean beaconOn) {
    menuSubs.get("account").setText(
        (controller.cloud.protocol.isLoggedIn()
                ? "계정 연결됨"
                : controller.hasSavedLogin() ? "계정 저장됨" : "계정 연결 전")
            + " · "
            + (controller.vin.isEmpty() ? "차량 선택 전" : controller.vehicleName));
    menuSubs.get("bluetooth").setText(
        (BluetoothAdapter.checkBluetoothAddress(controller.settings.getString("address", ""))
                ? "차량 BLE"
                : "차량 BLE 선택 전")
            + " · "
            + (beaconOn ? "보조 비콘 1개" : "보조 비콘 없음"));
    menuSubs.get("bridge").setText(
        "Bridge · "
            + (!controller.vehicleLink.configured()
                ? "미등록"
                : controller.vehicleLink.connected() ? "연결됨" : "등록됨 · 연결 대기"));
    menuSubs.get("auto").setText(
        (controller.monitoring && controller.autoEnabled ? "켜짐" : "꺼짐")
            + " · 접근 "
            + RangeSlider.dbm(controller.settings.getInt("near", Controller.DEFAULT_NEAR))
            + " / 이탈 "
            + RangeSlider.dbm(controller.settings.getInt("far", Controller.DEFAULT_FAR)));
    List<String> trip = new ArrayList<>();
    if (controller.settings.getBoolean("autoReady", false)) trip.add("공조 시작");
    if (controller.settings.getBoolean("closeWindows", true)) trip.add("창문 닫기");
    if (controller.settings.getBoolean("autoStop", true) && !controller.sharedVehicle())
      trip.add("자동 종료");
    if (controller.settings.getBoolean("unlockOnPark", false)) trip.add("주차 시 열기");
    menuSubs.get("trip").setText(trip.isEmpty() ? "모두 꺼짐" : String.join(" · ", trip));
    menuSubs.get("notify").setText(
        (DoorNotifications.enabled(this) ? "알림 허용" : "알림 꺼짐")
            + (controller.chargeNotify() ? " · 충전 완료 알림" : "")
            + (controller.parkedOnNotify() ? " · P 시동 알림" : "")
            + " · 배터리 설정");
    menuSubs.get("logs").setText("활동 보기 · 진단 공유 · 파일 저장");
    if (menuSubs.containsKey("watchkey"))
      menuSubs
          .get("watchkey")
          .setText(watchKey != null && watchKey.enabled() ? watchKey.statusLine() : "꺼짐");
    menuSubs.get("update").setText("현재 버전 " + appVersion);
  }

  /** The beacon OR/OR combine switch. It appears in settings and on the BLE page; update() syncs both. */
  private Switch combineToggle(LinearLayout parent, String tag) {
    Switch toggle = new Switch(this);
    toggle.setTag(tag);
    toggle.setText("보조 비콘으로 열기·잠금 (둘 중 하나라도)");
    toggle.setTextColor(TEXT);
    toggle.setMinHeight(dp(56));
    toggle.setChecked(controller.settings.getBoolean("beaconCombine", true));
    parent.addView(toggle);
    toggle.setOnCheckedChangeListener(
        (b, on) -> {
          if (updating) return;
          controller.settings.edit().putBoolean("beaconCombine", on).apply();
          controller.note(on ? "보조 비콘 결합 켜짐 · 둘 중 하나라도 가까우면 열림 · 멀어지면 잠금" : "보조 비콘 결합 꺼짐 · 관찰 전용");
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
   * While armed, {@code ring} (the circle) gets an outline and the label asks again.
   */
  private View.OnClickListener doubleTap(TextView label, String hint, View ring, Runnable action) {
    return v -> {
      CharSequence text = label.getText();
      armOrRun(
          v,
          () -> {
            label.setText(hint);
            GradientDrawable mark = new GradientDrawable();
            mark.setShape(GradientDrawable.OVAL);
            mark.setStroke(dp(2), TEXT);
            ring.setForeground(mark);
          },
          () -> {
            label.setText(text);
            ring.setForeground(null);
          },
          action);
    };
  }

  /**
   * The first tap on {@code v} arms it ({@code show}); a second tap within 3 seconds runs
   * {@code action}. Only one control is armed at a time: arming another one, or the timeout, runs
   * {@code hide}.
   */
  private void armOrRun(View v, Runnable show, Runnable hide, Runnable action) {
    if (armedCommand == v) {
      disarmCommand();
      action.run();
      return;
    }
    disarmCommand();
    CharSequence description = v.getContentDescription();
    armedCommand = v;
    armedUndo =
        () -> {
          hide.run();
          v.setContentDescription(description);
        };
    show.run();
    v.setContentDescription(description + ". 3초 안에 한 번 더 누르면 실행합니다");
    v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
    handler.postDelayed(disarmCommand, 3000);
  }

  private void disarmCommand() {
    handler.removeCallbacks(disarmCommand);
    if (armedCommand == null) return;
    Runnable undo = armedUndo;
    armedCommand = null;
    armedUndo = null;
    undo.run();
  }

  private boolean supported(CloudClient.Command[] needs) {
    for (CloudClient.Command need : needs)
      if (!(need == CloudClient.Command.CLIMATE_ON
          ? CloudClient.hasClimate(controller.capabilities)
          : CloudClient.hasFeature(controller.capabilities, need.feature))) return false;
    return true;
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
    // The update time sits right of the title, next to the refresh button it belongs to.
    checkedTime = text(row, "업데이트 대기", 12, MUTED);
    checkedTime.setTag("statusCheckedTime");
    checkedTime.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
    checkedTime.setPadding(0, 0, dp(10), 0);
    checkedTime.setMaxLines(2); // Never cut the time off; wrap on narrow phones.
    checkedTime.setEllipsize(android.text.TextUtils.TruncateAt.END);
    checkedTime.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
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
    carHero.setTag("carHero");
    carHero.setPadding(0, dp(44), 0, 0); // The roof clears the corner badges.
    // 차량 찾기 lives on the bonnet: two taps within 3 seconds, like the other controls.
    carHero.setContentDescription("차량 찾기 · 본넷을 두 번 누르면 경적과 비상등으로 위치를 알립니다");
    carHero.setOnClickListener(
        v ->
            armOrRun(
                v,
                () -> carHero.armed(true),
                () -> carHero.armed(false),
                () -> controller.accessory(CloudClient.Command.FIND_CAR)));
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
    // The odometer rides in the picture's bottom-right corner, under the rear wheel.
    odometerText = new TextView(this);
    odometerText.setTag("odometer");
    odometerText.setTextSize(11);
    odometerText.setTextColor(SUBTLE);
    odometerText.setSingleLine(true);
    odometerText.setPadding(dp(8), dp(3), dp(8), dp(3));
    odometerText.setBackground(background(0xb3ffffff, 10));
    odometerText.setVisibility(View.GONE);
    hero.addView(
        odometerText, new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END));
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
    column.addView(circle, new LinearLayout.LayoutParams(dp(52), dp(52)));
    TextView name = text(column, label, 12, TEXT);
    name.setPadding(0, dp(7), 0, 0);
    name.setMaxLines(1); // setSingleLine + centered gravity scrolls the text out of view.
    name.setLayoutParams(new LinearLayout.LayoutParams(-2, -2));
    column.setOnClickListener(doubleTap(name, "한 번 더", circle, action));
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
    text(head, "3초 안에 두 번 탭 · 본넷은 차량 찾기", 11, MUTED).setPadding(dp(8), 0, 0, 0);
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
    roundButton(
        roundRow, R.drawable.ic_window_close, "창문 닫기", TEXT, "control_window_close",
        new CloudClient.Command[] {CloudClient.Command.CLOSE_WINDOWS},
        () -> controller.accessory(CloudClient.Command.CLOSE_WINDOWS));
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
    calScroll.setVisibility(page.equals("calibrate") ? View.VISIBLE : View.GONE);
    for (Map.Entry<String, ScrollView> detail : detailPages.entrySet())
      detail.getValue().setVisibility(page.equals(detail.getKey()) ? View.VISIBLE : View.GONE);
    // Home shows the brand, BLE pill and menu; sub pages a back arrow and their title — centred
    // on the BLE page, beside the arrow in settings (as in the two designs).
    boolean centred = page.equals("ble");
    back.setVisibility(home ? View.GONE : View.VISIBLE);
    titleSpacer.setVisibility(centred ? View.VISIBLE : View.GONE);
    logoTile.setVisibility(home ? View.VISIBLE : View.GONE);
    bleStatusPill.setVisibility(home ? View.VISIBLE : View.GONE);
    menu.setVisibility(home ? View.VISIBLE : View.GONE);
    title.setGravity(centred ? Gravity.CENTER : Gravity.CENTER_VERTICAL | Gravity.START);
    title.setAutoSizeTextTypeUniformWithConfiguration(
        14, home ? 19 : centred ? 16 : 18, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
    title.setPadding(home ? dp(10) : centred ? 0 : dp(4), 0, 0, 0);
    title.setText(
        home
            ? "D-Autolock"
            : page.equals("settings")
                ? "설정"
                : page.equals("setup")
                    ? "처음 설정"
                    : centred
                        ? "BLE 신호 설정"
                        : page.equals("calibrate")
                            ? "위치로 자동 설정"
                            : detailTitles.getOrDefault(page, "설정"));
  }

  private void navigateBack() {
    if (page.equals("calibrate")) {
      resumeAfterCalibration(); // Measured spots are dropped; automation comes back.
      showPage(calReturn);
    } else if (page.equals("ble")) showPage(bleReturn); // Unsaved threshold drafts are dropped.
    else if (page.startsWith("settings/")) showPage("settings");
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
    checkedTime.setText(controller.statusReading ? "확인 중…" : checked);
    String odometer = DashboardStatus.odometer(controller.snapshot);
    odometerText.setText(odometer == null ? "" : odometer);
    odometerText.setVisibility(odometer == null ? View.GONE : View.VISIBLE);
    beaconDevice.setText(
        "보조 BLE(비콘): "
            + (beaconOn ? controller.settings.getString("beaconName", "등록됨") : "없음"));
    showMenuSummaries(beaconOn);
    // update() runs about once a second while monitoring; that sets the history bar pace.
    if (now - lastHistoryAt >= 900) {
      lastHistoryAt = now;
      history[0].push(controller.averageRssi, range[0].near(), range[0].far());
      history[1].push(controller.beaconRssi, range[1].near(), range[1].far());
    }
    if (page.equals("ble")) refreshBle();
    if (page.equals("calibrate")) refreshCalibrate();
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
    carAudioState.setText(
        controller.vehicleLink.headUnitAddress().isEmpty()
            ? "차 블루투스 · 차량 보조 앱을 QR로 등록하면 사용할 수 있어요"
            : controller.carAudio.connected()
                ? "차 블루투스 · 연결됨 (차 안)"
                : "차 블루투스 · 연결 안 됨");
    if (watchKeySwitch != null) {
      watchKeySwitch.setChecked(watchKey.enabled());
      watchKeyRegister.setEnabled(watchKey.enabled() && watchKeyRun == null);
      if (watchKeyRun == null)
        watchKeyStatus.setText(watchKey.enabled() ? watchKey.statusLine() : "꺼짐");
      if (watchBleSection != null) {
        boolean showBle = watchKey.enabled() && watchKey.hasKey();
        watchBleSection.setVisibility(showBle ? View.VISIBLE : View.GONE);
        if (showBle && watchBleRun == null) {
          watchBleUnlock.setEnabled(true);
          watchBleLock.setEnabled(true);
        }
        if (watchKeyAutoSwitch != null)
          watchKeyAutoSwitch.setChecked(controller.settings.getBoolean("watchKeyAuto", true));
      }
    }
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
      boolean enabled = ready && supported(commandNeeds.get(i));
      setTreeEnabled(commands.get(i), enabled);
      commands.get(i).setAlpha(enabled ? 1f : .45f);
    }
    // The bonnet button: the picture stays at full strength, only the tap is off.
    carHero.setEnabled(ready && supported(new CloudClient.Command[] {CloudClient.Command.FIND_CAR}));
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
    } else if (primary == SignalBand.LOCK
        || primary == SignalBand.LOST
        || beacon == SignalBand.LOCK) {
      // Neither near and either far (a lost beacon alone never locks; a lost vehicle BLE does).
      state = "멀어짐 · 잠금 구간";
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
                    + " 도어가 잠긴 상태에서 보내면 차량이 경적을 한 번 울릴 수 있습니다."
                    + "\n\n차 옆에서 차량 보조 앱이 실시간 P단을 확인해 주지 못하면(멀리서 끌 때) 원격 공조 끄기로 대신 요청합니다. 원격 시동 상태면 1분 안에 차 전원이 꺼지고, 누가 타서 쓰는 중인 차는 꺼지지 않습니다."
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
    // Measure the spots instead of tuning numbers: sets both signals at once.
    LinearLayout auto = new LinearLayout(this);
    auto.setTag("calibrateOpen");
    auto.setGravity(Gravity.CENTER_VERTICAL);
    auto.setPadding(dp(14), dp(12), dp(10), dp(12));
    auto.setBackground(pressable(background(ACCENT_SOFT, 14)));
    auto.setOnClickListener(v -> openCalibrate());
    icon(auto, R.drawable.ic_find_car, ACCENT, 18);
    LinearLayout autoTexts = new LinearLayout(this);
    autoTexts.setOrientation(LinearLayout.VERTICAL);
    autoTexts.setPadding(dp(12), 0, dp(6), 0);
    auto.addView(autoTexts, new LinearLayout.LayoutParams(0, -2, 1));
    bold(autoTexts, "위치로 자동 설정", 13, ACCENT);
    text(autoTexts, "열림·잠금 위치를 몇 곳 측정하면 두 신호의 기준을 자동으로 정해요", 11, SUBTLE)
        .setPadding(0, dp(2), 0, 0);
    icon(auto, R.drawable.ic_back, ACCENT, 14).setRotation(180);
    LinearLayout.LayoutParams autoParams = new LinearLayout.LayoutParams(-1, -2);
    autoParams.bottomMargin = dp(10);
    content.addView(auto, autoParams);
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
            + " 하나만 멀어져도 잠깁니다(다른 쪽이 아직 가까우면 기다림).",
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
        "켜면 차량 BLE 또는 비콘 중 하나만 가까워도 열리고, 하나만 멀어져도 잠깁니다. 다른 쪽이 아직 가까우면 잠그지 않습니다. 비콘이 안 잡히는 것만으로는 잠그지 않고, 그때는 차량 BLE만으로 판단합니다.",
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

  // 위치로 자동 설정, step by step: the user stands at six spots where the doors should open, sits
  // in the driver's seat, then stands at six where they should lock, measuring each;
  // ThresholdCalibrator turns the readings into both signals' thresholds. Automatic open/lock
  // pauses while this page is up.

  private void buildCalibratePage(FrameLayout pages) {
    calScroll = new ScrollView(this);
    calScroll.setTag("calibratePage");
    calScroll.setFillViewport(true);
    LinearLayout content = new LinearLayout(this);
    content.setOrientation(LinearLayout.VERTICAL);
    content.setPadding(dp(20), dp(4), dp(20), dp(28));
    content.setClipToPadding(false);
    calScroll.addView(content);
    pages.addView(calScroll);
    calScroll.setVisibility(View.GONE);
    calStepLabel = bold(content, "", 12, ACCENT);
    calStepLabel.setTag("calStepLabel");
    calProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
    calProgress.setMax(CAL_RESULT);
    calProgress.setProgressTintList(ColorStateList.valueOf(ACCENT));
    calProgress.setProgressBackgroundTintList(ColorStateList.valueOf(IDLE_SOFT));
    LinearLayout.LayoutParams bar = new LinearLayout.LayoutParams(-1, dp(4));
    bar.topMargin = dp(8);
    content.addView(calProgress, bar);
    LinearLayout card = card(content, null);
    calStepTitle = bold(card, "", 18, TEXT);
    calStepTitle.setTag("calStepTitle");
    calStepBody = text(card, "", 13, SUBTLE);
    calStepBody.setPadding(0, dp(6), 0, 0);
    calLive = bold(card, "", 14, TEXT);
    calLive.setTag("calLive");
    calLive.setPadding(0, dp(14), 0, 0);
    calMeasured = text(card, "", 12, OPEN);
    calMeasured.setTag("calMeasured");
    calNotice = text(card, "", 12, LOCK);
    calResult = text(card, "", 13, TEXT);
    calResult.setTag("calResult");
    calPrimary = primaryButton(content, "", v -> calPrimaryAction());
    calPrimary.setTag("calPrimary");
    LinearLayout.LayoutParams primary = (LinearLayout.LayoutParams) calPrimary.getLayoutParams();
    primary.topMargin = dp(16);
    primary.height = dp(50);
    LinearLayout nav = new LinearLayout(this);
    content.addView(nav, new LinearLayout.LayoutParams(-1, -2));
    calBack = button(nav, "이전", v -> calGo(calStep - 1));
    calBack.setTag("calBack");
    calSkip = button(nav, "건너뛰기", v -> calSkipAction());
    calSkip.setTag("calSkip");
    for (Button b : new Button[] {calBack, calSkip}) {
      LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, dp(44), 1);
      half.topMargin = dp(8);
      half.leftMargin = b == calSkip ? dp(8) : 0;
      b.setLayoutParams(half);
    }
  }

  private void openCalibrate() {
    if (!page.equals("calibrate")) calReturn = page;
    Arrays.fill(calOpen, null);
    Arrays.fill(calLock, null);
    calInside = null;
    calStep = 0;
    pauseForCalibration();
    showPage("calibrate");
    calGo(0);
  }

  /** Keeps scanning (it is what gets measured) but stops automatic door commands meanwhile. */
  private void pauseForCalibration() {
    if (controller.monitoring && controller.autoEnabled) {
      controller.auto(false);
      calPausedAuto = true;
      controller.note("위치 측정 중 · 자동 열기·잠금 잠시 멈춤");
    } else if (!controller.monitoring && controller.setupReady() && permissions(false)) {
      controller.startMonitoring(false, false); // Observe only.
      calStartedWatch = true;
    }
  }

  /** Puts automation back the way it was; also run when the app goes to the background. */
  private void resumeAfterCalibration() {
    cancelMeasure();
    if (calPausedAuto) {
      calPausedAuto = false;
      if (controller.monitoring) controller.auto(true);
    }
    if (calStartedWatch) {
      calStartedWatch = false;
      controller.stop();
    }
  }

  private void cancelMeasure() {
    if (calMeasure != null) handler.removeCallbacks(calMeasure);
    calMeasure = null;
  }

  private static boolean calSpotStep(int step) {
    return step >= 1 && step < CAL_RESULT;
  }

  private void calGo(int step) {
    cancelMeasure();
    calStep = Math.max(0, Math.min(CAL_RESULT, step));
    calNotice.setText("");
    refreshCalibrate();
    calScroll.scrollTo(0, 0);
  }

  private void calPrimaryAction() {
    if (calStep == 0) calGo(1);
    else if (calStep == CAL_RESULT) applyCalibration();
    else measure();
  }

  /** On a spot: skip it (or move on once measured). On the result: start over. */
  private void calSkipAction() {
    if (calStep == CAL_RESULT) {
      Arrays.fill(calOpen, null);
      Arrays.fill(calLock, null);
      calInside = null;
      calGo(1);
    } else calGo(calStep + 1);
  }

  /** Samples both smoothed signals for 4 s, keeps the medians for this step, then moves on. */
  private void measure() {
    if (calMeasure != null || !calSpotStep(calStep)) return;
    if (!controller.monitoring) {
      calNotice.setText("거리 감지가 켜져 있어야 측정할 수 있어요");
      refreshCalibrate();
      return;
    }
    int step = calStep;
    boolean open = step <= CAL_SPOTS, inside = step == CAL_INSIDE;
    List<Double> primary = new ArrayList<>(), beacon = new ArrayList<>();
    calMeasureUntil = SystemClock.elapsedRealtime() + 4000;
    calNotice.setText("");
    calMeasure =
        new Runnable() {
          public void run() {
            if (!Double.isNaN(controller.averageRssi)) primary.add(controller.averageRssi);
            if (!Double.isNaN(controller.beaconRssi)) beacon.add(controller.beaconRssi);
            if (SystemClock.elapsedRealtime() < calMeasureUntil) {
              refreshCalibrate(); // Countdown.
              handler.postDelayed(this, 250);
              return;
            }
            calMeasure = null;
            ThresholdCalibrator.Spot spot =
                new ThresholdCalibrator.Spot(median(primary), median(beacon));
            if (open && Double.isNaN(spot.primary) && Double.isNaN(spot.beacon)) {
              // Nothing heard where it should open: this spot would teach nothing.
              calNotice.setText("이 위치에서 신호를 받지 못했어요. 다시 측정하거나 건너뛰세요.");
              refreshCalibrate();
              return;
            }
            if (inside) calInside = spot;
            else (open ? calOpen : calLock)[open ? step - 1 : step - 1 - CAL_INSIDE] = spot;
            controller.diagnostics.record(
                "CAL_SPOT",
                (open ? "open " + step : inside ? "inside" : "lock " + (step - CAL_INSIDE))
                    + " primary="
                    + calValue(spot.primary)
                    + " beacon="
                    + calValue(spot.beacon)
                    + " samples="
                    + primary.size()
                    + "/"
                    + beacon.size());
            calGo(step + 1);
          }
        };
    handler.post(calMeasure);
    refreshCalibrate();
  }

  private static double median(List<Double> values) {
    if (values.isEmpty()) return Double.NaN;
    List<Double> sorted = new ArrayList<>(values);
    Collections.sort(sorted);
    return sorted.get(sorted.size() / 2);
  }

  private static String calValue(double rssi) {
    return Double.isNaN(rssi) ? "lost" : String.valueOf(Math.round(rssi));
  }

  private static String reading(double rssi) {
    return Double.isNaN(rssi) ? "끊김" : RangeSlider.dbm((int) Math.round(rssi)) + " dBm";
  }

  private static List<ThresholdCalibrator.Spot> measured(ThresholdCalibrator.Spot[] spots) {
    List<ThresholdCalibrator.Spot> out = new ArrayList<>();
    for (ThresholdCalibrator.Spot s : spots) if (s != null) out.add(s);
    return out;
  }

  private String spotText(ThresholdCalibrator.Spot s, boolean beaconOn) {
    return "차량 BLE " + reading(s.primary) + (beaconOn ? " · 보조 비콘 " + reading(s.beacon) : "");
  }

  private void refreshCalibrate() {
    if (calScroll == null) return;
    boolean beaconOn = controller.beaconConfigured(), measuring = calMeasure != null;
    boolean spotStep = calSpotStep(calStep), result = calStep == CAL_RESULT;
    calProgress.setProgress(calStep);
    calLive.setText(
        "지금 "
            + spotText(
                new ThresholdCalibrator.Spot(
                    controller.monitoring ? controller.averageRssi : Double.NaN,
                    controller.monitoring ? controller.beaconRssi : Double.NaN),
                beaconOn));
    calLive.setVisibility(result ? View.GONE : View.VISIBLE);
    String watchOff = "거리 감지가 꺼져 있어요. 감지가 시작되면 측정할 수 있어요.";
    if (!controller.monitoring && !result && calNotice.getText().length() == 0)
      calNotice.setText(watchOff);
    if (controller.monitoring && watchOff.contentEquals(calNotice.getText())) calNotice.setText("");
    calNotice.setVisibility(calNotice.getText().length() == 0 ? View.GONE : View.VISIBLE);
    calBack.setVisibility(calStep == 0 ? View.INVISIBLE : View.VISIBLE);
    calSkip.setVisibility(calStep == 0 ? View.INVISIBLE : View.VISIBLE);
    calMeasured.setVisibility(View.GONE);
    calResult.setVisibility(result ? View.VISIBLE : View.GONE);
    calPrimary.setEnabled(!measuring && (!spotStep || controller.monitoring));
    if (calStep == 0) {
      calStepLabel.setText("준비");
      calStepTitle.setText("위치로 자동 설정");
      calStepBody.setText(
          "문이 열렸으면 하는 위치 "
              + CAL_SPOTS
              + "곳, 운전석, 잠겼으면 하는 위치 "
              + CAL_SPOTS
              + "곳에서 차례로 신호를 측정하면 차량 BLE와 보조 비콘의 기준을 자동으로 계산해요.\n\n"
              + "· 차 주위를 한 바퀴 돌 수 있는, 사방이 트인 곳에서 하면 가장 정확해요.\n"
              + "· 휴대폰은 평소처럼 주머니나 손에 두세요.\n"
              + "· 위치마다 잠시 멈춘 뒤 측정을 누르면 4초간 측정하고 다음 단계로 넘어가요.\n"
              + "· 측정하는 동안 자동 열기·잠금은 잠시 멈춰요.\n"
              + "· 벽이나 다른 차가 많은 곳은 신호가 달라질 수 있어요. 평소 주차하는 곳에서 다르게 동작하면 그곳에서 한 번 더 해 보세요.");
      calPrimary.setText("시작하기");
    } else if (spotStep) {
      boolean open = calStep <= CAL_SPOTS, inside = calStep == CAL_INSIDE;
      int index = open ? calStep - 1 : calStep - 1 - CAL_INSIDE;
      String[] place = inside ? CAL_INSIDE_PLACE : (open ? CAL_OPEN_PLACES : CAL_LOCK_PLACES)[index];
      ThresholdCalibrator.Spot done = inside ? calInside : (open ? calOpen : calLock)[index];
      calStepLabel.setText(
          inside ? "차 안" : (open ? "도어 열림 위치 " : "도어 잠금 위치 ") + (index + 1) + " / " + CAL_SPOTS);
      calStepTitle.setText(place[0]);
      calStepBody.setText(
          (!open && !inside && index == 0 ? "이제 문이 잠겼으면 하는 곳이에요. 차에서 걸어 나가며 측정해요.\n\n" : "")
              + place[1]);
      if (done != null) {
        calMeasured.setText("측정됨 · " + spotText(done, beaconOn));
        calMeasured.setVisibility(View.VISIBLE);
      }
      long left = Math.max(1, (calMeasureUntil - SystemClock.elapsedRealtime() + 999) / 1000);
      calPrimary.setText(
          measuring
              ? "측정 중… 그대로 계세요 (" + left + ")"
              : done == null ? "이 위치에서 측정" : "다시 측정");
      calSkip.setText(done == null ? "건너뛰기" : "다음");
    } else {
      calStepLabel.setText("결과");
      calStepTitle.setText("계산 결과");
      calSkip.setText("처음부터 다시");
      calPrimary.setText("이 기준으로 저장");
      List<ThresholdCalibrator.Spot> open = measured(calOpen), lock = measured(calLock);
      List<ThresholdCalibrator.Spot> inside = measured(new ThresholdCalibrator.Spot[] {calInside});
      if (open.isEmpty() || lock.isEmpty()) {
        calResultValue = null;
        calStepBody.setText(
            "열림 위치와 잠금 위치를 각각 1곳 이상 측정해야 계산할 수 있어요. 이전으로 돌아가 측정해 주세요.");
        calResult.setText("");
        calPrimary.setEnabled(false);
      } else {
        calStepBody.setText("측정한 위치를 바탕으로 계산한 기준이에요. 저장하면 바로 적용돼요.");
        calResult.setText(resultText(open, inside, lock, beaconOn));
      }
    }
    calPrimary.setAlpha(calPrimary.isEnabled() ? 1f : .45f);
  }

  private String resultText(
      List<ThresholdCalibrator.Spot> open,
      List<ThresholdCalibrator.Spot> inside,
      List<ThresholdCalibrator.Spot> lock,
      boolean beaconOn) {
    ThresholdCalibrator.Pair current =
        new ThresholdCalibrator.Pair(
            controller.settings.getInt("near", Controller.DEFAULT_NEAR),
            controller.settings.getInt("far", Controller.DEFAULT_FAR));
    ThresholdCalibrator.Pair currentBeacon =
        beaconOn
            ? new ThresholdCalibrator.Pair(
                controller.settings.getInt("beaconNear", Controller.DEFAULT_BEACON_NEAR),
                controller.settings.getInt("beaconFar", Controller.DEFAULT_BEACON_FAR))
            : null;
    ThresholdCalibrator.Result r =
        ThresholdCalibrator.calibrate(open, inside, lock, current, currentBeacon);
    calResultValue = r;
    StringBuilder out =
        new StringBuilder()
            .append("차량 BLE   열림 ")
            .append(RangeSlider.dbm(r.primary.near))
            .append(" 이상 · 잠금 ")
            .append(RangeSlider.dbm(r.primary.far))
            .append(" 이하")
            .append(r.primaryMeasured ? "" : " (측정값 없음 · 지금 값 유지)");
    if (r.beacon != null)
      out.append("\n보조 비콘   열림 ")
          .append(RangeSlider.dbm(r.beacon.near))
          .append(" 이상 · 잠금 ")
          .append(RangeSlider.dbm(r.beacon.far))
          .append(" 이하")
          .append(r.beaconMeasured ? "" : " (측정값 없음 · 지금 값 유지)");
    out.append("\n\n도어 열림 위치 ")
        .append(open.size())
        .append("곳 중 ")
        .append(r.openCovered)
        .append("곳, 도어 잠금 위치 ")
        .append(lock.size())
        .append("곳 중 ")
        .append(r.lockCovered)
        .append("곳에서 동작해요.");
    if (r.openCovered < open.size())
      out.append(
          "\n\n일부 열림 위치는 잠금 위치와 신호 차이가 작아 열리지 않을 수 있어요. 그 위치를 차에 조금 더 가깝게 다시 재 보세요.");
    if (r.lockCovered < lock.size())
      out.append(
          "\n\n일부 잠금 위치에서는 아직 안 잠기고 몇 걸음 더 가면 잠겨요. 신호는 순간순간 몇 dB씩 흔들려서, 열림과 잠금 사이에 여유를 둬 차 옆이나 차 안에서 잘못 잠기지 않게 했어요.");
    if (!inside.isEmpty())
      out.append(
          r.insideKept == inside.size()
              ? "\n\n운전석에서는 잠기지 않아요."
              : "\n\n운전석에서 차량 BLE 신호가 너무 약하거나 끊겨 잠길 수 있어요. 휴대폰을 두는 곳을 바꿔 다시 재 보세요.");
    return out.toString();
  }

  private void applyCalibration() {
    ThresholdCalibrator.Result r = calResultValue;
    if (r == null) return;
    // Back to automatic first, so the restart that saving triggers comes up automatic again.
    if (calPausedAuto) {
      calPausedAuto = false;
      if (controller.monitoring) controller.auto(true);
    }
    calStartedWatch = false; // Keep watching with the new values.
    controller.diagnostics.record(
        "CAL_RESULT",
        "open="
            + r.openCovered
            + "/"
            + measured(calOpen).size()
            + " lock="
            + r.lockCovered
            + "/"
            + measured(calLock).size()
            + " inside="
            + r.insideKept
            + "/"
            + (calInside == null ? 0 : 1));
    boolean beacon = r.beacon != null && r.beaconMeasured && controller.beaconConfigured();
    try {
      controller.calibratedThresholds(
          r.primary.near,
          r.primary.far,
          beacon ? r.beacon.near : null,
          beacon ? r.beacon.far : null);
    } catch (Exception e) {
      Toast.makeText(this, "계산한 기준을 저장하지 못했어요. 다시 측정해 주세요", Toast.LENGTH_SHORT).show();
      return;
    }
    Toast.makeText(this, "위치 측정으로 기준을 저장했어요", Toast.LENGTH_SHORT).show();
    if (calReturn.equals("ble")) {
      loadBleDrafts(); // Show the saved values, not stale drafts.
      refreshBle();
    }
    showPage(calReturn);
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
    if (request == SHARE_REQUEST) askClearAfterShare();
  }

  private static final int SHARE_REQUEST = 52;

  /** Snapshot the log to a private file and open the share sheet (Telegram etc.). */
  private void shareDiagnostics() {
    controller.shareDiagnostics(
        file -> {
          if (file == null || isFinishing()) return;
          android.net.Uri uri = DiagnosticsShareProvider.uri(file);
          Intent send =
              new Intent(Intent.ACTION_SEND)
                  .setType("text/plain")
                  .putExtra(Intent.EXTRA_STREAM, uri)
                  .putExtra(Intent.EXTRA_SUBJECT, file.getName())
                  .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
          send.setClipData(ClipData.newRawUri(file.getName(), uri));
          try {
            startActivityForResult(Intent.createChooser(send, "진단 로그 공유"), SHARE_REQUEST);
          } catch (ActivityNotFoundException e) {
            controller.note("공유할 앱을 찾지 못했습니다. 진단 로그 파일 저장을 사용하세요");
          }
        });
  }

  /**
   * Whether it was really sent cannot be known, so clearing is the user's call — the same fresh
   * start the file export gives, without losing a log that never left the phone.
   */
  private void askClearAfterShare() {
    if (isFinishing()) return;
    new AlertDialog.Builder(this)
        .setTitle("보낸 로그를 비울까요?")
        .setMessage("비우면 지금부터 새로 기록해 다음에 보낼 로그가 짧아집니다. 보내지 않았다면 그대로 두세요.")
        .setPositiveButton(
            "비우기",
            (d, w) ->
                controller.diagnostics.clear(
                    () -> controller.note("진단 로그를 비웠습니다 · 새 로그를 기록합니다")))
        .setNegativeButton("그대로 두기", null)
        .show();
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
