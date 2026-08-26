package com.intellij.plugins.haxe.profiler.tracy;

import com.intellij.plugins.haxe.profiler.model.ProfilerFormatException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("tracy receiver: event reader")
public class TracyEventReaderTest {

  private static final TracyWelcome TICKS_ARE_NS =
    new TracyWelcome(1.0, 0, 0, 0, 0, 0, 0, 1, 0, false, "synthetic");

  @Test
  @DisplayName("replays the live captured session into nested zones")
  public void testReplaysTheLiveCapturedSessionIntoNestedZones() throws IOException {
    TracyWelcome welcome;
    try (InputStream in = resource("/tracy/welcome-v74.bin")) {
      welcome = TracyHandshake.parseWelcome(in.readAllBytes());
    }
    TracySession session;
    try (InputStream raw = resource("/tracy/session-v74.raw")) {
      session = TracyEventReader.read(new TracyLz4Stream(raw), welcome);
    }

    assertEquals(791, session.zones().size(), "787 closed + 4 auto-closed at the capture cut");
    assertEquals(0, session.unmatchedZoneEnds());
    assertTrue(session.zones().stream().allMatch(zone -> zone.endNs() >= zone.startNs()));
    assertTrue(session.zones().stream().anyMatch(zone -> zone.location().function().contains("ProfPump")),
               "the sample's own functions must appear");
    assertTrue(session.zones().stream().anyMatch(zone -> zone.location().file().endsWith(".hx")),
               "haxe positions ride along");
    // wall clock was ~2 s but the INSTRUMENTED span is the burn workload (~70 ms)
    assertTrue(session.durationNs() > 10_000_000L && session.durationNs() < 60_000_000_000L,
               "the sample's instrumented span: " + session.durationNs() + " ns");
    assertEquals(1, session.cpuUsage().size(), "the one SysTimeReport in the capture");

    TracyZone outer = session.zones().stream()
      .max(java.util.Comparator.comparingLong(TracyZone::durationNs))
      .orElseThrow();
    boolean nests = session.zones().stream()
      .anyMatch(zone -> zone != outer && zone.startNs() >= outer.startNs() && zone.endNs() <= outer.endNs());
    assertTrue(nests, "instrumented calls must nest inside their caller's zone");
  }

  @Test
  @DisplayName("a thread context switch resets the time reference")
  public void testAThreadContextSwitchResetsTheTimeReference() throws IOException {
    ItemBuilder items = new ItemBuilder();
    items.threadContext(1);
    items.sourceLocation("Main.main", "Main.hx", 10);
    items.zoneBeginAlloc(100);
    items.zoneEnd(50);
    items.threadContext(2);
    items.sourceLocation("Worker.run", "Worker.hx", 20);
    items.zoneBeginAlloc(10); // a RESET reference: 10, not 160
    items.zoneEnd(5);

    TracySession session = TracyEventReader.read(items.stream(), TICKS_ARE_NS);

    assertEquals(2, session.zones().size());
    TracyZone worker = session.zones().get(0);
    assertEquals(2, worker.threadId());
    assertEquals(0, worker.startNs(), "session times rebase to the earliest instant");
    assertEquals(5, worker.endNs());
    TracyZone main = session.zones().get(1);
    assertEquals(90, main.startNs());
    assertEquals(140, main.endNs());
  }

  @Test
  @DisplayName("frame marks are absolute while zones stay on the delta stream")
  public void testFrameMarksAreAbsoluteWhileZonesStayOnTheDeltaStream() throws IOException {
    ItemBuilder items = new ItemBuilder();
    items.threadContext(1);
    items.sourceLocation("Main.main", "Main.hx", 1);
    items.zoneBeginAlloc(1000);
    items.frameMark(1200);
    items.zoneEnd(500); // delta from the zone begin, unaffected by the mark

    TracySession session = TracyEventReader.read(items.stream(), TICKS_ARE_NS);

    assertEquals(List.of(200L), session.frameMarksNs());
    assertEquals(500, session.zones().getFirst().endNs());
  }

