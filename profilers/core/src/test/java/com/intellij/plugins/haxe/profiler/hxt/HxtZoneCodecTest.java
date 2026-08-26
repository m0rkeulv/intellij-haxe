package com.intellij.plugins.haxe.profiler.hxt;

import com.intellij.plugins.haxe.profiler.model.ProfilerFormatException;
import com.intellij.plugins.haxe.profiler.tracy.TracySession;
import com.intellij.plugins.haxe.profiler.tracy.TracySourceLocation;
import com.intellij.plugins.haxe.profiler.tracy.TracyWelcome;
import com.intellij.plugins.haxe.profiler.tracy.TracyZone;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("hxcpp tracy: session codec")
public class HxtZoneCodecTest {

  private static final TracySourceLocation BURN = new TracySourceLocation("ProfPump.burn", "ProfPump.hx", 2, 0);
  private static final TracySourceLocation MAIN = new TracySourceLocation("ProfPump.main", "ProfPump.hx", 9, 0xFF00FF);

  @Test
  @DisplayName("a session survives the write and reopen roundtrip")
  public void testASessionSurvivesTheWriteAndReopenRoundtrip() throws IOException {
    TracyWelcome welcome = new TracyWelcome(0.25, 1, 2, 3, 4, 1_756_200_000L, 5, 4242, 0, false, "game.exe");
    TracySession session = new TracySession(
      welcome,
      List.of(new TracyZone(1, 0, 5_000_000, MAIN),
              new TracyZone(1, 1_000, 900_000, BURN),
              new TracyZone(2, 400, 800, BURN)),
      List.of(16_000_000L, 33_000_000L),
      Map.of("GC memory", List.of(new TracySession.PlotPoint(100, 1024.0), new TracySession.PlotPoint(200, 2048.5))),
      List.of(new TracySession.PlotPoint(500, 12.5)),
      Map.of(1, "Main", 2, "worker"),
      5_000_000,
      3);

    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    HxtZoneCodec.write(session, bytes);
    HxtCapture capture = HxtSessionTranslator.translateCapture(new ByteArrayInputStream(bytes.toByteArray()));

    TracySession reopened = assertInstanceOf(HxtCapture.Zones.class, capture).session();
    assertEquals(session.zones(), reopened.zones());
    assertEquals(session.frameMarksNs(), reopened.frameMarksNs());
    assertEquals(session.plots(), reopened.plots());
    assertEquals(session.cpuUsage(), reopened.cpuUsage());
    assertEquals(session.threadNames(), reopened.threadNames());
    assertEquals(session.durationNs(), reopened.durationNs());
    assertEquals(session.unmatchedZoneEnds(), reopened.unmatchedZoneEnds());
    assertEquals("game.exe", reopened.welcome().programName());
    assertEquals(4242, reopened.welcome().pid());
    assertEquals(1_756_200_000L, reopened.welcome().epoch());
  }

  @Test
  @DisplayName("an empty session roundtrips too")
  public void testAnEmptySessionRoundtripsToo() throws IOException {
    TracyWelcome welcome = new TracyWelcome(1.0, 0, 0, 0, 0, 0, 0, 1, 0, false, "idle");
    TracySession session = new TracySession(welcome, List.of(), List.of(), Map.of(), List.of(), Map.of(), 0, 0);

    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    HxtZoneCodec.write(session, bytes);
    HxtCapture capture = HxtSessionTranslator.translateCapture(new ByteArrayInputStream(bytes.toByteArray()));

    assertEquals(List.of(), assertInstanceOf(HxtCapture.Zones.class, capture).session().zones());
  }

  @Test
  @DisplayName("unknown record types are skipped by their length")
  public void testUnknownRecordTypesAreSkippedByTheirLength() throws IOException {
    TracyWelcome welcome = new TracyWelcome(1.0, 0, 0, 0, 0, 0, 0, 1, 0, false, "x");
    TracySession session = new TracySession(welcome, List.of(new TracyZone(1, 0, 10, BURN)),
                                            List.of(), Map.of(), List.of(), Map.of(), 10, 0);
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    HxtZoneCodec.write(session, bytes);
    // a record from the future, spliced in at the end
    bytes.write(99);
    bytes.writeBytes(new byte[]{3, 0, 0, 0, 1, 2, 3});

    HxtCapture capture = HxtSessionTranslator.translateCapture(new ByteArrayInputStream(bytes.toByteArray()));

    assertEquals(1, assertInstanceOf(HxtCapture.Zones.class, capture).session().zones().size());
  }

  @Test
  @DisplayName("the sampled view refuses a zone capture with a clear message")
  public void testTheSampledViewRefusesAZoneCaptureWithAClearMessage() throws IOException {
    TracyWelcome welcome = new TracyWelcome(1.0, 0, 0, 0, 0, 0, 0, 1, 0, false, "x");
    TracySession session = new TracySession(welcome, List.of(), List.of(), Map.of(), List.of(), Map.of(), 0, 0);
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    HxtZoneCodec.write(session, bytes);

    ProfilerFormatException failure = assertThrows(ProfilerFormatException.class,
      () -> HxtSessionTranslator.translate(new ByteArrayInputStream(bytes.toByteArray())));
    assertTrue(failure.getMessage().contains("zone capture"), failure.getMessage());
  }
}
