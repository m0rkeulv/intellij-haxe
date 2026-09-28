/*
 * Copyright 2000-2013 JetBrains s.r.o.
 * Copyright 2014-2014 AS3Boyan
 * Copyright 2014-2014 Elias Ku
 * Copyright 2019-2020 Eric Bishton
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.plugins.haxe.util;

import com.intellij.openapi.util.text.StringUtil;
import com.intellij.plugins.haxe.ide.refactoring.HaxeRefactoringUtil;
import com.intellij.plugins.haxe.lang.psi.*;
import com.intellij.plugins.haxe.lang.util.HaxeExpressionUtil;
import com.intellij.plugins.haxe.model.type.*;
import com.intellij.psi.PsiElement;
import com.intellij.psi.codeStyle.NameUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author: Fedor.Korotkov
 */
public class HaxeNameSuggesterUtil {
  /** The conventional short name of a value of each standard type, by the type's simple name. */
  private static final Map<String, String> CONVENTIONAL_TYPE_NAMES = Map.of(
    "Dynamic", "obj",
    "Void", "v",
    "Int", "i",
    "Bool", "b",
    "Float", "f",
    "String", "str",
    "Array", "arr",
    "Map", "map");
  private static final String FUNCTION_VALUE_NAME = "func";

  private HaxeNameSuggesterUtil() {
  }

  private static String deleteNonLetterFromString(@NotNull final String string) {
    Pattern pattern = Pattern.compile("[^a-zA-Z_]+");
    Matcher matcher = pattern.matcher(string);
    return matcher.replaceAll("_");
  }

  @NotNull
  public static String prepareNameTextForSuggestions(@NotNull String name) {
    // Note: decapitalize only changes the first letter to lower case, but won't do if the second letter is also uppercase.
    name = StringUtil.decapitalize(deleteNonLetterFromString(StringUtil.unquoteString(name.replace('.', '_'))));

    StringBuilder prepped = new StringBuilder();
    int startPos = 0;
    int endPos = name.length() - 1;

    // Trim (skip past) underscores and common leading words.
    // (Leave an underscore or name if that is the entirety of the remaining text.)
    while (startPos < endPos && '_' == name.charAt(startPos)) ++startPos;
    while (endPos > startPos && '_' == name.charAt(endPos)) --endPos;
    for (String prefix : new String[]{"get", "is"}) {
      if (name.startsWith(prefix) && (endPos - startPos) > prefix.length()) {
        startPos += prefix.length();
      }
    }

    // Copy the string, removing consecutive underscores.
    char c = '_';  // Assume last char is '_' to skip any underscores after 'get' or 'is', as in get_myVar();
    for (int i = startPos; i <= endPos; i++) {
      if (c == '_' && name.charAt(i) == '_') continue;
      c = name.charAt(i);
      prepped.append(c);
    }

    return prepped.toString();
  }


  @NotNull
  public static Collection<String> generateNames(@NotNull String name, boolean useUpperCase, boolean isArray) {
    name = prepareNameTextForSuggestions(name);

    Collection<String> candidates = new LinkedHashSet<String>();
    if (null != name && !name.isEmpty()) {
      candidates.addAll(NameUtil.getSuggestionsByName(name, "", "", useUpperCase, false, isArray));
    }
    return candidates;
  }

  @NotNull
  public static String getDefaultExpressionName(PsiElement expression, boolean useUpperCase) {
    String lower = getDefaultExpressionName(expression);
    return useUpperCase ? lower.toUpperCase() : lower;
  }

