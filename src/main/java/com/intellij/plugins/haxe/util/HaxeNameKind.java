package com.intellij.plugins.haxe.util;

import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.codeStyle.NameUtil;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** What a suggested name will name; it decides the casing of the suggestions. */
public enum HaxeNameKind {
  /** A class, interface, enum, abstract or typedef: UpperCamelCase. */
  TYPE,
  /** An enum constructor or enum-abstract value: UpperCamelCase. */
  ENUM_VALUE,
  /** lowerCamelCase. */
  METHOD,
  /** A local, parameter or field: lowerCamelCase. */
  VARIABLE,
  /** A static final or inline value: UPPER_SNAKE_CASE first, lowerCamelCase after, since Haxe code uses both. */
  CONSTANT;

  /**
   * The name and its shorter tails in this kind's casing, longest first:
   * {@code userAccountName} gives {@code userAccountName}, {@code accountName},
   * {@code name}, or {@code USER_ACCOUNT_NAME}, {@code ACCOUNT_NAME},
   * {@code NAME} for a constant.
   */
  @NotNull
  public List<String> variantsOf(@NotNull String rawName) {
    if (this == CONSTANT) return tailsOf(rawName, true);
    Set<String> variants = new LinkedHashSet<>();
    for (String tail : tailsOf(rawName, false)) {
      variants.add(this == TYPE || this == ENUM_VALUE ? StringUtil.capitalize(tail) : tail);
    }
    return new ArrayList<>(variants);
  }

  /** The other casing Haxe code uses for the kind: lowerCamelCase for a constant, nothing for the rest. */
  @NotNull
  public List<String> alternateVariantsOf(@NotNull String rawName) {
    return this == CONSTANT ? VARIABLE.variantsOf(rawName) : List.of();
  }

  /** The whole name in this kind's casings, without its tails: what a rename offers for a name against convention. */
  @NotNull
  public List<String> recased(@NotNull String name) {
    List<String> recased = new ArrayList<>();
    recased.add(variantsOf(name).getFirst());
    List<String> alternate = alternateVariantsOf(name);
    if (!alternate.isEmpty()) recased.add(alternate.getFirst());
    return recased;
  }

  /** The platform's word-tail variants, shortest first, reversed so the whole name leads. */
  @NotNull
  private static List<String> tailsOf(@NotNull String rawName, boolean upperSnake) {
    List<String> tails = new ArrayList<>(NameUtil.getSuggestionsByName(rawName, "", "", upperSnake, false, false));
    return tails.reversed();
  }
}
