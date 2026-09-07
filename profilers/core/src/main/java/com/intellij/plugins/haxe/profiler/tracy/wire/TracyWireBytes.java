package com.intellij.plugins.haxe.profiler.tracy.wire;

import com.intellij.plugins.haxe.profiler.model.ProfilerFormatException;
import org.jetbrains.annotations.NotNull;

import java.io.DataInputStream;
import java.io.IOException;

/** Little-endian primitive reads over the decompressed item stream, shared by the reader and the wire formats. */
public final class TracyWireBytes {

  private TracyWireBytes() {
  }

  public static int readU16Le(@NotNull DataInputStream in) throws IOException {
    return in.readUnsignedByte() | in.readUnsignedByte() << 8;
  }

  public static int readIntLe(@NotNull DataInputStream in) throws IOException {
    int value = 0;
    for (int i = 0; i < 4; i++) {
      value |= in.readUnsignedByte() << (8 * i);
    }
    return value;
  }

  public static long readLongLe(@NotNull DataInputStream in) throws IOException {
    long value = 0;
    for (int i = 0; i < 8; i++) {
      value |= (long)in.readUnsignedByte() << (8 * i);
    }
    return value;
  }

  public static long readU48Le(@NotNull DataInputStream in) throws IOException {
    long value = 0;
    for (int i = 0; i < 6; i++) {
      value |= (long)in.readUnsignedByte() << (8 * i);
    }
    return value;
  }

  public static void skip(@NotNull DataInputStream in, int count) throws IOException {
    if (in.readNBytes(count).length < count) {
      throw new ProfilerFormatException("tracy stream ended inside an item");
    }
  }
}
