package com.dautolock.link;

import static org.junit.Assert.*;

import java.io.*;
import org.junit.Test;

public class LinkProtocolTest {
  @Test
  public void qrRoundTripPreservesCredentialAndService() {
    Pairing p = Pairing.create(), copy = Pairing.parse(p.qr());
    assertEquals(p.service, copy.service);
    assertArrayEquals(p.key, copy.key);
    assertNotEquals(p.qr(), Pairing.create().qr());
  }

  @Test
  public void qrRejectsUnrelatedUrlsWrongVersionsTrailingDataAndShortKeys() {
    String valid = Pairing.create().qr();
    for (String s :
        new String[] {
          null,
          "https://example.com",
          valid.replace("/v1/", "/v2/"),
          valid + "?extra=1",
          valid.substring(0, valid.length() - 1),
          " " + valid
        }) assertThrows(IllegalArgumentException.class, () -> Pairing.parse(s));
  }

  private LinkProtocol.Sample sample() {
    return new LinkProtocol.Sample(0, 1, 1, 3, 17, 0);
  }

  @Test
  public void requestAndResponseRoundTrip() throws Exception {
    Pairing pair = Pairing.create();
    byte[] n = LinkProtocol.nonce();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    LinkProtocol.request(out, pair.key, n);
    assertArrayEquals(
        n, LinkProtocol.readRequest(new ByteArrayInputStream(out.toByteArray()), pair.key));
    out.reset();
    LinkProtocol.respond(out, pair.key, n, sample());
    LinkProtocol.Sample s =
        LinkProtocol.readResponse(new ByteArrayInputStream(out.toByteArray()), pair.key, n);
    assertEquals(0, s.gear);
    assertEquals(1, s.brake);
    assertEquals(3, s.fallback);
    assertEquals(17, s.rawBrake);
  }

  @Test
  public void wrongKeyModifiedDataAndOldChallengeAreRejected() throws Exception {
    Pairing pair = Pairing.create();
    byte[] n = LinkProtocol.nonce();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    LinkProtocol.respond(out, pair.key, n, sample());
    byte[] data = out.toByteArray();
    assertThrows(
        IOException.class,
        () -> LinkProtocol.readResponse(new ByteArrayInputStream(data), Pairing.create().key, n));
    assertThrows(
        IOException.class,
        () ->
            LinkProtocol.readResponse(
                new ByteArrayInputStream(data), pair.key, LinkProtocol.nonce()));
    byte[] altered = data.clone();
    altered[23] = 3;
    assertThrows(
        IOException.class,
        () -> LinkProtocol.readResponse(new ByteArrayInputStream(altered), pair.key, n));
  }

  @Test
  public void oversizedAndTruncatedFramesAreRejectedBeforeAllocation() {
    assertThrows(
        IOException.class,
        () ->
            LinkProtocol.readRequest(
                new ByteArrayInputStream(new byte[] {127, 127}), new byte[32]));
    assertThrows(
        IOException.class,
        () ->
            LinkProtocol.readRequest(
                new ByteArrayInputStream(new byte[] {0, 53, 0}), new byte[32]));
  }

  @Test
  public void requestCannotBeReusedAsResponse() throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] n = LinkProtocol.nonce(), key = new byte[32];
    LinkProtocol.request(out, key, n);
    assertThrows(
        IOException.class,
        () -> LinkProtocol.readResponse(new ByteArrayInputStream(out.toByteArray()), key, n));
  }
}
