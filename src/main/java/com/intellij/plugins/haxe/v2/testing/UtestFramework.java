package com.intellij.plugins.haxe.v2.testing;

import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.IndexNotReadyException;
import com.intellij.plugins.haxe.lang.psi.HaxeClass;
import com.intellij.plugins.haxe.lang.psi.HaxeMethod;
import com.intellij.plugins.haxe.model.HaxeClassModel;
import com.intellij.plugins.haxe.model.HaxeClassReferenceModel;
import com.intellij.plugins.haxe.model.HaxeMethodModel;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The utest framework: a test class transitively implements {@code utest.ITest}
 * ({@code utest.Test} implements it, so extends-Test project bases resolve through
 * the same chain); a test is a public instance method named {@code test*} or
 * {@code spec*} on such a class. Activation/filter defines follow utest's built-in
 * TeamCity reporter and {@code UTEST_PATTERN} single-test filter.
 */
public final class UtestFramework implements HaxeTestFramework {

  private static final String ITEST_QUALIFIED_NAME = "utest.ITest";
  private static final List<String> TEST_METHOD_PREFIXES = List.of("test", "spec");
  // pathological inheritance chains (macro-built, cyclic through typedefs) stay bounded
  private static final int MAX_SUPERTYPE_DEPTH = 32;

  @Override
  public boolean isTestClass(@NotNull HaxeClass haxeClass) {
    if (DumbService.isDumb(haxeClass.getProject())) return false;
    try {
      HaxeClassModel model = haxeClass.getModel();
      return model.isClass() && inheritsITest(model, new HashSet<>(), 0);
    }
    catch (IndexNotReadyException e) {
      // dumb mode can begin mid-walk; detection degrades to "not a test" rather than throwing
      return false;
    }
  }

  @Override
  public boolean isTestMethod(@NotNull HaxeMethod method) {
    if (!hasTestMethodPrefix(method.getName())) return false;
    if (DumbService.isDumb(method.getProject())) return false;
    try {
      HaxeMethodModel model = method.getModel();
      if (model.isConstructor() || model.isStatic() || !model.isPublic()) return false;
      HaxeClassModel declaringClass = model.getDeclaringClass();
      return declaringClass != null && isTestClass(declaringClass.haxeClass);
    }
    catch (IndexNotReadyException e) {
      return false;
    }
  }

  @Override
  public @NotNull ResultChannel resultChannel() {
    return ResultChannel.STDOUT_TEAMCITY;
  }

  @Override
  public @NotNull List<String> activationArgs() {
    return List.of("-D", "teamcity");
  }

  @Override
  public @NotNull List<String> filterArgs(@Nullable String pattern) {
    return pattern == null ? List.of() : List.of("-D", "UTEST_PATTERN=" + pattern);
  }

  private static boolean hasTestMethodPrefix(@NotNull String name) {
    return TEST_METHOD_PREFIXES.stream().anyMatch(name::startsWith);
  }

  /** Walks extends + implements transitively; visited qualified names stop diamond/cyclic chains. */
  private static boolean inheritsITest(@NotNull HaxeClassModel model, @NotNull Set<String> visited, int depth) {
    if (depth > MAX_SUPERTYPE_DEPTH) return false;

    List<HaxeClassReferenceModel> supers = new ArrayList<>(model.getExtendingTypes());
    supers.addAll(model.getImplementingInterfaces());
    for (HaxeClassReferenceModel superReference : supers) {
      HaxeClassModel superModel = superReference.getHaxeClassModel();
      if (superModel == null) continue;
      String qualifiedName = superModel.haxeClass.getQualifiedName();
      if (qualifiedName == null || !visited.add(qualifiedName)) continue;
      if (ITEST_QUALIFIED_NAME.equals(qualifiedName)) return true;
      if (inheritsITest(superModel, visited, depth + 1)) return true;
    }
    return false;
  }
}
