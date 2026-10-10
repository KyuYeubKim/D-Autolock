package com.dautolock.bridge;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

/**
 * Periodic watchdog that re-ensures the vehicle-state server is running, so the Bridge survives the
 * head unit force-stopping it (some BYD ROMs stop third-party apps on standby). A job started while
 * running may start a foreground service even on OSes that block background starts. BOOT_COMPLETED
 * and the phone's Bluetooth connect are the immediate triggers; this is the slow safety net.
 */
public final class BridgeJobService extends JobService {
  private static final int JOB_ID = 4482;
  private static final long INTERVAL_MS = 15 * 60 * 1000L; // JobScheduler minimum period.

  static void schedule(Context context) {
    try {
      JobScheduler scheduler = context.getSystemService(JobScheduler.class);
      if (scheduler == null) return;
      scheduler.schedule(
          new JobInfo.Builder(JOB_ID, new ComponentName(context, BridgeJobService.class))
              .setPersisted(true) // Survives reboot (needs RECEIVE_BOOT_COMPLETED).
              .setPeriodic(INTERVAL_MS)
              .build());
    } catch (Exception ignored) {
    }
  }

  @Override
  public boolean onStartJob(JobParameters params) {
    BootLog.add(this, "JobScheduler 워치독 · 서비스 재확인");
    BridgeBootReceiver.start(this, "job");
    return false; // Work is synchronous; nothing left running on a background thread.
  }

  @Override
  public boolean onStopJob(JobParameters params) {
    return true; // Reschedule if the system stopped us early.
  }
}
