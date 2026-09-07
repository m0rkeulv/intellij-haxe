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
 * The server side of tracy's connection opening: send the 8-byte
 * shibboleth plus the protocol version offered, read the client's one-byte
 * verdict, then its packed welcome message in that version's layout. The
 * client compares versions for equality only, so a refusal surfaces as
 * {@link TracyProtocolMismatchException} for the caller to offer another.
 */
public final class TracyHandshake {

  private static final byte[] SHIBBOLETH = "TracyPrf".getBytes(StandardCharsets.US_ASCII);
  // HandshakeStatus, by wire value
  private static final String[] STATUS_NAMES = {"pending", "welcome", "protocol mismatch", "not available", "dropped"};
  private static final int STATUS_WELCOME = 1;
  private static final int STATUS_PROTOCOL_MISMATCH = 2;
  private static final int ON_DEMAND_FLAG = 1;

  private TracyHandshake() {
  }

  @NotNull
  public static TracyWelcome perform(@NotNull InputStream in, @NotNull OutputStream out,
                                     @NotNull TracyProtocolVersion version) throws IOException {
    out.write(SHIBBOLETH);
    byte[] wireVersion = new byte[4];
    ByteBuffer.wrap(wireVersion).order(ByteOrder.LITTLE_ENDIAN).putInt(version.wire());
    out.write(wireVersion);
    out.flush();

    int status = in.read();
    if (status == STATUS_PROTOCOL_MISMATCH) throw new TracyProtocolMismatchException(version);
    if (status != STATUS_WELCOME) {
      String name = status >= 0 && status < STATUS_NAMES.length ? STATUS_NAMES[status] : "unknown (" + status + ")";
      throw new ProfilerFormatException("tracy client refused the connection: " + name
                                        + " (receiver offered protocol " + version.wire() + ")");
    }

    byte[] welcome = in.readNBytes(version.welcomeSize());
    if (welcome.length < version.welcomeSize()) {
      throw new ProfilerFormatException("truncated tracy welcome message");
    }
    return parseWelcome(welcome, version);
  }

  /**
   * The packed WelcomeMessage: timerMul f64, initBegin, initEnd, [delay -
   * before v76], resolution, epoch, exectime, pid, samplingPeriod (i64/u64
   * each), flags u8, cpuArch u8, cpuManufacturer[12], cpuId u32,
   * programName[64], hostInfo[1024].
   */
  @NotNull
  static TracyWelcome parseWelcome(byte @NotNull [] welcome, @NotNull TracyProtocolVersion version) {
    ByteBuffer buffer = ByteBuffer.wrap(welcome).order(ByteOrder.LITTLE_ENDIAN);
    double timerMul = buffer.getDouble();
    long initBegin = buffer.getLong();
    long initEnd = buffer.getLong();
    long delay = version.welcomeHasDelay() ? buffer.getLong() : 0;
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

    return new TracyWelcome(version, timerMul, initBegin, initEnd, delay, resolution, epoch, execTime, pid,
                            samplingPeriod, (flags & ON_DEMAND_FLAG) != 0, programName);
  }
}
