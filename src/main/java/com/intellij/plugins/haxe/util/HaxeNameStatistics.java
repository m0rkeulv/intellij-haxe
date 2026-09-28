package com.intellij.plugins.haxe.util;

import com.intellij.psi.statistics.StatisticsInfo;
import com.intellij.psi.statistics.StatisticsManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The names chosen before for the same kind of value, kept in the platform's
 * statistics store. A value is described by what it names, the property its
 * initializer is about and its type; a choice counts under the full
 * description and under the property and the type alone, so a name picked
 * for one {@code Sprite} field reaches the next {@code Sprite} whatever its
 * initializer.
 */
public final class HaxeNameStatistics {
  private static final String CONTEXT_PREFIX = "haxeName#";
  /** A name chosen this often, or half as often as the most chosen one, is offered even when nothing else suggests it. */
  private static final int FREQUENT_USE_FLOOR = 3;

  private HaxeNameStatistics() {
  }

  /** Counts the choice under the full description and under each of its parts. */
  public static void recordChosen(@NotNull HaxeNameKind kind, @Nullable String propertyName, @Nullable String typeText, @NotNull String name) {
    if (propertyName == null && typeText == null) return;
    StatisticsManager statistics = StatisticsManager.getInstance();
    statistics.incUseCount(info(kind, propertyName, typeText, name));
    if (propertyName != null && typeText != null) {
      statistics.incUseCount(info(kind, propertyName, null, name));
      statistics.incUseCount(info(kind, null, typeText, name));
    }
  }

  /** Names chosen often enough for this description that are not among {@code names} yet. */
  @NotNull
  public static List<String> frequentlyChosen(@NotNull HaxeNameKind kind,
                                              @Nullable String propertyName,
                                              @Nullable String typeText,
                                              @NotNull List<String> names) {
    List<String> frequent = new ArrayList<>();
    for (String context : contexts(kind, propertyName, typeText)) {
      StatisticsInfo[] chosen = StatisticsManager.getInstance().getAllValues(context);
      int mostChosen = 0;
      for (StatisticsInfo info : chosen) mostChosen = Math.max(mostChosen, useCount(info));
      int floor = Math.max(FREQUENT_USE_FLOOR, mostChosen / 2);
      for (StatisticsInfo info : chosen) {
        String name = info.getValue();
        if (useCount(info) >= floor && !names.contains(name) && !frequent.contains(name)) frequent.add(name);
      }
    }
    return frequent;
  }

  /** The names most chosen for this description first; names never chosen keep their order. */
  @NotNull
  public static List<String> mostChosenFirst(@NotNull HaxeNameKind kind,
                                             @Nullable String propertyName,
                                             @Nullable String typeText,
                                             @NotNull List<String> names) {
    List<String> sorted = new ArrayList<>(names);
    Comparator<String> byUse = Comparator.comparingInt(name -> useCount(kind, propertyName, typeText, name));
    sorted.sort(byUse.reversed());
    return sorted;
  }

  private static int useCount(@NotNull HaxeNameKind kind, @Nullable String propertyName, @Nullable String typeText, @NotNull String name) {
    int count = 0;
    for (String context : contexts(kind, propertyName, typeText)) {
      count += useCount(new StatisticsInfo(context, name));
    }
    return count;
  }

  private static int useCount(@NotNull StatisticsInfo info) {
    return StatisticsManager.getInstance().getUseCount(info);
  }

  /** The full description first, then the property alone and the type alone. */
  @NotNull
  private static List<String> contexts(@NotNull HaxeNameKind kind, @Nullable String propertyName, @Nullable String typeText) {
    List<String> contexts = new ArrayList<>();
    if (propertyName != null || typeText != null) contexts.add(context(kind, propertyName, typeText));
    if (propertyName != null && typeText != null) {
      contexts.add(context(kind, propertyName, null));
      contexts.add(context(kind, null, typeText));
    }
    return contexts;
  }

  @NotNull
  private static StatisticsInfo info(@NotNull HaxeNameKind kind, @Nullable String propertyName, @Nullable String typeText, @NotNull String name) {
    return new StatisticsInfo(context(kind, propertyName, typeText), name);
  }

  @NotNull
  private static String context(@NotNull HaxeNameKind kind, @Nullable String propertyName, @Nullable String typeText) {
    return CONTEXT_PREFIX + kind + "#" + (propertyName == null ? "" : propertyName) + "#" + (typeText == null ? "" : typeText);
  }
}
