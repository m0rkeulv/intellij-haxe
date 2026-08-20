package com.intellij.plugins.haxe.ide.inspections;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.plugins.haxe.ide.annotator.HaxeProblemReporter;
import com.intellij.plugins.haxe.ide.annotator.semantics.AnnotatorUtil;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import org.jetbrains.annotations.NotNull;

import java.util.function.BiConsumer;

/**
 * Base for the plugin's inspections: registration and metadata (display
 * name, group, default level, enabled state, description) live in the
 * {@code <localInspection>} entry in plugin.xml and the standard
 * {@code inspectionDescriptions/<shortName>.html} file — the inspection
 * framework owns toggling and severity. This base carries only the shared
 * visitor plumbing.
 */
public abstract class HaxeInspection extends LocalInspectionTool {

  /**
   * The visitor shape most checks share: one element type, one check method
   * reporting through {@link HaxeProblemReporter}. A problem at the severity
   * of {@link #getDefaultLevel()} follows the profile's severity choice —
   * override it to match the plugin.xml {@code level} attribute, or the two
   * defaults diverge.
   */
  protected final <T extends PsiElement> PsiElementVisitor checkVisitor(@NotNull ProblemsHolder holder,
                                                                        @NotNull Class<T> elementType,
                                                                        @NotNull BiConsumer<T, HaxeProblemReporter> check) {
    HaxeProblemReporter reporter = HaxeProblemReporter.of(holder, getDefaultLevel().getSeverity());
    return new PsiElementVisitor() {
      @Override
      public void visitElement(@NotNull PsiElement element) {
        if (AnnotatorUtil.shouldSkip(element)) return;
        if (elementType.isInstance(element)) {
          check.accept(elementType.cast(element), reporter);
        }
      }
    };
  }
}
