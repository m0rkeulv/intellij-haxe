package com.intellij.plugins.haxe.profiler.tracy;

import com.intellij.plugins.haxe.profiler.model.ProfilerFormatException;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * The server side of tracy's connection opening (protocol v74, the version
 * the hxcpp-bundled 0.12.0 client speaks): send the 8-byte shibboleth plus
 * our protocol version, read the client's one-byte verdict, then its packed
 * 1178-byte welcome message. A version mismatch fails loudly — tracy's
 * protocol is locked per version and limping on would misdecode everything.
 */
public final class TracyHandshake {

  public static final int PROTOCOL_VERSION = 74;

  static final int WELCOME_SIZE = 8 * 9 + 1 + 1 + 12 + 4 + 64 + 1024;
  private static final byte[] SHIBBOLETH = "TracyPrf".getBytes(StandardCharsets.US_ASCII);
  // HandshakeStatus, by wire value
  private static final String[] STATUS_NAMES = {"pending", "welcome", "protocol mismatch", "not available", "dropped"};
  private static final int STATUS_WELCOME = 1;
  private static final int ON_DEMAND_FLAG = 1;

  private TracyHandshake() {
  }

  @NotNull
  public static TracyWelcome perform(@NotNull InputStream in, @NotNull OutputStream out) throws IOException {
    out.write(SHIBBOLETH);
    byte[] version = new byte[4];
    ByteBuffer.wrap(version).order(ByteOrder.LITTLE_ENDIAN).putInt(PROTOCOL_VERSION);
    out.write(version);
    out.flush();

    int status = in.read();
    if (status != STATUS_WELCOME) {
      String name = status >= 0 && status < STATUS_NAMES.length ? STATUS_NAMES[status] : "unknown (" + status + ")";
      throw new ProfilerFormatException("tracy client refused the connection: " + name
                                        + " (receiver speaks protocol " + PROTOCOL_VERSION + ")");
    }

    byte[] welcome = in.readNBytes(WELCOME_SIZE);
    if (welcome.length < WELCOME_SIZE) {
      throw new ProfilerFormatException("truncated tracy welcome message");
    }
    return parseWelcome(welcome);
  }

  @NotNull
  static TracyWelcome parseWelcome(byte @NotNull [] welcome) {
    ByteBuffer buffer = ByteBuffer.wrap(welcome).order(ByteOrder.LITTLE_ENDIAN);
    double timerMul = buffer.getDouble();
    long initBegin = buffer.getLong();
    long initEnd = buffer.getLong();
    long delay = buffer.getLong();
    long resolution = buffer.getLong();
    long epoch = buffer.getLong();
    long execTime = buffer.getLong();
    long pid = buffer.getLong();
    long samplingPeriod = buffer.getLong();
    int flags = buffer.get() & 0xFF;
    buffer.get();       // cpuArch
    buffer.position(buffer.position() + 12 + 4); // cpuManufacturer, cpuId

    byte[] name = new byte[64];
    buffer.get(name);
    int nameEnd = 0;
    while (nameEnd < name.length && name[nameEnd] != 0) nameEnd++;
    String programName = new String(name, 0, nameEnd, StandardCharsets.UTF_8);

    return new TracyWelcome(timerMul, initBegin, initEnd, delay, resolution, epoch, execTime, pid,
                            samplingPeriod, (flags & ON_DEMAND_FLAG) != 0, programName);
  }
}
