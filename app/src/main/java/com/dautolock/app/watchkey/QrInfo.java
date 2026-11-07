package com.dautolock.app.watchkey;

/** The QR the phone generates for the owner to approve with the official BYD app. */
public final class QrInfo {
  public final String uuid;
  public final String payload;

  public QrInfo(String uuid, String payload) {
    this.uuid = uuid == null ? "" : uuid;
    this.payload = payload == null ? "" : payload;
  }
}
