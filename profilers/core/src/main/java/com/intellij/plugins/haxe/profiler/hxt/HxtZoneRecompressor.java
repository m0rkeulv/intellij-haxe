package com.intellij.plugins.haxe.profiler.hxt;

import com.intellij.plugins.haxe.profiler.model.ProfilerFormatException;
import org.jetbrains.annotations.NotNull;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Re-deflates a finished capture's zone chunks at a higher level: a LIVE
 * capture writes cheap ({@link HxtZoneWriter#LIVE_LEVEL}) so the receiver
 * does not steal CPU from the app it is profiling, and this pass brings
 * the file to the archive level once the app has exited and the machine is
 * idle again. Every non-zone record is copied verbatim except INFO, whose
 * trailing level byte is updated to match the rewritten chunks; the
 * rewrite lands in a temp sibling and atomically replaces the original.
 */
public final class HxtZoneRecompressor {

  private HxtZoneRecompressor() {
  }

  public static void recompress(@NotNull Path file, int level) throws IOException {
    Path temp = file.resolveSibling(file.getFileName() + ".recompress");
    try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)));
         OutputStream out = new BufferedOutputStream(Files.newOutputStream(temp))) {
      copyHeader(in, out);
      byte[] raw = new byte[0];
      while (true) {
        int type = in.read();
        if (type < 0) break;
        int length = readI32(in);
        if (type == HxtZoneWriter.INFO_RECORD) {
          byte[] patched = withLevelByte(in.readNBytes(length), level);
          out.write(type);
          writeI32(out, patched.length);
          out.write(patched);
          continue;
        }
        if (type != HxtZoneWriter.ZONES_RECORD) {
          out.write(type);
          writeI32(out, length);
          copyBytes(in, out, length);
          continue;
        }

        byte[] bounds = in.readNBytes(28); // count + min/max/maxDuration
        int count = bounds[0] & 0xFF | (bounds[1] & 0xFF) << 8 | (bounds[2] & 0xFF) << 16 | (bounds[3] & 0xFF) << 24;
        byte[] compressed = in.readNBytes(length - 28);
        int rawLength = count * HxtZoneWriter.ZONE_BYTES;
        if (raw.length < rawLength) raw = new byte[rawLength];
        inflate(compressed, raw, rawLength);
        byte[] recompressed = deflate(raw, rawLength, level);

        out.write(type);
        writeI32(out, 28 + recompressed.length);
        out.write(bounds);
        out.write(recompressed);
      }
    }
    catch (IOException e) {
      Files.deleteIfExists(temp);
      throw e;
    }
    replaceRetrying(temp, file);
  }

  /**
   * A live view's refresh tick may hold the original open for a moment,
   * and a Windows reader blocks the replace with a sharing violation; a
   * short bounded retry outlasts any single read. On give-up the temp is
   * removed and the original — complete and valid at its live level —
   * stays in place.
   */
  private static void replaceRetrying(Path temp, Path file) throws IOException {
    IOException lastFailure = null;
    for (int attempt = 0; attempt < 10; attempt++) {
      try {
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        return;
      }
      catch (IOException locked) {
        lastFailure = locked;
        try {
          Thread.sleep(200);
        }
        catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          break;
        }
      }
    }
    Files.deleteIfExists(temp);
    throw lastFailure;
  }

  /**
   * The INFO payload carries an optional u8 deflate level after its fixed
   * fields; sets it (patching or appending) so the file names the level
   * its rewritten chunks actually carry. A payload too short for its own
   * fixed fields is left alone.
   */
  private static byte[] withLevelByte(byte[] payload, int level) {
    if (payload.length < 2) return payload;
    int nameLength = payload[0] & 0xFF | (payload[1] & 0xFF) << 8;
    int levelOffset = 2 + nameLength + 8 + 8 + 8 + 4 + 8;
    if (payload.length < levelOffset) return payload;
    byte[] patched = Arrays.copyOf(payload, Math.max(payload.length, levelOffset + 1));
    patched[levelOffset] = (byte)level;
    return patched;
  }

  private static void copyBytes(DataInputStream in, OutputStream out, int length) throws IOException {
    byte[] buffer = new byte[64 * 1024];
    int remaining = length;
    while (remaining > 0) {
      int read = in.read(buffer, 0, Math.min(buffer.length, remaining));
      if (read < 0) throw new ProfilerFormatException("record truncated during recompression");
      out.write(buffer, 0, read);
      remaining -= read;
    }
  }

  private static void copyHeader(DataInputStream in, OutputStream out) throws IOException {
    byte[] fixed = in.readNBytes(4 + 2 + 4 + 8);
    out.write(fixed);
    int low = in.readUnsignedByte();
    int high = in.readUnsignedByte();
    out.write(low);
    out.write(high);
    byte[] target = in.readNBytes(low | high << 8);
    out.write(target);
  }

  private static void inflate(byte[] compressed, byte[] raw, int rawLength) throws IOException {
    Inflater inflater = new Inflater();
    inflater.setInput(compressed);
    try {
      int total = 0;
      while (total < rawLength) {
        int read = inflater.inflate(raw, total, rawLength - total);
        if (read == 0) throw new ProfilerFormatException("zone chunk decompressed short during recompression");
        total += read;
      }
    }
    catch (DataFormatException corrupted) {
      throw new ProfilerFormatException("zone chunk does not decompress: " + corrupted.getMessage());
    }
    finally {
      inflater.end();
    }
  }

  private static byte[] deflate(byte[] raw, int rawLength, int level) {
    Deflater deflater = new Deflater(level);
    deflater.setInput(raw, 0, rawLength);
    deflater.finish();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buffer = new byte[64 * 1024];
    while (!deflater.finished()) {
      out.write(buffer, 0, deflater.deflate(buffer));
    }
    deflater.end();
    return out.toByteArray();
  }

  private static int readI32(DataInputStream in) throws IOException {
    int value = 0;
    for (int i = 0; i < 4; i++) value |= in.readUnsignedByte() << (8 * i);
    return value;
  }

  private static void writeI32(OutputStream out, int value) throws IOException {
    for (int i = 0; i < 4; i++) out.write(value >> (8 * i) & 0xFF);
  }
}
