package com.dautolock.app;

import static org.junit.Assert.*;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class DiagnosticsShareProviderTest {
  @Test
  public void servesOnlyTheSharedLogReadOnlyWithItsName() throws Exception {
    Context context = RuntimeEnvironment.getApplication();
    DiagnosticsShareProvider provider =
        Robolectric.setupContentProvider(
            DiagnosticsShareProvider.class, DiagnosticsShareProvider.AUTHORITY);
    File dir = new File(context.getCacheDir(), DiagnosticsShareProvider.DIR);
    assertTrue(dir.isDirectory() || dir.mkdirs());
    File log = new File(dir, "D-Autolock-diagnostics-20261103-080936.txt");
    Files.write(log.toPath(), "hello".getBytes(StandardCharsets.UTF_8));
    Uri uri = DiagnosticsShareProvider.uri(log);

    try (Cursor c = provider.query(uri, null, null, null, null)) {
      assertTrue(c.moveToFirst());
      assertEquals(log.getName(), c.getString(c.getColumnIndex(OpenableColumns.DISPLAY_NAME)));
      assertEquals(5, c.getLong(c.getColumnIndex(OpenableColumns.SIZE)));
    }
    try (InputStream in =
        new ParcelFileDescriptor.AutoCloseInputStream(provider.openFile(uri, "r"))) {
      assertEquals("hello", new String(in.readAllBytes(), StandardCharsets.UTF_8));
    }
    assertEquals("text/plain", provider.getType(uri));

    // Nothing else in the app's storage can be reached through it, and it never writes.
    File secret = new File(context.getCacheDir(), "secret.txt");
    Files.write(secret.toPath(), "x".getBytes(StandardCharsets.UTF_8));
    String base = "content://" + DiagnosticsShareProvider.AUTHORITY + "/";
    assertNull(provider.query(Uri.parse(base + "secret.txt"), null, null, null, null));
    assertThrows(
        FileNotFoundException.class,
        () -> provider.openFile(Uri.parse(base + "..%2Fsecret.txt"), "r"));
    assertThrows(
        FileNotFoundException.class,
        () -> provider.openFile(Uri.parse(base + "x/" + log.getName()), "r"));
    assertThrows(FileNotFoundException.class, () -> provider.openFile(uri, "w"));
  }
}
