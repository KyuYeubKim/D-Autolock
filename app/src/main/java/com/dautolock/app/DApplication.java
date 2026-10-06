package com.dautolock.app;

import android.app.Application;

public final class DApplication extends Application {
  private Controller controller;

  public void onCreate() {
    super.onCreate();
    controller = new Controller(this);
  }

  public Controller controller() {
    return controller;
  }
}