  @NotNull
  public static String getDefaultExpressionName(PsiElement expression) {
    ResultHolder typeResult = HaxeTypeResolver.getPsiElementType(expression, new HaxeGenericResolver());
    String byType = conventionalName(typeResult.getType());
    if (byType != null) return byType;

    // If result typing doesn't work (e.g. it's Unknown or Invalid), then try against
    // the kind of expression.

    if (expression instanceof HaxeAssignExpression
        || expression instanceof HaxeReferenceExpression) {
      return "var"; // Should come out of expression.getType() or a resolve(),
    }
    if (expression instanceof HaxePrefixExpression) {  // Wraps statements, like HaxeIfStatement
      return "expr";
    }
    if (expression instanceof HaxeSwitchCaseExpr
        || expression instanceof HaxeCallExpression
        || expression instanceof HaxeLogicAndExpression
        || expression instanceof HaxeLogicOrExpression
        || expression instanceof HaxeCompareExpression
        || expression instanceof HaxeTernaryExpression) {
      return "result";
    }
    if (expression instanceof HaxeBitwiseExpression
        || expression instanceof HaxeShiftExpression) {
      return "bitResult";
    }
    if (expression instanceof HaxeSuperExpression) {
      return "mysuper";
    }
    if (expression instanceof HaxeMapInitializerExpression
        || expression instanceof HaxeFunctionLiteral) {
      return "func";
    }
    if (expression instanceof HaxeStringLiteralExpression) {
      return "str";
    }
    if (expression instanceof HaxeThisExpression) {
      return "myself";
    }
    if (expression instanceof HaxeIteratorExpression) {
      return "iter";
    }
    if (expression instanceof HaxeAdditiveExpression
        || expression instanceof HaxeMultiplicativeExpression) {
      return "f"; // float
    }
    if (expression instanceof HaxeMapLiteral) {
      return "map";
    }
    if (expression instanceof HaxeArrayLiteral) {
      return "arr";
    }
    if (expression instanceof HaxeArrayAccessExpression) {
      return "element";
    }
    if (expression instanceof HaxeRegularExpression
        || expression instanceof HaxeRegularExpressionLiteral) {  // These must come before HaxeLiteralExpression
      return "regex";
    }
    if (expression instanceof HaxeLiteralExpression    // Must come after RegularExpressionXXX
        || expression instanceof HaxeConstantExpression) {
      return "const";
    }
    if (expression instanceof HaxeNewExpression) {
      return "newObj";
    }
    if (expression instanceof HaxeUnsafeCastExpression || expression instanceof HaxeSafeCastExpression) {
      return "cast";
    }
    if (expression instanceof HaxeObjectLiteral) {
      return "anon";  // Anonymous structure.  Maybe should be comprised of element names??
    }
    if (expression instanceof HaxeParenthesizedExpression) {
      return "result";  // Should be typed!
    }
    if (expression instanceof HaxePropertyAccessor) {
      return "prop";
    }
    if (expression instanceof HaxeTypeCheckExpr) {
      HaxeTypeCheckExpr expr = (HaxeTypeCheckExpr) expression;
      HaxeFunctionType functionType = expr.getFunctionType();
      if (null != functionType) {
        return functionType.getName();
      } else {
        HaxeTypeOrAnonymous toa = expr.getTypeOrAnonymous();
        if (null != toa) {
          HaxeType haxeType = toa.getType();
          if (null == haxeType && null != toa.getAnonymousType()) {
            return "anon";
          }
          if (null != haxeType) {
            String name = haxeType.getName();
            if (null != name) {
              return name;
            }
          }
        }
      }
    }

    return "x";
  }

  /** The conventional name for a value of a resolved type, or null when the type gives no lead. */
  @Nullable
  private static String conventionalName(@NotNull SpecificTypeReference type) {
    if (type.isDynamic()) return CONVENTIONAL_TYPE_NAMES.get("Dynamic");
    if (type.isVoid()) return CONVENTIONAL_TYPE_NAMES.get("Void");
    if (type.isInt()) return CONVENTIONAL_TYPE_NAMES.get("Int");
    if (type.isBool()) return CONVENTIONAL_TYPE_NAMES.get("Bool");
    if (type.isFloat()) return CONVENTIONAL_TYPE_NAMES.get("Float");
    if (type.isString()) return CONVENTIONAL_TYPE_NAMES.get("String");
    if (type.isArray()) return CONVENTIONAL_TYPE_NAMES.get("Array");
    if (type.isMapType()) return CONVENTIONAL_TYPE_NAMES.get("Map");
    if (type instanceof SpecificHaxeClassReference classReference) {
      HaxeClass clazz = classReference.getHaxeClass();
      String name = clazz == null ? null : clazz.getName();
      if (name != null) return HaxeStringUtil.toLowerFirst(name);
    }
    return null;
  }

