package com.dautolock.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.List;

/**
 * Hands the share sheet a read-only copy of the diagnostics log (no AndroidX FileProvider here).
 * Serves only files named like a diagnostics export from its own cache folder, and only to apps
 * the share intent grants; it is not exported.
 */
public final class DiagnosticsShareProvider extends ContentProvider {
  static final String AUTHORITY = "com.dautolock.app.diagnostics";
  static final String DIR = "shared-logs";
  private static final String NAME = "D-Autolock-diagnostics-[0-9-]+\\.txt";

  static Uri uri(File file) {
    return new Uri.Builder().scheme("content").authority(AUTHORITY).appendPath(file.getName()).build();
  }

  private File file(Uri uri) throws FileNotFoundException {
    String name = uri.getLastPathSegment();
    if (getContext() == null
        || uri.getPathSegments().size() != 1
        || name == null
        || !name.matches(NAME)) throw new FileNotFoundException("not a shared log");
    File f = new File(new File(getContext().getCacheDir(), DIR), name);
    if (!f.isFile()) throw new FileNotFoundException("not a shared log");
    return f;
  }

  @Override
  public boolean onCreate() {
    return true;
  }

  @Override
  public String getType(Uri uri) {
    return "text/plain";
  }

  @Override
  public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
    if (!"r".equals(mode)) throw new FileNotFoundException("read only");
    return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY);
  }

  /** Name and size, which share targets such as messengers show for the attachment. */
  @Override
  public Cursor query(
      Uri uri, String[] projection, String selection, String[] args, String sortOrder) {
    File f;
    try {
      f = file(uri);
    } catch (FileNotFoundException e) {
      return null;
    }
    String[] wanted =
        projection == null
            ? new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}
            : projection;
    List<String> columns = new ArrayList<>();
    List<Object> values = new ArrayList<>();
    for (String column : wanted) {
      if (OpenableColumns.DISPLAY_NAME.equals(column)) {
        columns.add(column);
        values.add(f.getName());
      } else if (OpenableColumns.SIZE.equals(column)) {
        columns.add(column);
        values.add(f.length());
      }
    }
    MatrixCursor cursor = new MatrixCursor(columns.toArray(new String[0]), 1);
    cursor.addRow(values.toArray());
    return cursor;
  }

  @Override
  public Uri insert(Uri uri, ContentValues values) {
    throw new UnsupportedOperationException("read only");
  }

  @Override
  public int delete(Uri uri, String selection, String[] args) {
    throw new UnsupportedOperationException("read only");
  }

  @Override
  public int update(Uri uri, ContentValues values, String selection, String[] args) {
    throw new UnsupportedOperationException("read only");
  }
}