  @Test
  @DisplayName("unmatched ends are counted and unclosed zones close at the last instant")
  public void testUnmatchedEndsAreCountedAndUnclosedZonesCloseAtTheLastInstant() throws IOException {
    ItemBuilder items = new ItemBuilder();
    items.threadContext(1);
    items.zoneEnd(50); // connection opened mid-zone
    items.sourceLocation("Main.main", "Main.hx", 1);
    items.zoneBeginAlloc(50);
    items.frameMark(400); // moves the last-seen instant past the open zone

    TracySession session = TracyEventReader.read(items.stream(), TICKS_ARE_NS);

    assertEquals(1, session.unmatchedZoneEnds());
    assertEquals(1, session.zones().size());
    assertEquals(350, session.zones().getFirst().endNs(), "closed at the last instant seen");
  }

  @Test
  @DisplayName("a zone begin without its source location payload fails")
  public void testAZoneBeginWithoutItsSourceLocationPayloadFails() {
    ItemBuilder items = new ItemBuilder();
    items.threadContext(1);
    items.zoneBeginAlloc(100);

    assertThrows(ProfilerFormatException.class, () -> TracyEventReader.read(items.stream(), TICKS_ARE_NS));
  }

  @Test
  @DisplayName("plots ride the thread delta stream keyed by their name pointer")
  public void testPlotsRideTheThreadDeltaStreamKeyedByTheirNamePointer() throws IOException {
    ItemBuilder items = new ItemBuilder();
    items.threadContext(1);
    items.plotDouble(0xCAFE, 100, 42.5);
    items.plotDouble(0xCAFE, 50, 43.5);

    TracySession session = TracyEventReader.read(items.stream(), TICKS_ARE_NS);

    List<TracySession.PlotPoint> points = session.plots().get("plot@cafe");
    assertEquals(2, points.size());
    assertEquals(0, points.get(0).timeNs());
    assertEquals(42.5, points.get(0).value());
    assertEquals(50, points.get(1).timeNs(), "the second point is 50 ticks after the first");
  }

  /** Writes decompressed tracy items the way the client's dequeue emits them. */
  private static final class ItemBuilder {
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    void threadContext(int thread) {
      out.write(TracyQueueType.ThreadContext.ordinal());
      writeInt(thread);
    }

    void sourceLocation(String function, String file, int line) {
      out.write(TracyQueueType.SourceLocationPayload.ordinal());
      writeLong(0xDEAD); // the client-side pointer; unused by the reader
      byte[] functionUtf8 = function.getBytes(StandardCharsets.UTF_8);
      byte[] fileUtf8 = file.getBytes(StandardCharsets.UTF_8);
      writeU16(4 + 4 + functionUtf8.length + 1 + fileUtf8.length + 1);
      writeInt(0); // color
      writeInt(line);
      out.writeBytes(functionUtf8);
      out.write(0);
      out.writeBytes(fileUtf8);
      out.write(0);
    }

    void zoneBeginAlloc(long deltaTicks) {
      out.write(TracyQueueType.ZoneBeginAllocSrcLoc.ordinal());
      writeLong(deltaTicks);
    }

    void zoneEnd(long deltaTicks) {
      out.write(TracyQueueType.ZoneEnd.ordinal());
      writeLong(deltaTicks);
    }

    void frameMark(long absoluteTicks) {
      out.write(TracyQueueType.FrameMarkMsg.ordinal());
      writeLong(absoluteTicks);
      writeLong(0); // name pointer; 0 = the continuous frame set
    }

    void plotDouble(long namePointer, long deltaTicks, double value) {
      out.write(TracyQueueType.PlotDataDouble.ordinal());
      writeLong(namePointer);
      writeLong(deltaTicks);
      writeLong(Double.doubleToLongBits(value));
    }

    InputStream stream() {
      return new ByteArrayInputStream(out.toByteArray());
    }

    private void writeU16(int value) {
      out.write(value & 0xFF);
      out.write(value >> 8 & 0xFF);
    }

    private void writeInt(int value) {
      for (int i = 0; i < 4; i++) out.write(value >> (8 * i) & 0xFF);
    }

    private void writeLong(long value) {
      for (int i = 0; i < 8; i++) out.write((int)(value >> (8 * i) & 0xFF));
    }
  }

  private static InputStream resource(String name) {
    return TracyEventReaderTest.class.getResourceAsStream(name);
  }
}
