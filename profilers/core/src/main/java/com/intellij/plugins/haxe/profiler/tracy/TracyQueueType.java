package com.intellij.plugins.haxe.profiler.tracy;

import org.jetbrains.annotations.Nullable;

/**
 * Tracy protocol v74's queue-item vocabulary, verbatim from the client
 * sources hxcpp bundles ({@code common/TracyQueue.hpp}, tracy 0.12.0). The
 * DECLARATION ORDER IS THE WIRE VALUE — each item starts with the type's
 * ordinal byte. {@code wireSize} is the item's fixed size INCLUDING that
 * byte (extracted mechanically from the compiler's own
 * {@code QueueDataSize[]}; the committed
 * {@code tracy/queue-data-sizes-v74.txt} fixture guards against drift).
 * Payload types carry extra inline data right after the item: a u16 or u32
 * length plus that many bytes.
 */
public enum TracyQueueType {
  ZoneText(1),
  ZoneName(1),
  Message(9),
  MessageColor(12),
  MessageCallstack(9),
  MessageColorCallstack(12),
  MessageAppInfo(9),
  ZoneBeginAllocSrcLoc(9),
  ZoneBeginAllocSrcLocCallstack(9),
  CallstackSerial(1),
  Callstack(1),
  CallstackAlloc(1),
  CallstackSample(13),
  CallstackSampleContextSwitch(13),
  FrameImage(10),
  ZoneBegin(17),
  ZoneBeginCallstack(17),
  ZoneEnd(9),
  LockWait(17),
  LockObtain(17),
  LockRelease(13),
  LockSharedWait(17),
  LockSharedObtain(17),
  LockSharedRelease(17),
  LockName(5),
  MemAlloc(27),
  MemAllocNamed(27),
  MemFree(21),
  MemFreeNamed(21),
  MemAllocCallstack(27),
  MemAllocCallstackNamed(27),
  MemFreeCallstack(21),
  MemFreeCallstackNamed(21),
  MemDiscard(21),
  MemDiscardCallstack(21),
  GpuZoneBegin(24),
  GpuZoneBeginCallstack(24),
  GpuZoneBeginAllocSrcLoc(16),
  GpuZoneBeginAllocSrcLocCallstack(16),
  GpuZoneEnd(16),
  GpuZoneBeginSerial(24),
  GpuZoneBeginCallstackSerial(24),
  GpuZoneBeginAllocSrcLocSerial(16),
  GpuZoneBeginAllocSrcLocCallstackSerial(16),
  GpuZoneEndSerial(16),
  PlotDataInt(25),
  PlotDataFloat(21),
  PlotDataDouble(25),
  ContextSwitch(23),
  ThreadWakeup(16),
  GpuTime(12),
  GpuContextName(2),
  CallstackFrameSize(10),
  SymbolInformation(13),
  ExternalNameMetadata(1),
  SymbolCodeMetadata(1),
  SourceCodeMetadata(1),
  FiberEnter(25),
  FiberLeave(13),
  Terminate(1),
  KeepAlive(1),
  ThreadContext(5),
  GpuCalibration(26),
  GpuTimeSync(18),
  Crash(1),
  CrashReport(17),
  ZoneValidation(5),
  ZoneColor(4),
  ZoneValue(9),
  FrameMarkMsg(17),
  FrameMarkMsgStart(17),
  FrameMarkMsgEnd(17),
  FrameVsync(13),
  SourceLocation(32),
  LockAnnounce(22),
  LockTerminate(13),
  LockMark(17),
  MessageLiteral(17),
  MessageLiteralColor(20),
  MessageLiteralCallstack(17),
  MessageLiteralColorCallstack(20),
  GpuNewContext(28),
  CallstackFrame(17),
  SysTimeReport(13),
  SysPowerReport(25),
  TidToPid(17),
  HwSampleCpuCycle(17),
  HwSampleInstructionRetired(17),
  HwSampleCacheReference(17),
  HwSampleCacheMiss(17),
  HwSampleBranchRetired(17),
  HwSampleBranchMiss(17),
  PlotConfig(16),
  ParamSetup(18),
  AckServerQueryNoop(1),
  AckSourceCodeNotAvailable(5),
  AckSymbolCodeNotAvailable(1),
  CpuTopology(17),
  SingleStringData(1, Payload.U16),
  SecondStringData(1, Payload.U16),
  // fixed 9 bytes (u64 name pointer) - NOT a string transfer; the C++
  // table's transfer group starts below at StringData
  MemNamePayload(9),
  ThreadGroupHint(9),
  StringData(9, Payload.U16),
  ThreadName(9, Payload.U16),
  PlotName(9, Payload.U16),
  SourceLocationPayload(9, Payload.U16),
  CallstackPayload(9, Payload.U16),
  CallstackAllocPayload(9, Payload.U16),
  FrameName(9, Payload.U16),
  FrameImageData(9, Payload.U32),
  ExternalName(9, Payload.U16),
  ExternalThreadName(9, Payload.U16),
  SymbolCode(9, Payload.U32),
  SourceCode(9, Payload.U32),
  FiberName(9, Payload.U16);

  /** The inline data following the fixed item, when any. */
  public enum Payload {
    NONE,
    U16,
    U32
  }

  private static final TracyQueueType[] BY_WIRE_VALUE = values();

  private final int wireSize;
  private final Payload payload;

  TracyQueueType(int wireSize) {
    this(wireSize, Payload.NONE);
  }

  TracyQueueType(int wireSize, Payload payload) {
    this.wireSize = wireSize;
    this.payload = payload;
  }

  /** The item's fixed size on the wire, including the type byte itself. */
  public int wireSize() {
    return wireSize;
  }

  public Payload payload() {
    return payload;
  }

  @Nullable
  public static TracyQueueType of(int wireValue) {
    return wireValue >= 0 && wireValue < BY_WIRE_VALUE.length ? BY_WIRE_VALUE[wireValue] : null;
  }
}
