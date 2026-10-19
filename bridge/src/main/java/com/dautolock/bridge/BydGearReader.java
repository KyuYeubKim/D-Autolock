package com.dautolock.bridge;

import android.content.*;
import android.content.pm.PackageManager;
import com.dautolock.link.LinkProtocol;
import java.lang.reflect.*;

/** A narrow read-only adapter. Never instantiate the reference app's simulation provider. */
final class BydGearReader {
  private final Context context;
  private Object device;
  private int initQuality = 1;
  volatile String detail = "차량 API 확인 전";
  // Reflection is resolved once: the getter Methods and the fixed parking-brake constants never
  // change, so re-finding them on every read wasted CPU on a weak head-unit and the car SDK logs a
  // line per call under this app's name.
  private Method modeMethod, currentMethod, epbMethod;
  private boolean reflectionResolved;
  private Integer engagedConst, releasedConst;
  // When the car gear API stays unavailable, back off instead of re-calling getInstance twice a
  // second (each attempt makes the SDK log). 30 s → … → 5 min, reset implicitly once device is set.
  private long nextInitAttempt;
  private long initBackoffMs = 30000;

  BydGearReader(Context context) {
    this.context = context.getApplicationContext();
  }

  BydGearReader(Object device) {
    this.context = null;
    this.device = device;
  }

  private void initialize() {
    if (device != null || context == null) return;
    long now = android.os.SystemClock.elapsedRealtime();
    if (nextInitAttempt != 0 && now < nextInitAttempt) return; // Still backing off.
    try {
      Class<?> c = Class.forName("android.hardware.bydauto.gearbox.BYDAutoGearboxDevice");
      device = c.getMethod("getInstance", Context.class).invoke(null, new GetterContext(context));
      initQuality = device == null ? 1 : 0;
    } catch (Throwable e) {
      initQuality = security(e) ? 2 : 1;
    }
    if (device == null) {
      nextInitAttempt = now + initBackoffMs;
      initBackoffMs = Math.min(300000, initBackoffMs * 2); // 30 s → 1 m → … → 5 m.
    }
  }

  static boolean security(Throwable e) {
    while (e.getCause() != null && e.getCause() != e) e = e.getCause();
    return e instanceof SecurityException;
  }

  private static final class Value {
    int raw = LinkProtocol.MISSING;
    boolean denied;
  }

  private Value value(Method method) {
    Value v = new Value();
    if (method == null) return v; // Getter absent on this firmware: leave raw MISSING, not denied.
    try {
      Object raw = method.invoke(device);
      // Never turn null, void, Boolean or a fractional value into zero/N.
      if (raw instanceof Integer) v.raw = (Integer) raw;
    } catch (Throwable e) {
      v.denied = security(e);
    }
    return v;
  }

  private Method method(String name) {
    try {
      return device.getClass().getMethod(name);
    } catch (Throwable e) {
      return null;
    }
  }

  private void resolveReflection() {
    if (reflectionResolved) return;
    modeMethod = method("getGearboxAutoModeType");
    currentMethod = method("getCurrentGear");
    epbMethod = method("getParkBrakeSwitch");
    engagedConst = constant("GEARBOX_PARK_BREAK_SWITCH_VALID");
    releasedConst = constant("GEARBOX_PARK_BREAK_SWITCH_INVALID");
    reflectionResolved = true;
  }

  private Integer constant(String name) {
    try {
      Object n = device.getClass().getField(name).get(null);
      return n instanceof Integer ? (Integer) n : null;
    } catch (Throwable e) {
      return null;
    }
  }

  static int mode(int v) {
    return v == 1
        ? LinkProtocol.P
        : v == 2
            ? LinkProtocol.R
            : v == 3
                ? LinkProtocol.N
                : v == 4 || v == 5 || v == 6 ? LinkProtocol.D : LinkProtocol.UNKNOWN;
  }

  static int current(int v) {
    return v == 3
        ? LinkProtocol.P
        : v == 1
            ? LinkProtocol.R
            : v == 0 ? LinkProtocol.N : v == 2 ? LinkProtocol.D : LinkProtocol.UNKNOWN;
  }

  synchronized LinkProtocol.Sample read() {
    initialize();
    if (device == null) {
      detail = initQuality == 2 ? "기어 API 권한 거부" : "BYD 기어 API 없음 · 차량 DiLink에서 실행하세요";
      return new LinkProtocol.Sample(
          -1, -1, LinkProtocol.MISSING, LinkProtocol.MISSING, LinkProtocol.MISSING, initQuality);
    }
    resolveReflection();
    Value a = value(modeMethod), b = value(currentMethod), epb = value(epbMethod);
    int first = mode(a.raw), second = current(b.raw), gear = first != -1 ? first : second;
    int quality = gear == -1 ? (a.denied || b.denied ? 2 : 1) : 0;
    if (first != -1 && second != -1 && first != second) {
      gear = -1;
      quality = 3;
    }
    Integer engaged = engagedConst, released = releasedConst;
    int brake = -1;
    // Read the firmware's named constants. An arbitrary 0/1 mapping is not assumed.
    if (engaged != null && released != null && !engaged.equals(released))
      brake = epb.raw == engaged ? 1 : epb.raw == released ? 0 : -1;
    LinkProtocol.Sample s = new LinkProtocol.Sample(gear, brake, a.raw, b.raw, epb.raw, quality);
    detail = s.diagnostic() + "\n주차브레이크 상수: 체결=" + engaged + " / 해제=" + released;
    return s;
  }

  /**
   * The supplied DiLink implementation uses a local Context check for privileged GET. Restrict
   * compatibility to GEARBOX_GET only; COMMON and the Binder service still check actual
   * permissions. No setter is exposed and no permission is granted at OS level.
   */
  static final class GetterContext extends ContextWrapper {
    GetterContext(Context c) {
      super(c);
    }

    private boolean getter(String p) {
      return "android.permission.BYDAUTO_GEARBOX_GET".equals(p);
    }

    @Override
    public Context getApplicationContext() {
      return this;
    }

    @Override
    public int checkSelfPermission(String p) {
      return getter(p) ? PackageManager.PERMISSION_GRANTED : super.checkSelfPermission(p);
    }

    @Override
    public int checkCallingOrSelfPermission(String p) {
      return getter(p) ? PackageManager.PERMISSION_GRANTED : super.checkCallingOrSelfPermission(p);
    }

    @Override
    public int checkCallingPermission(String p) {
      return getter(p) ? PackageManager.PERMISSION_GRANTED : super.checkCallingPermission(p);
    }

    @Override
    public int checkPermission(String p, int pid, int uid) {
      return getter(p) ? PackageManager.PERMISSION_GRANTED : super.checkPermission(p, pid, uid);
    }

    @Override
    public void enforceCallingOrSelfPermission(String p, String message) {
      if (!getter(p)) super.enforceCallingOrSelfPermission(p, message);
    }

    @Override
    public void enforceCallingPermission(String p, String message) {
      if (!getter(p)) super.enforceCallingPermission(p, message);
    }

    @Override
    public void enforcePermission(String p, int pid, int uid, String message) {
      if (!getter(p)) super.enforcePermission(p, pid, uid, message);
    }
  }
}
