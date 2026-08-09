package com.intellij.plugins.haxe.v2.display;

import com.intellij.openapi.project.Project;
import com.intellij.plugins.haxe.v2.toolwindow.HaxeEnvironmentStore;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The pipeline turning a build context into the argument list a display
 * request sends: COLLECT the build's arguments (one-level hxml expansion when
 * needed), APPLY the container's IDE define overrides, then the transport
 * sends the result over the server socket. Without the overrides the server's
 * view of which {@code #if} branches are alive diverges from the editor's
 * define context — diagnostics and completion would follow the raw build
 * file instead of what the user configured.
 */
public final class HaxeDisplayConfiguration {

  /** A container's define overrides in argument form. */
  public record DefineOverrides(@NotNull Set<String> removedNames, @NotNull List<String> setArgs) {

    public static final DefineOverrides EMPTY = new DefineOverrides(Set.of(), List.of());

    public boolean isEmpty() {
      return removedNames.isEmpty() && setArgs.isEmpty();
    }

    /** Cache-key material — contexts with different overrides are different server contexts. */
    @NotNull
    public String signature() {
      return isEmpty() ? "" : "-" + String.join(",", removedNames) + "+" + String.join(",", setArgs);
    }
  }

  private HaxeDisplayConfiguration() {
  }

  /** The container's overrides as arguments: SETs become {@code -D name[=value]}, REMOVEs name the defines to strip. */
  @NotNull
  public static DefineOverrides overridesFor(@NotNull Project project, @NotNull String containerId) {
    Set<String> removed = new LinkedHashSet<>();
    List<String> set = new ArrayList<>();
    for (HaxeEnvironmentStore.EnvironmentDefine override : HaxeEnvironmentStore.getInstance(project).getDefines(containerId)) {
      if (override.effect() == HaxeEnvironmentStore.DefineEffect.REMOVE) {
        removed.add(override.name());
      }
      else {
        set.add("-D");
        set.add(override.value().isEmpty() ? override.name() : override.name() + "=" + override.value());
      }
    }
    return removed.isEmpty() && set.isEmpty() ? DefineOverrides.EMPTY : new DefineOverrides(removed, set);
  }

  /**
   * Applies the overrides to the build's base arguments. SETs simply append —
   * a later {@code -D} wins over an earlier value of the same define. A
   * REMOVE has no CLI form, so it forces one-level hxml expansion (an hxml
   * reference hides the {@code -D} lines to strip) and drops the matching
   * define pairs.
   */
  @NotNull
  public static List<String> applyOverrides(@NotNull List<String> baseArgs, @NotNull DefineOverrides overrides) {
    if (overrides.isEmpty()) return baseArgs;
    List<String> args = overrides.removedNames().isEmpty()
                        ? new ArrayList<>(baseArgs)
                        : withoutRemovedDefines(HaxeGeneratedDumpService.expandHxmlReferences(baseArgs),
                                                overrides.removedNames());
    args.addAll(overrides.setArgs());
    return args;
  }

  @NotNull
  private static List<String> withoutRemovedDefines(@NotNull List<String> args, @NotNull Set<String> removedNames) {
    List<String> kept = new ArrayList<>(args.size());
    for (int i = 0; i < args.size(); i++) {
      String arg = args.get(i);
      boolean defineFlag = arg.equals("-D") || arg.equals("--define");
      if (defineFlag && i + 1 < args.size()) {
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
