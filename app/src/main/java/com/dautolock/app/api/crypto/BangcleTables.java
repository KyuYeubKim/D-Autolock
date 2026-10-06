package com.dautolock.app.api.crypto;

public class BangcleTables {
  public final byte[] invRound;
  public final byte[] invXor;
  public final byte[] invFirst;
  public final byte[] round;
  public final byte[] xor;
  public final byte[] finalTable;
  public final byte[] permDecrypt;
  public final byte[] permEncrypt;

  public BangcleTables(
      byte[] invRound,
      byte[] invXor,
      byte[] invFirst,
      byte[] round,
      byte[] xor,
      byte[] finalTable,
      byte[] permDecrypt,
      byte[] permEncrypt) {
    this.invRound = invRound;
    this.invXor = invXor;
    this.invFirst = invFirst;
    this.round = round;
    this.xor = xor;
    this.finalTable = finalTable;
    this.permDecrypt = permDecrypt;
    this.permEncrypt = permEncrypt;
  }
}
