package com.dautolock.app.api.crypto;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

public class BangcleCodec {

  private static final byte[] MAGIC = {'B', 'G', 'T', 'B'};
  private static final int VERSION = 1;
  private static final int TABLE_COUNT = 8;

  private final BangcleTables tables;

  public BangcleCodec() {
    this.tables = loadTablesFromResources();
  }

  private BangcleTables loadTablesFromResources() {
    try (InputStream is = getClass().getResourceAsStream("/bangcle_tables.bin")) {
      if (is == null) {
        throw new RuntimeException("bangcle_tables.bin not found in resources");
      }
      ByteArrayOutputStream buffer = new ByteArrayOutputStream();
      int nRead;
      byte[] data = new byte[16384];
      while ((nRead = is.read(data, 0, data.length)) != -1) {
        buffer.write(data, 0, nRead);
      }
      return loadTablesFromBin(buffer.toByteArray());
    } catch (Exception e) {
      throw new RuntimeException("Failed to load Bangcle tables", e);
    }
  }

  private BangcleTables loadTablesFromBin(byte[] data) {
    ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);

    byte[] magic = new byte[4];
    buf.get(magic);
    if (!Arrays.equals(magic, MAGIC)) {
      throw new RuntimeException("Bad magic");
    }

    int version = buf.getShort() & 0xFFFF;
    if (version != VERSION) {
      throw new RuntimeException("Unsupported table version: " + version);
    }

    int count = buf.getShort() & 0xFFFF;
    if (count != TABLE_COUNT) {
      throw new RuntimeException("Expected 8 tables, got " + count);
    }

    int[] offsets = new int[8];
    int[] lengths = new int[8];
    for (int i = 0; i < 8; i++) {
      offsets[i] = buf.getInt();
      lengths[i] = buf.getInt();
    }

    byte[] invRound = new byte[0x28000];
    byte[] invXor = new byte[0x3C000];
    byte[] invFirst = new byte[0x1000];
    byte[] round = new byte[0x28000];
    byte[] xor = new byte[0x3C000];
    byte[] finalTable = new byte[0x1000];
    byte[] permDecrypt = new byte[8];
    byte[] permEncrypt = new byte[8];

    System.arraycopy(data, offsets[0], invRound, 0, lengths[0]);
    System.arraycopy(data, offsets[1], invXor, 0, lengths[1]);
    System.arraycopy(data, offsets[2], invFirst, 0, lengths[2]);
    System.arraycopy(data, offsets[3], round, 0, lengths[3]);
    System.arraycopy(data, offsets[4], xor, 0, lengths[4]);
    System.arraycopy(data, offsets[5], finalTable, 0, lengths[5]);
    System.arraycopy(data, offsets[6], permDecrypt, 0, lengths[6]);
    System.arraycopy(data, offsets[7], permEncrypt, 0, lengths[7]);

