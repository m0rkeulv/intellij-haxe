package com.intellij.plugins.haxe.v2.display;

import com.intellij.plugins.haxe.v2.buildtools.settings.EnvironmentDefine;
import com.intellij.plugins.haxe.v2.buildtools.settings.DefineEffect;
import com.intellij.openapi.project.Project;
import com.intellij.plugins.haxe.v2.buildsystem.HxmlArguments;
import com.intellij.plugins.haxe.v2.buildsystem.HxmlFileParser;
import com.intellij.plugins.haxe.v2.buildtools.settings.HaxeEnvironmentStore;
import com.intellij.plugins.haxe.v2.compiler.HaxeLanguageLevel;
import com.intellij.plugins.haxe.v2.compiler.HaxeLanguageLevelUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Applies the container's environment overrides (its Define overrides and its
 * Custom target) to the build's arguments before a display request sends
 * them. Without the overrides, the server's view of which {@code #if}
 * branches are active and which target-specific modules ({@code Module.go.hx})
 * load differs from the editor's define context, and diagnostics and
 * completion would follow the raw build file instead of what the user
 * configured.
 */
public final class HaxeDisplayConfiguration {

  /**
   * A container's environment overrides in argument form: the names of the
   * defines to remove, the {@code -D} arguments of the defines to set, and the
   * custom target to compile for, null when none is forwarded.
   */
  public record ContainerOverrides(@NotNull Set<String> removedNames,
                                   @NotNull List<String> defineArgs,
                                   @Nullable String customTarget) {

    public static final ContainerOverrides EMPTY = new ContainerOverrides(Set.of(), List.of(), null);

    public boolean isEmpty() {
      return removedNames.isEmpty() && defineArgs.isEmpty() && customTarget == null;
    }

    /** Part of the context's cache key, since different overrides make different server contexts. */
    @NotNull
    public String signature() {
      if (isEmpty()) return "";
      String target = customTarget == null ? "" : "@" + customTarget;
      return "-" + String.join(",", removedNames) + "+" + String.join(",", defineArgs) + target;
    }
  }

  private HaxeDisplayConfiguration() {
  }

  /// The container's overrides as arguments: a SET becomes `-D name[=value]`,
  /// a REMOVE lists the define to strip, and the Custom target is forwarded
  /// as described at [#customTargetToForward].
  @NotNull
  public static ContainerOverrides overridesFor(@NotNull Project project,
                                                @NotNull String containerId,
                                                boolean buildDeclaresTarget) {
    Set<String> removed = new LinkedHashSet<>();
    List<String> defineArgs = new ArrayList<>();
    for (EnvironmentDefine override : HaxeEnvironmentStore.getInstance(project).getDefines(containerId)) {
      if (override.effect() == DefineEffect.REMOVE) {
        removed.add(override.name());
      }
      else {
        defineArgs.add("-D");
        defineArgs.add(override.value().isEmpty() ? override.name() : override.name() + "=" + override.value());
      }
    }
    String customTarget = HaxeEnvironmentStore.getInstance(project).getCustomTarget(containerId);
    HaxeLanguageLevel compiler = HaxeLanguageLevelUtil.fromCompiler(project, containerId);
    String forwardedTarget = customTargetToForward(customTarget, compiler, buildDeclaresTarget);
    if (removed.isEmpty() && defineArgs.isEmpty() && forwardedTarget == null) return ContainerOverrides.EMPTY;
    return new ContainerOverrides(removed, defineArgs, forwardedTarget);
  }

  /**
   * The custom target to add to the build's arguments: the container's Custom
   * target, only when the build selects no target of its own (a second one
   * fails the compile) and only to a Haxe 5 compiler, the first with
   * {@code --custom-target}. Without it, a build such as a library's
   * {@code extraParams.hxml} compiles for the default platform, and the
   * target's {@code Module.<target>.hx} files are not part of it.
   */
  @Nullable
  static String customTargetToForward(@Nullable String customTarget,
                                      @Nullable HaxeLanguageLevel compiler,
                                      boolean buildDeclaresTarget) {
    if (customTarget == null || buildDeclaresTarget) return null;
    return compiler != null && compiler.isAtLeast(HaxeLanguageLevel.HAXE_5_0) ? customTarget : null;
  }

  /// Applies the overrides to the build's base arguments. SETs are simply
  /// appended, because a later `-D` wins over an earlier value of the same
  /// define. A REMOVE has no command-line form. The hxml references are
  /// therefore expanded one level, since a reference can hide the `-D` lines
  /// to strip, and the matching define pairs are dropped. A forwarded custom
  /// target comes last.
  @NotNull
  public static List<String> applyOverrides(@NotNull List<String> baseArgs, @NotNull ContainerOverrides overrides) {
    if (overrides.isEmpty()) return baseArgs;
    List<String> args = baseWithRemovals(baseArgs, overrides);
    args.addAll(overrides.defineArgs());
    if (overrides.customTarget() != null) {
      args.addAll(List.of(HxmlFileParser.CUSTOM_TARGET_FLAG, overrides.customTarget()));
    }
    return args;
  }

  @NotNull
  private static List<String> baseWithRemovals(List<String> baseArgs, ContainerOverrides overrides) {
    if (overrides.removedNames().isEmpty()) return new ArrayList<>(baseArgs);
    return withoutRemovedDefines(HxmlArguments.expandReferences(baseArgs), overrides.removedNames());
  }

  private static List<String> withoutRemovedDefines(@NotNull List<String> args, @NotNull Set<String> removedNames) {
    List<String> kept = new ArrayList<>(args.size());
    for (int i = 0; i < args.size(); i++) {
      String arg = args.get(i);
      boolean isDefineFlag = HxmlFileParser.DEFINE_FLAGS.contains(arg);
      if (isDefineFlag && i + 1 < args.size()) {
        String value = args.get(i + 1);
        // the define's name is everything before the optional =value
        int equals = value.indexOf('=');
        String name = equals >= 0 ? value.substring(0, equals) : value;
        if (removedNames.contains(name)) {
          i++;
          continue;
        }
      }
      kept.add(arg);
    }
    return kept;
  }
}
