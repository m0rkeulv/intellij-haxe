package com.intellij.plugins.haxe.profiler.tracy;

import com.intellij.plugins.haxe.profiler.model.ProfilerFormatException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("tracy receiver: lz4 stream")
public class TracyLz4StreamTest {

  @Test
  @DisplayName("carries the dictionary window across frames")
  public void testCarriesTheDictionaryWindowAcrossFrames() throws IOException {
    // frame 2 opens with a match reaching 16 bytes back - into frame 1's output
    byte[] literals = "0123456789abcdef".getBytes(StandardCharsets.US_ASCII);
    byte[] block1 = new byte[2 + literals.length];
    block1[0] = (byte)0xF0;
    block1[1] = 0x01;
    System.arraycopy(literals, 0, block1, 2, literals.length);
    byte[] block2 = {0x04, 0x10, 0x00, 0x50, 'A', 'B', 'C', 'D', 'E'};

    TracyLz4Stream stream = new TracyLz4Stream(new ByteArrayInputStream(framed(block1, block2)));
    String out = new String(stream.readAllBytes(), StandardCharsets.US_ASCII);

    assertEquals("0123456789abcdef01234567ABCDE", out);
  }

  @Test
  @DisplayName("a truncated frame body fails as truncated")
  public void testATruncatedFrameBodyFailsAsTruncated() {
    byte[] framed = framed(new byte[]{0x50, 'A', 'B', 'C', 'D', 'E'});
    byte[] cut = new byte[framed.length - 3];
    System.arraycopy(framed, 0, cut, 0, cut.length);

    TracyLz4Stream stream = new TracyLz4Stream(new ByteArrayInputStream(cut));

    ProfilerFormatException failure = assertThrows(ProfilerFormatException.class, stream::readAllBytes);
    assertTrue(failure.getMessage().contains("truncated"), failure.getMessage());
  }

  @Test
  @DisplayName("the embedded queue table matches the extracted compiler table")
  public void testTheEmbeddedQueueTableMatchesTheExtractedCompilerTable() throws IOException {
    String[] lines = fixture("/tracy/queue-data-sizes-v74.txt").split("\r?\n");

    assertEquals("NUM_TYPES=" + TracyQueueType.values().length, lines[0].trim());
    for (TracyQueueType type : TracyQueueType.values()) {
      String[] pair = lines[1 + type.ordinal()].trim().split(";");
      assertEquals(type.ordinal(), Integer.parseInt(pair[0]));
      assertEquals(Integer.parseInt(pair[1]), type.wireSize(), type + " wire size drifted");
    }
  }

  @Test
  @DisplayName("the live captured session decompresses and walks to exact item boundaries")
  public void testTheLiveCapturedSessionDecompressesAndWalksToExactItemBoundaries() throws IOException {
    Map<TracyQueueType, Integer> counts;
    try (InputStream raw = TracyLz4StreamTest.class.getResourceAsStream("/tracy/session-v74.raw")) {
      counts = walkItems(new TracyLz4Stream(raw));
    }

    assertTrue(counts.getOrDefault(TracyQueueType.ZoneBeginAllocSrcLoc, 0) > 100,
               "the sample's instrumented functions must appear as zones: " + counts);
    assertTrue(counts.getOrDefault(TracyQueueType.ZoneEnd, 0) > 100, "zones must close: " + counts);
    assertTrue(counts.getOrDefault(TracyQueueType.SourceLocationPayload, 0) > 0,
               "alloc'd source locations ship inline: " + counts);
    assertTrue(counts.getOrDefault(TracyQueueType.ThreadContext, 0) > 0, counts.toString());
  }

  /**
   * Consumes the whole decompressed stream item by item using the type
   * table — the walk only ends cleanly when every fixed size and payload
   * length is right, so reaching EOF at an item boundary validates the
   * framing, the LZ4 window carry and the table at once.
   */
  private static Map<TracyQueueType, Integer> walkItems(InputStream stream) throws IOException {
    Map<TracyQueueType, Integer> counts = new EnumMap<>(TracyQueueType.class);
    DataInputStream data = new DataInputStream(stream);
    int typeByte;
    while ((typeByte = data.read()) >= 0) {
      TracyQueueType type = TracyQueueType.of(typeByte);
      if (type == null) throw new IOException("unknown queue type " + typeByte + " after " + counts);
      counts.merge(type, 1, Integer::sum);

      expectFully(data, type.wireSize() - 1, type);
      int payloadLength = switch (type.payload()) {
        case NONE -> 0;
        case U16 -> data.readUnsignedByte() | data.readUnsignedByte() << 8;
        case U32 -> {
          int value = 0;
          for (int i = 0; i < 4; i++) value |= data.readUnsignedByte() << (8 * i);
          yield value;
        }
      };
      expectFully(data, payloadLength, type);
    }
    return counts;
  }

  private static void expectFully(DataInputStream data, int count, TracyQueueType type) throws IOException {
    if (data.readNBytes(count).length < count) {
      throw new IOException("stream ended inside a " + type + " item");
    }
  }

  /** Wraps each block as one tracy frame: u32 LE compressed size + block. */
  private static byte[] framed(byte[]... blocks) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (byte[] block : blocks) {
      for (int i = 0; i < 4; i++) out.write(block.length >> (8 * i) & 0xFF);
      out.writeBytes(block);
    }
    return out.toByteArray();
  }

  private static String fixture(String name) throws IOException {
    try (InputStream in = TracyLz4StreamTest.class.getResourceAsStream(name)) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
