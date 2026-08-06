package com.intellij.plugins.haxe.v2.toolwindow;

import com.intellij.plugins.haxe.config.NMETarget;
import com.intellij.plugins.haxe.config.OpenFLTarget;
import com.intellij.plugins.haxe.v2.toolwindow.tree.HaxeBuildFileType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.List;

/**
 * The selectable target platforms for XML/HXP-based build files (HXML declares its
 * target inside the file and is not selectable). Ids are the enum constant names of
 * {@link OpenFLTarget} / {@link NMETarget}.
 */
public final class HaxeTargetOptions {

  public record TargetChoice(@NotNull String id, @NotNull String displayName) {
  }

  private HaxeTargetOptions() {
  }

  public static boolean isTargetSelectable(@NotNull HaxeBuildFileType type) {
    // hxml declares its target in the file; a plain hxp script decides in code
    return type != HaxeBuildFileType.HXML && type != HaxeBuildFileType.HXP_SCRIPT;
  }

  @NotNull
  public static List<TargetChoice> choicesFor(@NotNull HaxeBuildFileType type) {
    if (type == HaxeBuildFileType.NMML) {
      return Arrays.stream(NMETarget.values())
        .map(target -> new TargetChoice(target.name(), target.toString()))
        .toList();
    }
    return Arrays.stream(OpenFLTarget.values())
      .map(target -> new TargetChoice(target.name(), target.toString()))
      .toList();
  }

  /** Display name for the stored id, falling back to the default target when unset or stale. */
  @NotNull
  public static String displayNameFor(@NotNull HaxeBuildFileType type, @Nullable String targetId) {
    List<TargetChoice> choices = choicesFor(type);
    return choices.stream()
      .filter(choice -> choice.id().equals(targetId))
      .findFirst()
      .orElseGet(() -> defaultChoice(type))
      .displayName();
  }

  /** The lime/openfl command-line flag for the stored target id, e.g. "html5" (falls back to the default target). */
  @NotNull
  public static String targetFlagFor(@NotNull HaxeBuildFileType type, @Nullable String targetId) {
    String resolvedId = choicesFor(type).stream()
      .map(TargetChoice::id)
      .filter(id -> id.equals(targetId))
      .findFirst()
      .orElseGet(() -> defaultChoice(type).id());
    return type == HaxeBuildFileType.NMML
           ? NMETarget.valueOf(resolvedId).getTargetFlag()
           : OpenFLTarget.valueOf(resolvedId).getTargetFlag();
  }

  @NotNull
  public static TargetChoice defaultChoice(@NotNull HaxeBuildFileType type) {
    String defaultId = type == HaxeBuildFileType.NMML ? NMETarget.HTML5.name() : OpenFLTarget.HTML5.name();
    return choicesFor(type).stream()
      .filter(choice -> choice.id().equals(defaultId))
      .findFirst()
      .orElse(choicesFor(type).get(0));
  }
}
