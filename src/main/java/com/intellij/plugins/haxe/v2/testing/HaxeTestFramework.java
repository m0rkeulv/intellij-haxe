package com.intellij.plugins.haxe.v2.testing;

import com.intellij.openapi.project.Project;
import com.intellij.plugins.haxe.lang.psi.HaxeClass;
import com.intellij.plugins.haxe.lang.psi.HaxeMethod;
import com.intellij.psi.PsiElement;
import com.intellij.psi.search.GlobalSearchScope;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * A Haxe test framework the plugin can detect and run: it recognizes test
 * classes/methods in source, and supplies the compiler arguments that make a
 * tests build report TeamCity service messages on stdout — every framework's
 * results ride that one channel, through the framework's own reporter when it
 * ships none (see the reporter sources under {@code resources/testing/}).
 * One implementation per framework; a tests build's framework is picked from
 * its {@code -lib} declarations by {@link #libraryName}.
 */
// TODO: wire into the gutter run-line markers (detection); the run
//       configuration consumes the argument seam already.
public interface HaxeTestFramework {

  /** The haxelib whose presence in the tests build declares this framework. */
  @NotNull
  String libraryName();

  /** Whether the framework runs on the eval interpreter — munit predates it and hangs there. */
  boolean supportsInterp();

  /** Whether the class is a runnable test case of this framework. Detection only - false in dumb mode. */
  boolean isTestClass(@NotNull HaxeClass haxeClass);

  /** Whether the method is an individual test of this framework. Detection only - false in dumb mode. */
  boolean isTestMethod(@NotNull HaxeMethod method);

  /**
   * Compiler arguments making the run report TeamCity service messages the
   * IDE's test console can parse. {@code suiteName} labels the run's root
   * suite (null for none); {@code reporterClasspath} is the extracted shipped
   * reporter's {@code -cp} root, null when extraction failed;
   * {@code liveReporting} is the Build Tools toggle — frameworks whose ONLY
   * result channel is the shipped reporter ignore it.
   */
  @NotNull
  List<String> reportingArgs(@Nullable String suiteName, @Nullable String reporterClasspath, boolean liveReporting);

  /**
   * Compiler arguments narrowing the run to tests matching the pattern
   * (utest: `-D UTEST_PATTERN=Class.method`); empty when the pattern is null
   * or the framework cannot filter.
   */
  @NotNull
  List<String> filterArgs(@Nullable String pattern);

  /**
   * Resolves a location URL this framework's reporting emitted to the PSI
   * element a result double-click navigates to; null when the URL is not this
   * framework's or nothing matches. The default covers reporters naming tests
   * as {@code Class.method} identifiers on the {@code haxe:test} protocol
   * (utest, munit), whose hints may carry the run's tests build file as a
   * {@code ?build=} suffix to break same-name ties between sibling projects;
   * buddy and tink override with file-carrying protocols of their own.
   * Call in a read action.
   */
  @Nullable
  default PsiElement resolveTestLocation(@NotNull Project project,
                                         @NotNull GlobalSearchScope scope,
                                         @NotNull String protocol,
                                         @NotNull String path) {
    if (!"haxe:test".equals(protocol)) return null;
    int marker = path.lastIndexOf("?build=");
    String name = marker < 0 ? path : path.substring(0, marker);
    String buildFilePath = marker < 0 ? null : path.substring(marker + "?build=".length());
    return HaxeTestNameLocation.resolve(name, buildFilePath, project, scope);
  }
}