    return new BangcleTables(
        invRound, invXor, invFirst, round, xor, finalTable, permDecrypt, permEncrypt);
  }

  private void prepareAesMatrix(byte[] inputBlock, byte[] output) {
    for (int col = 0; col < 4; col++) {
      for (int row = 0; row < 4; row++) {
        output[col * 8 + row] = inputBlock[col + row * 4];
      }
    }
  }

  private byte[] encryptBlockAuth(byte[] block, int roundEnd) {
    byte[] state = new byte[32];
    byte[] temp64 = new byte[64];
    byte[] tmp32 = new byte[32];
    byte[] output = new byte[16];

    prepareAesMatrix(block, state);
    int rounds = Math.min(9, Math.max(0, roundEnd));

    for (int rnd = 0; rnd < rounds; rnd++) {
      int l_var21 = rnd * 4;
      int perm_ptr = 0;

      for (int i = 0; i < 4; i++) {
        int b_var4 = tables.permEncrypt[perm_ptr] & 0xFF;
        int l_var16 = i * 8;
        int base = i * 16;

        for (int j = 0; j < 4; j++) {
          int u_var8 = (b_var4 + j) & 3;
          int byte_val = state[l_var16 + u_var8] & 0xFF;
          int idx = byte_val + (i + (l_var21 + u_var8) * 4) * 256;
          System.arraycopy(tables.round, idx * 4, temp64, base + j * 4, 4);
        }
        perm_ptr += 2;
      }

      int i_var16 = 1;
      for (int l_var22 = 0; l_var22 < 4; l_var22++) {
        int pb_var19_offset = l_var22;

        for (int l_var10 = 0; l_var10 < 4; l_var10++) {
          int local10 = temp64[pb_var19_offset] & 0xFF;
          int u_var7 = local10 & 0xF;
          int u_var26 = local10 & 0xF0;

          int local_f0 = temp64[pb_var19_offset + 0x10] & 0xFF;
          int local_f1 = temp64[pb_var19_offset + 0x20] & 0xFF;
          int local_f2 = temp64[pb_var19_offset + 0x30] & 0xFF;

          int l_var2 = l_var10 * 0x18 + rnd * 0x60;
          int i_var25 = i_var16;

          for (int l_var17 = 0; l_var17 < 3; l_var17++) {
            int b_var4_inner;
            if (l_var17 == 0) {
              b_var4_inner = local_f0;
            } else if (l_var17 == 1) {
              b_var4_inner = local_f1;
            } else {
              b_var4_inner = local_f2;
            }

            int u_var1 = (b_var4_inner << 4) & 0xFF;
            int u_var27 = u_var7 | u_var1;
            u_var26 = ((u_var26 >> 4) | ((b_var4_inner >> 4) << 4)) & 0xFF;

            int idx1 = (l_var2 + (i_var25 - 1)) * 0x100 + u_var27;
            u_var7 = tables.xor[idx1] & 0xF;

            int idx2 = (l_var2 + i_var25) * 0x100 + u_var26;
            int b_var4_new = tables.xor[idx2] & 0xFF;
            u_var26 = (b_var4_new & 0xF) << 4;
            i_var25 += 2;
          }

          state[l_var10 + l_var22 * 8] = (byte) ((u_var26 | u_var7) & 0xFF);
          pb_var19_offset += 4;
        }
        i_var16 += 6;
      }
    }

    if (roundEnd == 10) {
      System.arraycopy(state, 0, tmp32, 0, 32);
      int u_var13 = 3;
      int u_var9 = 2;
      int u_var11 = 1;
      int u_var8_enc = 0;

      for (int row = 0; row < 4; row++) {
        int row0 = (u_var8_enc + row) & 3;
        state[row] = tables.finalTable[(tmp32[row0] & 0xFF) + row0 * 0x400];

        int row1 = (u_var11 + row) & 3;
        state[8 + row] = tables.finalTable[(tmp32[8 + row1] & 0xFF) + row1 * 0x400 + 0x100];

        int row2 = (u_var9 + row) & 3;
        state[0x10 + row] = tables.finalTable[(tmp32[0x10 + row2] & 0xFF) + row2 * 0x400 + 0x200];

        int row3 = (u_var13 + row) & 3;
        state[0x18 + row] = tables.finalTable[(tmp32[0x18 + row3] & 0xFF) + row3 * 0x400 + 0x300];
      }
    }

    for (int col = 0; col < 4; col++) {
      for (int row = 0; row < 4; row++) {
        output[col + row * 4] = state[col * 8 + row];
      }
    }

    return output;
  }

  private byte[] decryptBlockAuth(byte[] block, int roundStart) {
    byte[] state = new byte[32];
    byte[] temp64 = new byte[64];
    byte[] tmp32 = new byte[32];
    byte[] output = new byte[16];

    prepareAesMatrix(block, state);

    int stopBound = Math.max(0, roundStart);
    for (int rnd = 9; rnd >= stopBound; rnd--) {
      int l_var20 = rnd;
      int l_var21 = l_var20 * 4;
      int perm_ptr = 0;

      for (int i = 0; i < 4; i++) {
        int b_var3 = tables.permDecrypt[perm_ptr] & 0xFF;
        int l_var16 = i * 8;
        int base = i * 16;

        for (int j = 0; j < 4; j++) {
          int u_var7 = (b_var3 + j) & 3;
          int byte_val = state[l_var16 + u_var7] & 0xFF;
          int idx = byte_val + (i + (l_var21 + u_var7) * 4) * 256;
          System.arraycopy(tables.invRound, idx * 4, temp64, base + j * 4, 4);
        }
        perm_ptr += 2;
      }

      int i_var15 = 1;
      for (int l_var21_xor = 0; l_var21_xor < 4; l_var21_xor++) {
        int pb_var18_offset = l_var21_xor;

        for (int l_var9_xor = 0; l_var9_xor < 4; l_var9_xor++) {
          int local10 = temp64[pb_var18_offset] & 0xFF;
          int u_var6 = local10 & 0xF;
          int u_var26 = local10 & 0xF0;

          int local_f0 = temp64[pb_var18_offset + 0x10] & 0xFF;
          int local_f1 = temp64[pb_var18_offset + 0x20] & 0xFF;
          int local_f2 = temp64[pb_var18_offset + 0x30] & 0xFF;

          int l_var2 = l_var9_xor * 0x18 + l_var20 * 0x60;
          int i_var25 = i_var15;

          for (int l_var16 = 0; l_var16 < 3; l_var16++) {
            int b_var3_inner;
            if (l_var16 == 0) {
              b_var3_inner = local_f0;
            } else if (l_var16 == 1) {
              b_var3_inner = local_f1;
            } else {
              b_var3_inner = local_f2;
            }

            int u_var1 = (b_var3_inner << 4) & 0xFF;
            int u_var27 = u_var6 | u_var1;
            u_var26 = ((u_var26 >> 4) | ((b_var3_inner >> 4) << 4)) & 0xFF;

            int idx1 = (l_var2 + (i_var25 - 1)) * 0x100 + u_var27;
            u_var6 = tables.invXor[idx1] & 0xF;

            int idx2 = (l_var2 + i_var25) * 0x100 + u_var26;
            int b_var3_new = tables.invXor[idx2] & 0xFF;
            u_var26 = (b_var3_new & 0xF) << 4;
            i_var25 += 2;
          }

          state[l_var9_xor + l_var21_xor * 8] = (byte) ((u_var26 | u_var6) & 0xFF);
          pb_var18_offset += 4;
        }
        i_var15 += 6;
      }
    }

    if (roundStart == 1) {
      System.arraycopy(state, 0, tmp32, 0, 32);
      int u_var8 = 1;
      int u_var10 = 3;
      int u_var12 = 2;

      for (int row = 0; row < 4; row++) {
        int idx0 = (tmp32[row] & 0xFF) + row * 0x400;
        state[row] = tables.invFirst[idx0];

        int row1 = u_var10 & 3;
        int idx1 = (tmp32[8 + row1] & 0xFF) + row1 * 0x400 + 0x100;
        state[8 + row] = tables.invFirst[idx1];

        int row2 = u_var12 & 3;
        int idx2 = (tmp32[0x10 + row2] & 0xFF) + row2 * 0x400 + 0x200;
        state[0x10 + row] = tables.invFirst[idx2];

        int row3 = u_var8 & 3;
        int idx3 = (tmp32[0x18 + row3] & 0xFF) + row3 * 0x400 + 0x300;
        state[0x18 + row] = tables.invFirst[idx3];

        u_var8++;
        u_var10++;
        u_var12++;
      }
    }

    for (int col = 0; col < 4; col++) {
      for (int row = 0; row < 4; row++) {
        output[col + row * 4] = state[col * 8 + row];
      }
    }

    return output;
  }

  private void xorInto(byte[] target, byte[] source, int sourceOffset) {
    for (int i = 0; i < target.length; i++) {
      target[i] ^= source[sourceOffset + i];
    }
  }

  public byte[] encryptCbc(byte[] data, byte[] iv) {
    if (data.length % 16 != 0) {
      throw new IllegalArgumentException("Plaintext length must be a multiple of 16");
    }
    byte[] result = new byte[data.length];
    byte[] prev = new byte[16];
    System.arraycopy(iv, 0, prev, 0, 16);

    byte[] block = new byte[16];
    for (int offset = 0; offset < data.length; offset += 16) {
      System.arraycopy(data, offset, block, 0, 16);
      xorInto(block, prev, 0);
      byte[] encrypted = encryptBlockAuth(block, 10);
      System.arraycopy(encrypted, 0, result, offset, 16);
      System.arraycopy(encrypted, 0, prev, 0, 16);
    }
    return result;
  }

  public byte[] decryptCbc(byte[] data, byte[] iv) {
    if (data.length % 16 != 0) {
      throw new IllegalArgumentException("Ciphertext length must be a multiple of 16");
    }
    byte[] result = new byte[data.length];
    byte[] prev = new byte[16];
    System.arraycopy(iv, 0, prev, 0, 16);

    byte[] block = new byte[16];
    for (int offset = 0; offset < data.length; offset += 16) {
      System.arraycopy(data, offset, block, 0, 16);
      byte[] decrypted = decryptBlockAuth(block, 1);
      xorInto(decrypted, prev, 0);
      System.arraycopy(decrypted, 0, result, offset, 16);
      System.arraycopy(block, 0, prev, 0, 16);
    }
    return result;
  }

  private byte[] addPkcs7(byte[] data) {
    int padding = 16 - (data.length % 16);
    byte[] padded = new byte[data.length + padding];
    System.arraycopy(data, 0, padded, 0, data.length);
    for (int i = data.length; i < padded.length; i++) {
      padded[i] = (byte) padding;
    }
    return padded;
  }

  private byte[] stripPkcs7(byte[] data) {
    if (data.length == 0) return data;
    int padding = data[data.length - 1] & 0xFF;
    if (padding < 1 || padding > 16 || padding > data.length) {
      StringBuilder sb = new StringBuilder();
      sb.append("Invalid PKCS7 padding: ").append(padding).append(". Last 16 bytes: ");
      int start = Math.max(0, data.length - 16);
      for (int i = start; i < data.length; i++) {
        sb.append(String.format("%02x ", data[i]));
      }
      throw new IllegalArgumentException(sb.toString());
    }
    byte[] unpadded = new byte[data.length - padding];
    System.arraycopy(data, 0, unpadded, 0, unpadded.length);
    return unpadded;
  }

  public String encodeEnvelope(String plaintext) {
    byte[] plainBytes = plaintext.getBytes(StandardCharsets.UTF_8);
    byte[] padded = addPkcs7(plainBytes);
    byte[] ciphertext = encryptCbc(padded, new byte[16]);
    return "F" + Base64.getEncoder().encodeToString(ciphertext);
  }

  public String decodeEnvelope(String envelope) {
    String cleaned =
        envelope.replace(" ", "").replace("\t", "").replace("\n", "").replace("\r", "").trim();
    cleaned = cleaned.replace("-", "+").replace("_", "/");

    if (cleaned.isEmpty() || !cleaned.startsWith("F")) {
      throw new RuntimeException("Invalid envelope format");
    }

    cleaned = cleaned.substring(1);
    int remainder = cleaned.length() % 4;
    if (remainder != 0) {
      cleaned += "===".substring(0, 4 - remainder);
    }

    byte[] ciphertext = Base64.getDecoder().decode(cleaned);
    byte[] decrypted = decryptCbc(ciphertext, new byte[16]);
    byte[] unpadded = stripPkcs7(decrypted);
    return new String(unpadded, StandardCharsets.UTF_8);
  }
}
