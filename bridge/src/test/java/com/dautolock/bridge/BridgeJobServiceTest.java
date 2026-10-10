package com.dautolock.bridge;

import static org.junit.Assert.*;

import android.app.job.JobScheduler;
import android.content.Context;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 33})
public class BridgeJobServiceTest {
  @Test
  public void scheduleRegistersAPersistedPeriodicWatchdog() {
    Context context = RuntimeEnvironment.getApplication();
    JobScheduler scheduler = context.getSystemService(JobScheduler.class);
    assertTrue(scheduler.getAllPendingJobs().isEmpty());
    BridgeJobService.schedule(context);
    assertEquals(1, scheduler.getAllPendingJobs().size());
    android.app.job.JobInfo job = scheduler.getAllPendingJobs().get(0);
    assertTrue(job.isPersisted());
    assertTrue(job.isPeriodic());
    assertEquals(
        BridgeJobService.class.getName(), job.getService().getClassName());
  }
}
