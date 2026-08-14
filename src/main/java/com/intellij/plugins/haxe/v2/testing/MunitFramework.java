package com.intellij.plugins.haxe.v2.testing;

import com.intellij.plugins.haxe.lang.psi.HaxeClass;
import com.intellij.plugins.haxe.lang.psi.HaxeMethod;
import com.intellij.plugins.haxe.metadata.psi.HaxeMeta;
import com.intellij.plugins.haxe.model.HaxeMethodModel;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The munit (MassiveUnit) framework: a test is a public instance method
 * carrying runtime {@code @Test} (or {@code @AsyncTest}) metadata; a test
 * class is any class declaring one. Detection is purely syntactic — munit
 * requires no marker interface.
 *
 * munit has no TeamCity reporter of its own, so the shipped
 * {@code intellij_munit} client (attached into {@code massive.munit.TestRunner}
 * by its build macro) IS the IDE's result channel, not an optional
 * enhancement. Wire facts, all verified against munit 2.3.5: the runner hangs
 * on the eval interpreter (munit predates it); the classic TestMain exits 0
 * even on failures (its delayed completion handler misses the process end),
 * so verdicts come from the events alone; munit has no test-filter define.
 */
public final class MunitFramework implements HaxeTestFramework {

  private static final List<String> TEST_METADATA = List.of("Test", "AsyncTest");

  @Override
  public @NotNull String libraryName() {
    return "munit";
  }

  @Override
  public boolean supportsInterp() {
    return false;
  }

  @Override
  public boolean isTestClass(@NotNull HaxeClass haxeClass) {
    return haxeClass.getModel().isClass()
           && haxeClass.getHaxeMethodsSelf(null).stream().anyMatch(MunitFramework::hasTestMetadata);
  }

  @Override
  public boolean isTestMethod(@NotNull HaxeMethod method) {
    if (!hasTestMetadata(method)) return false;
    HaxeMethodModel model = method.getModel();
    return !model.isConstructor() && !model.isStatic() && model.isPublic();
  }

  @Override
  public @NotNull List<String> reportingArgs(@Nullable String suiteName,
                                             @Nullable String reporterClasspath,
                                             boolean liveReporting) {
    // without the extracted reporter there is nothing to report through -
    // the run still executes with console output only
    if (reporterClasspath == null) return List.of();
    List<String> arguments = new ArrayList<>();
    // the injected client reads the root suite name from this define at macro time
    if (suiteName != null) {
      arguments.add("-D");
      arguments.add("teamcity_suite_name=" + suiteName);
    }
    arguments.add("-cp");
    arguments.add(reporterClasspath);
    arguments.add("--macro");
    arguments.add("intellij_munit.Macro.init()");
    return arguments;
  }

  @Override
  public @NotNull List<String> filterArgs(@Nullable String pattern) {
    // TODO Phase 2: munit has no filter define - single-test runs need a
    //  generated TestSuite narrowing the run to the selected class/method
    return List.of();
  }

  private static boolean hasTestMetadata(@NotNull HaxeMethod method) {
    return method.getMetadataList(HaxeMeta.RUN_TIME).stream()
      .anyMatch(meta -> TEST_METADATA.stream().anyMatch(meta::isType));
  }
}
