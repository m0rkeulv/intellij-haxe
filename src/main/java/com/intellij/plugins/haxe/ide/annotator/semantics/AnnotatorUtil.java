package com.intellij.plugins.haxe.ide.annotator.semantics;

import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.plugins.haxe.lang.psi.HaxeClass;
import com.intellij.plugins.haxe.metadata.psi.HaxeMeta;
import com.intellij.plugins.haxe.model.HaxeClassModel;
import com.intellij.plugins.haxe.model.HaxeClassReferenceModel;
import com.intellij.plugins.haxe.v2.display.HaxeGeneratedCodePreview;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import static java.util.function.Predicate.not;

public class AnnotatorUtil {

  /**
   * Common entry guard for semantic annotators: skips elements that are no
   * longer valid and elements in generated-code preview files (see
   * {@link #isInGeneratedPreview} for why the preview gets no semantic
   * analysis). Validity is checked first — an invalidated element cannot be
   * asked for its containing file.
   */
  public static boolean shouldSkip(@NotNull PsiElement element) {
    return !element.isValid() || isInGeneratedPreview(element);
  }

  /**
   * Semantic annotators (errors/warnings) skip generated-code preview files:
   * the preview is a post-macro reconstruction where unresolvable names are
   * expected, so semantic analysis both wastes work and paints noise. The
   * color annotators do NOT consult this — the preview keeps highlighting.
   */
  public static boolean isInGeneratedPreview(@NotNull PsiElement element) {
    PsiFile file = element.getContainingFile();
    VirtualFile virtualFile = file != null ? file.getVirtualFile() : null;
    return virtualFile != null && virtualFile.getUserData(HaxeGeneratedCodePreview.PREVIEW_KEY) != null;
  }

  public static boolean hasMacroForCodeGeneration(@NotNull HaxeClassModel clazz) {
    if (clazz.hasCompileTimeMeta(HaxeMeta.BUILD)) return true;

    List<HaxeClassModel> classModels = clazz.getExtendingTypes().stream()
      .map(HaxeClassReferenceModel::getHaxeClassModel)
      .filter(Objects::nonNull)
      .collect(Collectors.toList());


    for (int i = 0; i < classModels.size(); i++) {
      HaxeClassModel model = classModels.get(i);
      HaxeClass aClass = model.haxeClass;
      if (aClass != null) {
        if (aClass.hasCompileTimeMeta(HaxeMeta.AUTO_BUILD)) {
          return true;
        }
        List<HaxeClassModel> list =
          model.getExtendingTypes().stream()
            .map(HaxeClassReferenceModel::getHaxeClassModel)
            .filter(not(classModels::contains))
            .toList();

        classModels.addAll(list);
      }
    }

    return false;
  }


}
