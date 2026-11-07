package com.dautolock.app.watchkey;

/** The watch session issued by {@code watch/login/gain/token}. No owner password/PIN is kept. */
public final class WatchTokens {
  public final String signToken;
  public final String encryToken;
  public final String identifier;
  public final String userType;
  /** VIN carried in the token response; the gain/vehicle and gain/bluetooth requests need it. */
  public final String vin;

  public WatchTokens(String signToken, String encryToken, String identifier, String userType) {
    this(signToken, encryToken, identifier, userType, "");
  }

  public WatchTokens(
      String signToken, String encryToken, String identifier, String userType, String vin) {
    this.signToken = signToken == null ? "" : signToken;
    this.encryToken = encryToken == null ? "" : encryToken;
    this.identifier = identifier == null ? "" : identifier;
    this.userType = userType == null ? "" : userType;
    this.vin = vin == null ? "" : vin;
  }

  public boolean valid() {
    return !signToken.isEmpty() && !encryToken.isEmpty();
  }
}
