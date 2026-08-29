package com.intellij.plugins.haxe.profiler.tracy;

import com.intellij.plugins.haxe.profiler.model.ProfilerFormatException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("tracy receiver: handshake")
public class TracyHandshakeTest {

  @Test
  @DisplayName("parses the live captured welcome message")
  public void testParsesTheLiveCapturedWelcomeMessage() throws IOException {
    TracyWelcome welcome = TracyHandshake.parseWelcome(welcomeFixture());

    assertEquals("ProfPump-debug.exe", welcome.programName());
    assertTrue(welcome.timerMul() > 0.01 && welcome.timerMul() < 100, "ticks-to-ns factor: " + welcome.timerMul());
    assertTrue(welcome.pid() > 0);
    assertTrue(welcome.epoch() > 1_700_000_000L, "unix start time: " + welcome.epoch());
  }

  @Test
  @DisplayName("perform sends the shibboleth and version then reads the welcome")
  public void testPerformSendsTheShibbolethAndVersionThenReadsTheWelcome() throws IOException {
    byte[] fixture = welcomeFixture();
    byte[] fromClient = new byte[1 + fixture.length];
    fromClient[0] = 1; // HandshakeWelcome
    System.arraycopy(fixture, 0, fromClient, 1, fixture.length);
    ByteArrayOutputStream toClient = new ByteArrayOutputStream();

    TracyWelcome welcome = TracyHandshake.perform(new ByteArrayInputStream(fromClient), toClient);

    byte[] sent = toClient.toByteArray();
    assertEquals("TracyPrf", new String(Arrays.copyOf(sent, 8), StandardCharsets.US_ASCII));
    assertEquals(TracyHandshake.PROTOCOL_VERSION, sent[8] & 0xFF | (sent[9] & 0xFF) << 8);
    assertEquals(12, sent.length);
    assertEquals("ProfPump-debug.exe", welcome.programName());
  }

  @Test
  @DisplayName("a protocol mismatch fails loudly with the verdict name")
  public void testAProtocolMismatchFailsLoudlyWithTheVerdictName() {
    ByteArrayInputStream fromClient = new ByteArrayInputStream(new byte[]{2}); // HandshakeProtocolMismatch

    ProfilerFormatException failure = assertThrows(ProfilerFormatException.class,
      () -> TracyHandshake.perform(fromClient, new ByteArrayOutputStream()));
    assertTrue(failure.getMessage().contains("protocol mismatch"), failure.getMessage());
  }

  private static byte[] welcomeFixture() throws IOException {
    try (InputStream in = TracyHandshakeTest.class.getResourceAsStream("/tracy/welcome-v74.bin")) {
      return in.readAllBytes();
    }
  }
}
