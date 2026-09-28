package com.intellij.plugins.haxe.util;

import com.intellij.psi.codeStyle.SuggestedNameInfo;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Suggested names, best first, with what they describe: the kind of value,
 * the property the initializer is about and the type, which is what a
 * chosen name is remembered under.
 */
public record HaxeSuggestedNames(@NotNull List<String> names,
                                 @NotNull HaxeNameKind kind,
                                 @Nullable String propertyName,
                                 @Nullable String typeText) {

  public static final HaxeSuggestedNames NONE = new HaxeSuggestedNames(List.of(), HaxeNameKind.VARIABLE, null, null);

  public boolean isEmpty() {
    return names.isEmpty();
  }

  @NotNull
  public String first() {
    return names.getFirst();
  }

  /** Remembers the choice so it ranks first the next time the same kind of value is named. */
  public void recordChosen(@NotNull String name) {
    HaxeNameStatistics.recordChosen(kind, propertyName, typeText, name);
  }

  /** The names as the platform's rename and introduce flows take them; they report the choice back. */
  @NotNull
  public SuggestedNameInfo asInfo() {
    return new SuggestedNameInfo(names.toArray(String[]::new)) {
      @Override
      public void nameChosen(String name) {
        recordChosen(name);
      }
    };
  }
}
