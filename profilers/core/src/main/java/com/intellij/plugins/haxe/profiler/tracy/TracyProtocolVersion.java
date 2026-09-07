package com.intellij.plugins.haxe.profiler.tracy;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * The Tracy wire protocol versions the receiver speaks. Tracy locks its
 * protocol per release: the client refuses any server announcing a
 * different number, and each bump renumbers the queue items (and may
 * change item layouts), so the receiver must know the client's exact
 * version before reading a byte. What differs per version is captured
 * here as data: the welcome layout, the item table and the decode rules
 * v82 introduced. hxcpp bundles one client per checkout - 0.11 (69) from
 * October 2024, 0.12 (74) from June 2025, 0.13 (76) from April 2026.
 */
public enum TracyProtocolVersion {
  V69(69, "0.11", true, false),
  V74(74, "0.12", true, false),
  V76(76, "0.13", false, false),
  V82(82, "0.14", false, true);

  /**
   * The order the receiver tries versions in when the client's is unknown:
   * what current hxcpp ships first, then the previous one, then the newest
   * Tracy, then the oldest client still in the wild.
   */
  public static final List<TracyProtocolVersion> PROBE_ORDER = List.of(V76, V74, V82, V69);

  /** v82 encodes a 16-bit string length as {@code length - 256}; the 8-bit item covers the shorter strings. */
  static final int STRING_LENGTH_OFFSET_8BIT = 1 << 8;
  /** v82 zone-end deltas: the 32-bit item stores {@code delta - 2^16}, the 64-bit item {@code delta - (2^16 + 2^32)}. */
  static final long TIME_OFFSET_16BIT = 1L << 16;
  static final long TIME_OFFSET_32BIT = (1L << 16) + (1L << 32);

  private static final int WELCOME_FIXED_SIZE = 8 * 8 + 1 + 1 + 12 + 4 + 64 + 1024;

  private final int wire;
  private final String tracyRelease;
  private final boolean welcomeHasDelay;
  private final boolean compactWireForms;
  private volatile TracyQueueTable table;

  TracyProtocolVersion(int wire, String tracyRelease, boolean welcomeHasDelay, boolean compactWireForms) {
    this.wire = wire;
    this.tracyRelease = tracyRelease;
    this.welcomeHasDelay = welcomeHasDelay;
    this.compactWireForms = compactWireForms;
  }

  /** The number sent in the handshake and compared by the client. */
  public int wire() {
    return wire;
  }

  /** The Tracy release line speaking this version, for messages ("0.13"). */
  @NotNull
  public String tracyRelease() {
    return tracyRelease;
  }

  /** Whether the welcome message carries the queue-delay calibration field (removed in v76). */
  public boolean welcomeHasDelay() {
    return welcomeHasDelay;
  }

  /** The packed welcome message's size on the wire. */
  public int welcomeSize() {
    return WELCOME_FIXED_SIZE + (welcomeHasDelay ? 8 : 0);
  }

  /**
   * v82's compact encodings: zone ends and sampled callstacks arrive as
   * 16/32-bit delta items with offset-encoded 64-bit fallbacks, message
   * items carry a metadata byte, callstack samples put the thread before
   * the time, and the u16 string transfers carry an offset length.
   */
  public boolean compactTimes() {
    return compactWireForms;
  }

  public boolean messageMetadataByte() {
    return compactWireForms;
  }

  public boolean callstackSampleThreadFirst() {
    return compactWireForms;
  }

  public boolean stringLengthOffset() {
    return compactWireForms;
  }

  /** This version's wire ordinals and item sizes. */
  @NotNull
  public TracyQueueTable table() {
    TracyQueueTable loaded = table;
    if (loaded == null) {
      loaded = TracyQueueTable.load(this);
      table = loaded;
    }
    return loaded;
  }

  /** The version speaking the wire number, or null for one the receiver does not know. */
  @Nullable
  public static TracyProtocolVersion of(int wire) {
    for (TracyProtocolVersion version : values()) {
      if (version.wire == wire) return version;
    }
    return null;
  }

  @Override
  public String toString() {
    return wire + " (Tracy " + tracyRelease + ")";
  }
}
