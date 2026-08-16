package com.intellij.plugins.haxe.v2.runconfig;

import com.intellij.plugins.haxe.config.HaxeTarget;
import com.intellij.plugins.haxe.v2.buildtools.HaxeDebugAdditions;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

/**
 * ONE home for "does a debugger lane exist for this target". Program and
 * test sessions gate on it alike — they used to keep separate answers, and
 * neko offered a program Debug it has no debugger for. The build-system side
 * of the question (which target does THIS file's selection compile to) is
 * {@code HaxeBuildSystem}'s job.
 */
public final class HaxeDebugSupport {

  /**
   * Targets a PROGRAM debug session has a lane for: HashLink (DAP adapter),
   * desktop C++ (in-debuggee hxcpp server), browser JS (CDP adapters) and
   * Flash. Their compiles take {@link HaxeDebugAdditions}.
   */
  private static final Set<HaxeTarget> PROGRAM_TARGETS =
    Set.of(HaxeTarget.HL, HaxeTarget.CPP, HaxeTarget.JAVA_SCRIPT, HaxeTarget.FLASH);

  /**
   * Targets a TEST debug session has a lane for. Tests additionally debug the
   * interpreter (the compile IS the debuggee — the eval lane) and flash (fdb
   * hosts the swf under adl; the test console parses the trace lines fdb
   * relays — see {@code HaxeTestFlashDebugRunner}). js tests stay deferred:
   * the browser adapters route program output through the CDP connection and
   * the test console cannot parse their results yet.
   */
  private static final Set<HaxeTarget> TEST_TARGETS =
    Set.of(HaxeTarget.INTERP, HaxeTarget.HL, HaxeTarget.CPP, HaxeTarget.FLASH);

  private HaxeDebugSupport() {
  }

  public static boolean supportsProgramDebug(@Nullable HaxeTarget target) {
    return target != null && PROGRAM_TARGETS.contains(target);
  }

  public static boolean supportsTestDebug(@Nullable HaxeTarget target) {
    return target != null && TEST_TARGETS.contains(target);
  }
}
