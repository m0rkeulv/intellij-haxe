package com.intellij.plugins.haxe.v2.testing;

import com.intellij.plugins.haxe.lang.psi.HaxeClass;
import com.intellij.plugins.haxe.lang.psi.HaxeMethod;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * A Haxe test framework the plugin can detect and run: it recognizes test
 * classes/methods in source, and supplies the compiler arguments that switch a
 * tests build into reporting mode and narrow a run to selected tests. One
 * implementation per framework (utest first; munit/buddy behind the same seam).
 */
// TODO: wire into the gutter run-line markers (detection); the run
//       configuration consumes the activation/filter seam already.
public interface HaxeTestFramework {

  /** How a framework's runner reports results back to the IDE. */
  enum ResultChannel {
    /** TeamCity service messages on the run's stdout; the SM console parses them directly (utest). */
    STDOUT_TEAMCITY,
    /**
     * A results file written by the vshaxe test-adapter's {@code .unittest/} protocol,
     * watched and translated into SM events. No implementation yet - Phase 4 serves it
     * for the adapter-backed frameworks (munit, buddy, tink_unittest, hexUnit, haxe.unit).
     */
    RESULTS_FILE
  }

  /** The channel this framework's runs report through. */
  @NotNull
  ResultChannel resultChannel();

  /** Whether the class is a runnable test case of this framework. Detection only - false in dumb mode. */
  boolean isTestClass(@NotNull HaxeClass haxeClass);

  /** Whether the method is an individual test of this framework. Detection only - false in dumb mode. */
  boolean isTestMethod(@NotNull HaxeMethod method);

  /**
   * Compiler arguments appended to the tests build so the run reports results the
   * IDE's test console can parse (utest: `-D teamcity` service messages on stdout).
   */
  @NotNull
  List<String> activationArgs();

  /**
   * Compiler arguments narrowing the run to tests matching the pattern
   * (utest: `-D UTEST_PATTERN=Class.method`); empty when the pattern is null.
   */
  @NotNull
  List<String> filterArgs(@Nullable String pattern);
}
