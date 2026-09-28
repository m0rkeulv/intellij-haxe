package com.intellij.plugins.haxe.ide.refactoring.rename;

import com.intellij.plugins.haxe.ide.refactoring.HaxeRefactoringUtil;
import com.intellij.plugins.haxe.lang.psi.HaxeExpression;
import com.intellij.plugins.haxe.lang.psi.HaxeNamedComponent;
import com.intellij.plugins.haxe.lang.psi.HaxeParameter;
import com.intellij.plugins.haxe.lang.psi.HaxePsiField;
import com.intellij.plugins.haxe.lang.psi.HaxeTypeTag;
import com.intellij.plugins.haxe.lang.psi.HaxeVarInit;
import com.intellij.plugins.haxe.model.type.HaxeTypeResolver;
import com.intellij.plugins.haxe.model.type.ResultHolder;
import com.intellij.plugins.haxe.model.type.SpecificHaxeClassReference;
import com.intellij.plugins.haxe.util.HaxeNameSuggesterUtil;
import com.intellij.psi.PsiElement;
import com.intellij.psi.codeStyle.SuggestedNameInfo;
import com.intellij.refactoring.rename.NameSuggestionProvider;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Rename suggestions for a value declaration, which is a local, a field or a
 * parameter: names read off its initializer, then off its declared type,
 * through the name suggester. Methods and types get none. The names avoid
 * those in use around the declaration, except the declaration's own, which
 * a rename is free to keep.
 */
public class HaxeNameSuggestionProvider implements NameSuggestionProvider {

  @Override
  public @Nullable SuggestedNameInfo getSuggestedNames(@NotNull PsiElement element,
                                                       @Nullable PsiElement nameSuggestionContext,
                                                       @NotNull Set<String> result) {
    HaxeVarInit initializer;
    HaxeTypeTag typeTag;
    if (element instanceof HaxePsiField field) {
      initializer = field.getVarInit();
      typeTag = field.getTypeTag();
    } else if (element instanceof HaxeParameter parameter) {
      initializer = parameter.getVarInit();
      typeTag = parameter.getTypeTag();
    } else {
      return null;
    }

    Set<String> used = usedNamesExcept((HaxeNamedComponent)element);
    Set<String> names = new LinkedHashSet<>();
    HaxeExpression initialValue = initializer == null ? null : initializer.getExpression();
    if (initialValue != null) {
      names.addAll(HaxeNameSuggesterUtil.getSuggestedNames(initialValue, false, false, used));
    }
    if (typeTag != null) {
      names.addAll(namesForType(HaxeTypeResolver.getTypeFromTypeTag(typeTag, element), used));
    }
    result.addAll(names);
    return names.isEmpty() ? null : SuggestedNameInfo.NULL_INFO;
  }

  @NotNull
  private static Set<String> namesForType(@Nullable ResultHolder type, @NotNull Set<String> used) {
    if (type == null) return Set.of();
    SpecificHaxeClassReference classType = type.getClassType();
    String typeName = classType == null ? null : classType.getClassName();
    return new LinkedHashSet<>(HaxeNameSuggesterUtil.getSuggestedNamesForType(null, typeName, type.isFunctionType(), null, used));
  }

  @NotNull
  private static Set<String> usedNamesExcept(@NotNull HaxeNamedComponent declaration) {
    Set<String> used = HaxeRefactoringUtil.collectUsedNames(declaration);
    String ownName = declaration.getName();
    if (ownName != null) used.remove(ownName);
    return used;
  }
}