  /**
   * Names for a value known only by its type, such as a lambda parameter:
   * the name its declaration carries, if any, then the conventional short
   * name of a standard type ({@code i} for Int, {@code func} for a function),
   * then the variants the platform derives from the type name. Each name is
   * made unique against the keywords, the names in use around
   * {@code context} and {@code alsoUsed}.
   */
  @NotNull
  public static List<String> getSuggestedNamesForType(@Nullable String declaredName,
                                                      @Nullable String typeName,
                                                      boolean isFunction,
                                                      @Nullable PsiElement context,
                                                      @NotNull Set<String> alsoUsed) {
    Collection<String> candidates = new LinkedHashSet<>();
    if (declaredName != null && !declaredName.isEmpty()) candidates.add(declaredName);
    if (isFunction) {
      candidates.add(FUNCTION_VALUE_NAME);
    } else if (typeName != null && !typeName.isEmpty()) {
      String conventional = CONVENTIONAL_TYPE_NAMES.get(typeName);
      if (conventional != null) candidates.add(conventional);
      candidates.addAll(generateNames(typeName, false, "Array".equals(typeName)));
    }
    if (candidates.isEmpty()) candidates.add(CONVENTIONAL_TYPE_NAMES.get("Dynamic"));

    Set<String> ignoreNameList = new HashSet<>(HaxeRefactoringUtil.collectKeywords());
    ignoreNameList.addAll(alsoUsed);
    if (context != null) ignoreNameList.addAll(HaxeRefactoringUtil.collectUsedNames(context));
    return uniqueAgainst(candidates, ignoreNameList);
  }

  @NotNull
  public static List<String> getSuggestedNames(final PsiElement expression, final boolean wantUpperCase) {
    return getSuggestedNames(expression, wantUpperCase, true, null);
  }
  public static List<String> getSuggestedNames(final PsiElement expression, final boolean wantUpperCase, boolean findUsed, Set<String> customUsedList) {
    String text = expression.getText();
    boolean useUpperCase = wantUpperCase;
    boolean isArray = HaxeExpressionUtil.isArrayExpression(expression);
    if (expression instanceof HaxeCallExpression) {
      final HaxeExpression callee = ((HaxeCallExpression)expression).getExpression();
      text = callee.getText();
    } else if (expression instanceof HaxeRegularExpression) {
      text = "REGEX_";
      useUpperCase = true;
    }

    Collection<String> candidates = text == null ? new LinkedHashSet<String>()
                                                 : HaxeNameSuggesterUtil.generateNames(text, useUpperCase, isArray);
    candidates.add(HaxeNameSuggesterUtil.getDefaultExpressionName(expression, useUpperCase));

    Set<String> ignoreNameList = new HashSet<>(HaxeRefactoringUtil.collectKeywords());
    if (customUsedList != null) {
      ignoreNameList.addAll(customUsedList);
    }
    if (findUsed) {
      ignoreNameList.addAll(HaxeRefactoringUtil.collectUsedNames(expression));
    }
    return uniqueAgainst(candidates, ignoreNameList);
  }

  /** Each candidate with the smallest numeric suffix that keeps it out of {@code ignore}. */
  @NotNull
  private static List<String> uniqueAgainst(@NotNull Collection<String> candidates, @NotNull Set<String> ignore) {
    final List<String> result = new ArrayList<>();
    for (String candidate : candidates) {
      int index = 0;
      String suffix = "";
      while (ignore.contains(candidate + suffix)) {
        suffix = Integer.toString(++index);
      }
      result.add(candidate + suffix);
    }
    return result;
  }
}
