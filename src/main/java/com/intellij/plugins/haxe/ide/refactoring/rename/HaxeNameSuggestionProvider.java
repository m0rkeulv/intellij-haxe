package com.intellij.plugins.haxe.ide.refactoring.rename;

import com.intellij.plugins.haxe.ide.refactoring.HaxeRefactoringUtil;
import com.intellij.plugins.haxe.lang.psi.*;
import com.intellij.plugins.haxe.model.HaxeClassModel;
import com.intellij.plugins.haxe.model.HaxeClassReferenceModel;
import com.intellij.plugins.haxe.model.HaxeMethodModel;
import com.intellij.plugins.haxe.model.HaxeParameterModel;
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

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
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
  private static final String SETTER_PREFIX = "set_";
  private static final String SETTER_VALUE_NAME = "value";

  @Override
  public @Nullable SuggestedNameInfo getSuggestedNames(@NotNull PsiElement element,
                                                       @Nullable PsiElement nameSuggestionContext,
                                                       @NotNull Set<String> result) {
    HaxeNamedComponent declaration = declarationOf(element);
    if (declaration == null || declaration.getName() == null) return null;
    HaxeNameKind kind = kindOf(declaration);
    Set<String> used = usedNamesExcept(declaration);

    // the name the method's contract gives, then the name recased to convention, then names from the value
    Set<String> names = new LinkedHashSet<>();
    if (declaration instanceof HaxeParameter parameter) names.addAll(namesFromTheMethodContract(parameter, used));
    names.addAll(recasedCurrentName(declaration, kind, used));
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

  /**
   * What the method's contract calls the parameter: the overridden or
   * implemented method's parameter at the same position, and for a
   * property setter {@code value}, since the property's own name would
   * shadow the field the setter assigns.
   */
  @NotNull
  private static List<String> namesFromTheMethodContract(@NotNull HaxeParameter parameter, @NotNull Set<String> used) {
    if (!(parameter.getParent() instanceof HaxeParameterList parameters) || !(parameters.getParent() instanceof HaxeMethod method)) {
      return List.of();
    }
    HaxeMethodModel model = method.getModel();
    List<String> names = new ArrayList<>();
    int position = parameters.getParameterList().indexOf(parameter);
    for (HaxeMethodModel contract : contractsOf(model)) {
      List<HaxeParameterModel> contractParameters = contract.getParameters();
      if (position < contractParameters.size()) names.add(contractParameters.get(position).getName());
    }
    if (isPropertySetter(model)) names.add(SETTER_VALUE_NAME);
    return HaxeNameSuggesterUtil.getSuggestedNames(names, HaxeNameKind.VARIABLE, used);
  }

  /** The methods this one overrides or implements, nearest first. */
  @NotNull
  private static List<HaxeMethodModel> contractsOf(@NotNull HaxeMethodModel method) {
    List<HaxeMethodModel> contracts = new ArrayList<>();
    for (HaxeMethodModel parent = method.getParentMethod(null); parent != null; parent = parent.getParentMethod(null)) {
      if (contracts.contains(parent)) break;
      contracts.add(parent);
    }
    HaxeClassModel declaringClass = method.getDeclaringClass();
    if (declaringClass == null) return contracts;
    for (HaxeClassReferenceModel implemented : declaringClass.getImplementingInterfaces()) {
      HaxeClassModel interfaceModel = implemented.getHaxeClassModel();
      if (interfaceModel == null) continue;
      // the model's by-name lookup misses interface methods; the method list has them
      for (HaxeMethodModel declared : interfaceModel.getMethods(null)) {
        if (Objects.equals(declared.getName(), method.getName())) contracts.add(declared);
      }
    }
    return contracts;
  }

  /** A method {@code set_width} of a class with a property {@code width}. */
  private static boolean isPropertySetter(@NotNull HaxeMethodModel method) {
    String name = method.getName();
    HaxeClassModel declaringClass = method.getDeclaringClass();
    if (declaringClass == null || name == null || !name.startsWith(SETTER_PREFIX)) return false;
    return declaringClass.getField(name.substring(SETTER_PREFIX.length()), null) != null;
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
