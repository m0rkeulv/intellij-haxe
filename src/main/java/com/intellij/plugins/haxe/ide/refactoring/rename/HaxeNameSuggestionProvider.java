package com.intellij.plugins.haxe.ide.refactoring.rename;

import com.intellij.plugins.haxe.ide.refactoring.HaxeRefactoringUtil;
import com.intellij.plugins.haxe.lang.psi.*;
import com.intellij.plugins.haxe.model.type.HaxeTypeResolver;
import com.intellij.plugins.haxe.model.type.ResultHolder;
import com.intellij.plugins.haxe.util.HaxeNameKind;
import com.intellij.plugins.haxe.util.HaxeNameSuggesterUtil;
import com.intellij.plugins.haxe.util.HaxeSuggestedNames;
import com.intellij.psi.PsiElement;
import com.intellij.psi.codeStyle.SuggestedNameInfo;
import com.intellij.refactoring.rename.NameSuggestionProvider;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Rename suggestions for a Haxe declaration. First the current name recased
 * for its kind: a class named {@code myThing} is offered {@code MyThing}, a
 * constant {@code maxCount} is offered {@code MAX_COUNT}. Then, for a value
 * declaration (a local, a field, a parameter), names read off its
 * initializer and its declared type; the chosen name is remembered for the
 * next value like it. The names avoid
 * those in use around the declaration, except the declaration's own, which
 * a rename is free to keep.
 */
public class HaxeNameSuggestionProvider implements NameSuggestionProvider {

  @Override
  public @Nullable SuggestedNameInfo getSuggestedNames(@NotNull PsiElement element,
                                                       @Nullable PsiElement nameSuggestionContext,
                                                       @NotNull Set<String> result) {
    HaxeNamedComponent declaration = declarationOf(element);
    if (declaration == null || declaration.getName() == null) return null;
    HaxeNameKind kind = kindOf(declaration);
    Set<String> used = usedNamesExcept(declaration);

    // the name recased to convention first: it is the rename the user most likely came for
    Set<String> names = new LinkedHashSet<>(recasedCurrentName(declaration, kind, used));
    HaxeSuggestedNames suggested = suggestForValue(declaration, kind, used);
    names.addAll(suggested.names());
    names.remove(declaration.getName());
    if (names.isEmpty()) return null;
    result.addAll(names);
    return suggested.isEmpty() ? SuggestedNameInfo.NULL_INFO : suggested.asInfo();
  }

  /**
   * The declaring component: rename started on a declaration hands over the
   * name element, rename started on a reference the component it resolves to.
   */
  @Nullable
  private static HaxeNamedComponent declarationOf(@NotNull PsiElement element) {
    if (element instanceof HaxeComponentName name && name.getParent() instanceof HaxeNamedComponent component) return component;
    return element instanceof HaxeNamedComponent component ? component : null;
  }

  /** Names from a value's initializer and declared type; other declarations give none. */
  @NotNull
  private static HaxeSuggestedNames suggestForValue(@NotNull HaxeNamedComponent declaration,
                                                    @NotNull HaxeNameKind kind,
                                                    @NotNull Set<String> used) {
    HaxeVarInit initializer;
    HaxeTypeTag typeTag;
    if (declaration instanceof HaxePsiField field) {
      initializer = field.getVarInit();
      typeTag = field.getTypeTag();
    } else if (declaration instanceof HaxeParameter parameter) {
      initializer = parameter.getVarInit();
      typeTag = parameter.getTypeTag();
    } else {
      return HaxeSuggestedNames.NONE;
    }
    HaxeExpression initialValue = initializer == null ? null : initializer.getExpression();
    ResultHolder type = typeTag == null ? null : HaxeTypeResolver.getTypeFromTypeTag(typeTag, declaration);
    if (initialValue == null && type == null) return HaxeSuggestedNames.NONE;
    return HaxeNameSuggesterUtil.suggest(initialValue, type, kind, null, used);
  }

  /** The current name in the kind's casings; the caller drops the name itself. */
  @NotNull
  private static List<String> recasedCurrentName(@NotNull HaxeNamedComponent declaration,
                                                 @NotNull HaxeNameKind kind,
                                                 @NotNull Set<String> used) {
    return HaxeNameSuggesterUtil.getRecasedName(declaration.getName(), kind, used);
  }

  @NotNull
  private static HaxeNameKind kindOf(@NotNull HaxeNamedComponent declaration) {
    return switch (declaration) {
      case HaxeClass ignored -> HaxeNameKind.TYPE;
      case HaxeEnumValueDeclaration ignored -> HaxeNameKind.ENUM_VALUE;
      case HaxeMethod ignored -> HaxeNameKind.METHOD;
      case HaxeFieldDeclaration field when isConstant(field) -> HaxeNameKind.CONSTANT;
      default -> HaxeNameKind.VARIABLE;
    };
  }

  /** A static field that is final or inline holds a constant. */
  private static boolean isConstant(@NotNull HaxeFieldDeclaration field) {
    if (!field.isStatic()) return false;
    HaxeMutabilityModifier mutability = field.getMutabilityModifier();
    return field.isInline() || mutability != null && mutability.textMatches("final");
  }

  @NotNull
  private static Set<String> usedNamesExcept(@NotNull HaxeNamedComponent declaration) {
    Set<String> used = HaxeRefactoringUtil.collectUsedNames(declaration);
    String ownName = declaration.getName();
    if (ownName != null) used.remove(ownName);
    return used;
  }
}
