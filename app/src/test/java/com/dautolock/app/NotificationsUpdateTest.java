package com.dautolock.app;

import static org.junit.Assert.*;

import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.os.Build;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 34})
public class NotificationsUpdateTest {
  @Test
  public void foregroundNotificationHasExplicitDoorActionsAndStop() {
    Context context = RuntimeEnvironment.getApplication();
    Notification notification = DoorNotifications.ongoing(context, true);
    assertEquals(3, notification.actions.length);
    assertEquals("열기", notification.actions[0].title);
    assertEquals("닫기 · 잠금", notification.actions[1].title);
    Intent intent = Shadows.shadowOf(notification.actions[0].actionIntent).getSavedIntent();
    assertEquals(DoorActionReceiver.class.getName(), intent.getComponent().getClassName());
    assertEquals(DoorNotifications.UNLOCK, intent.getAction());
    assertTrue((notification.flags & Notification.FLAG_ONGOING_EVENT) != 0);
    if (Build.VERSION.SDK_INT >= 31) assertTrue(notification.actions[0].isAuthenticationRequired());
  }

  @Test
  public void updaterRejectsWrongPackageSignatureVersionAndDowngrade() {
    PackageInfo current = new PackageInfo();
    current.packageName = "com.dautolock.app";
    current.versionCode = 6;
    current.versionName = "0.2.4";
    current.signatures = new Signature[] {new Signature("001122")};
    PackageInfo candidate = new PackageInfo();
    candidate.packageName = current.packageName;
    candidate.versionCode = 7;
    candidate.versionName = "0.2.5";
    candidate.signatures = new Signature[] {new Signature("001122")};
    assertTrue(AutoUpdater.compatible(current, candidate, "0.2.5"));
    candidate.signatures = new Signature[] {new Signature("aabbcc")};
    assertFalse(AutoUpdater.compatible(current, candidate, "0.2.5"));
    candidate.signatures = current.signatures;
    candidate.versionCode = 6;
    assertFalse(AutoUpdater.compatible(current, candidate, "0.2.5"));
    candidate.versionCode = 7;
    candidate.packageName = "other.app";
    assertFalse(AutoUpdater.compatible(current, candidate, "0.2.5"));
    assertFalse(AutoUpdater.compatible(current, null, "0.2.5"));
  }

  @Test
  public void notificationReceiverDoesNotActForStoppedObservation() throws Exception {
    DApplication app = (DApplication) RuntimeEnvironment.getApplication();
    AccountPersistenceTest.await(app.controller());
    new DoorActionReceiver().onReceive(app, new Intent(DoorNotifications.UNLOCK));
    assertFalse(app.controller().busy());
  }
}
