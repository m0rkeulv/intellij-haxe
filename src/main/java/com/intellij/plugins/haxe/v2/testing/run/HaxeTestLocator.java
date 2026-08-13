package com.intellij.plugins.haxe.v2.testing.run;

import com.intellij.execution.Location;
import com.intellij.execution.PsiLocation;
import com.intellij.execution.testframework.sm.runner.SMTestLocator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.plugins.haxe.lang.psi.HaxeClass;
import com.intellij.plugins.haxe.lang.psi.indexes.unified.HaxeClassNameUnifiedIndex;
import com.intellij.plugins.haxe.util.HaxeResolveUtil;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.search.GlobalSearchScope;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/// Resolves the `haxe:test://<name>` location URLs the run's converter injects.
/// utest's TeamCity reporter names things as
/// `<package with dots replaced by underscores>.<ClassName>[.<methodName>]`,
/// with an EMPTY leading segment for the default package (`.MyTest.testX`).
/// Resolution order per name: the whole name as a class (suite rows), then
/// `class.method` split at the last dot (test rows); a class part resolves as a
/// literal FQN, as the underscores-to-dots package variant, and finally through
/// the simple-name index filtered by the reporter's naming (packages that
/// genuinely contain underscores stay ambiguous to the two rewrites).
public final class HaxeTestLocator implements SMTestLocator {

  public static final String PROTOCOL = "haxe:test";
  public static final HaxeTestLocator INSTANCE = new HaxeTestLocator();

  private HaxeTestLocator() {
  }

  @Override
  public @NotNull List<Location> getLocation(@NotNull String protocol,
                                             @NotNull String path,
                                             @NotNull Project project,
                                             @NotNull GlobalSearchScope scope) {
    if (!PROTOCOL.equals(protocol)) return List.of();
    String name = StringUtil.trimLeading(path, '.');

    HaxeClass wholeNameClass = resolveClass(name, project, scope);
    if (wholeNameClass != null) {
      return List.of(PsiLocation.fromPsiElement(wholeNameClass));
    }

    int lastDot = name.lastIndexOf('.');
    if (lastDot <= 0) return List.of();
    HaxeClass testClass = resolveClass(name.substring(0, lastDot), project, scope);
    if (testClass == null) return List.of();

    PsiMethod method = findMethod(testClass, name.substring(lastDot + 1));
    return List.of(PsiLocation.fromPsiElement(method != null ? method : testClass));
  }

  @Nullable
  private static HaxeClass resolveClass(@NotNull String teamcityName,
                                        @NotNull Project project,
                                        @NotNull GlobalSearchScope scope) {
    PsiManager psiManager = PsiManager.getInstance(project);
    for (String candidate : fqnCandidates(teamcityName)) {
      HaxeClass found = HaxeResolveUtil.findClassByQName(candidate, psiManager, scope);
      if (found != null) return found;
    }
    // packages containing real underscores are indistinguishable from the
    // reporter's dot rewrite - fall back to the simple name and compare through
    // the reporter's own naming
    String simpleName = StringUtil.getShortName(teamcityName);
    return HaxeClassNameUnifiedIndex.getByNameFiltered(simpleName, project, scope).stream()
      .filter(candidate -> teamcityName.equals(teamcityNameOf(candidate.getQualifiedName())))
      .findFirst()
      .orElse(null);
  }

  @NotNull
  private static List<String> fqnCandidates(@NotNull String teamcityName) {
    List<String> candidates = new ArrayList<>();
    candidates.add(teamcityName);
    String packagePart = StringUtil.getPackageName(teamcityName);
    if (packagePart.indexOf('_') >= 0) {
      candidates.add(packagePart.replace('_', '.') + "." + StringUtil.getShortName(teamcityName));
    }
    return candidates;
  }

  /** The reporter's spelling of a qualified class name: dots in the package become underscores. */
  @Nullable
  static String teamcityNameOf(@Nullable String qualifiedName) {
    if (qualifiedName == null) return null;
    String packagePart = StringUtil.getPackageName(qualifiedName);
    if (packagePart.isEmpty()) return qualifiedName;
    return packagePart.replace('.', '_') + "." + StringUtil.getShortName(qualifiedName);
  }

  @Nullable
  private static PsiMethod findMethod(@NotNull HaxeClass haxeClass, @NotNull String methodName) {
    for (PsiMethod method : haxeClass.getMethods()) {
      if (methodName.equals(method.getName())) return method;
    }
    return null;
  }
}
