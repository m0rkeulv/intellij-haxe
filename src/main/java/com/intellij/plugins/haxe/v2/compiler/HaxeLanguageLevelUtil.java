package com.intellij.plugins.haxe.v2.compiler;

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.project.Project;
import com.intellij.plugins.haxe.v2.buildtools.HaxeBuildConfigListener;
import com.intellij.plugins.haxe.v2.compiler.settings.HaxeCompilerSettings;
import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.NotNull;

/**
 * The language level editor features assume for a PSI element: the element's
 * module's effective level (Settings | Compiler | Haxe Compiler, mirrored by
 * the tool window's Language level row). Elements outside any module use the
 * project default. See doc/haxe-language-levels.md for what each level
 * supports. Call in a read action.
 */
public final class HaxeLanguageLevelUtil {

  private HaxeLanguageLevelUtil() {
  }

  @NotNull
  public static HaxeLanguageLevel getLanguageLevel(@NotNull PsiElement element) {
    HaxeCompilerSettings settings = HaxeCompilerSettings.getInstance(element.getProject());
    Module module = ModuleUtilCore.findModuleForPsiElement(element);
    return module != null ? settings.getEffectiveLanguageLevel(module.getName())
                          : settings.getDefaultLanguageLevel();
  }

  public static boolean isAtLeast(@NotNull PsiElement element, @NotNull HaxeLanguageLevel level) {
    return getLanguageLevel(element).isAtLeast(level);
  }

  /**
   * Applies the level as the element's MODULE override (the project default
   * when there is no module) and refreshes highlighting plus the tool
   * window's Language level row. Backs the "set language level" quickfix.
   */
  public static void setLanguageLevel(@NotNull PsiElement context, @NotNull HaxeLanguageLevel level) {
    Project project = context.getProject();
    HaxeCompilerSettings settings = HaxeCompilerSettings.getInstance(project);
    Module module = ModuleUtilCore.findModuleForPsiElement(context);
    if (module != null) {
      settings.setModuleLanguageLevelOverride(module.getName(), level);
    }
    else {
      settings.setDefaultLanguageLevel(level);
    }
    project.getMessageBus().syncPublisher(HaxeBuildConfigListener.TOPIC).buildConfigurationChanged();
    DaemonCodeAnalyzer.getInstance(project).restart("haxe: language level changed");
  }
}
