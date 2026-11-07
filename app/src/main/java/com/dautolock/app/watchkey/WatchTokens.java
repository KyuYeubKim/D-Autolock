package com.dautolock.app.watchkey;

/** The watch session issued by {@code watch/login/gain/token}. No owner password/PIN is kept. */
public final class WatchTokens {
  public final String signToken;
  public final String encryToken;
  public final String identifier;
  public final String userType;

  public WatchTokens(String signToken, String encryToken, String identifier, String userType) {
    this.signToken = signToken == null ? "" : signToken;
    this.encryToken = encryToken == null ? "" : encryToken;
    this.identifier = identifier == null ? "" : identifier;
    this.userType = userType == null ? "" : userType;
  }

  public boolean valid() {
    return !signToken.isEmpty() && !encryToken.isEmpty();
  }
}
