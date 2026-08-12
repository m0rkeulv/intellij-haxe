package com.intellij.plugins.haxe.v2.buildsystem;

import com.intellij.plugins.haxe.config.HaxeTarget;
import com.intellij.plugins.haxe.v2.buildsystem.HaxeBuildFileInfo.HaxeDefine;
import com.intellij.plugins.haxe.v2.buildsystem.HaxeBuildFileInfo.HaxeLibDependency;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Line-based parser for hxml files, extracting the compilation target, defines and
 * haxelib dependencies. Referenced hxml files (a bare {@code common.hxml} line) are
 * followed through {@link IncludeResolver}; {@code --next} sections are aggregated
 * rather than treated as separate builds.
 */
public final class HxmlFileParser {

  /** Resolves an included hxml reference to its content, or null when it cannot be read. */
  @FunctionalInterface
  public interface IncludeResolver {
    @Nullable
    String resolveContent(@NotNull String path);
  }

  private static final Map<String, HaxeTarget> TARGET_FLAGS = buildTargetFlagMap();

  /** The define flag's two spellings; the flag's value is {@code name} or {@code name=value}. */
  public static final Set<String> DEFINE_FLAGS = Set.of("-D", "--define");

  private static final Set<String> LIBRARY_FLAGS = Set.of("-lib", "--library", "-L");
  private static final Set<String> CLASSPATH_FLAGS = Set.of("-cp", "-p", "--class-path");

  private HxmlFileParser() {
  }

  @NotNull
  public static HaxeBuildFileInfo parse(@NotNull String content, @NotNull IncludeResolver includeResolver) {
    ParseAccumulator accumulator = new ParseAccumulator();
    parseInto(content, includeResolver, accumulator);
    return accumulator.toInfo();
  }

  private static void parseInto(@NotNull String content,
                                @NotNull IncludeResolver includeResolver,
                                @NotNull ParseAccumulator accumulator) {
    for (String rawLine : content.lines().toList()) {
      String line = rawLine.trim();
      if (line.isEmpty() || line.startsWith("#")) continue;

      String[] tokens = line.split("\\s+", 2);
      String flag = tokens[0];
      String value = tokens.length > 1 ? tokens[1].trim() : null;

      if (DEFINE_FLAGS.contains(flag) && value != null) {
        accumulator.defines.add(parseDefine(value));
      }
      else if (LIBRARY_FLAGS.contains(flag) && value != null) {
        accumulator.libraries.add(parseLibrary(value));
      }
      else if (CLASSPATH_FLAGS.contains(flag) && value != null) {
        accumulator.classpaths.add(value);
      }
      else if (TARGET_FLAGS.containsKey(flag)) {
        if (accumulator.target == null) {
          accumulator.target = TARGET_FLAGS.get(flag);
          accumulator.targetOutput = value;
        }
      }
      else if (isIncludeReference(flag) && accumulator.visitedIncludes.add(flag)) {
        String included = includeResolver.resolveContent(flag);
        if (included != null) {
          parseInto(included, includeResolver, accumulator);
        }
      }
    }
  }

  // "name" or "name=value"
  @NotNull
  private static HaxeDefine parseDefine(@NotNull String value) {
    String[] parts = value.split("=", 2);
    return new HaxeDefine(parts[0], parts.length > 1 ? parts[1] : null);
  }

  // "name", "name:1.2.0" or "name:git:https://..."
  @NotNull
  private static HaxeLibDependency parseLibrary(@NotNull String value) {
    String[] parts = value.split(":", 2);
    return new HaxeLibDependency(parts[0], parts.length > 1 ? parts[1] : null);
  }

  private static boolean isIncludeReference(@NotNull String token) {
    return !token.startsWith("-") && token.endsWith(".hxml");
  }

  /** The target a compiler flag selects ({@code -js}, {@code --jvm}, ...), or null for non-target flags. */
  @Nullable
  public static HaxeTarget targetForFlag(@NotNull String flag) {
    return TARGET_FLAGS.get(flag);
  }

  private static Map<String, HaxeTarget> buildTargetFlagMap() {
    Map<String, HaxeTarget> map = new HashMap<>();
    for (HaxeTarget target : HaxeTarget.values()) {
      // INTERP's flag is "-interp", so both spellings below already yield "--interp".
      map.put("-" + target.getFlag(), target);
      map.put("--" + target.getFlag(), target);
    }
    // aliases the enum does not carry
    map.put("-jvm", HaxeTarget.JAVA);
    map.put("--jvm", HaxeTarget.JAVA);
    map.put("-as3", HaxeTarget.FLASH);

    return Map.copyOf(map);
  }

  /** One parse run's mutable state, shared across included files (first target flag wins). */
  private static final class ParseAccumulator {
    final Set<String> visitedIncludes = new HashSet<>();
    final List<HaxeDefine> defines = new ArrayList<>();
    final List<HaxeLibDependency> libraries = new ArrayList<>();
    final List<String> classpaths = new ArrayList<>();

    @Nullable HaxeTarget target;
    @Nullable String targetOutput;

    @NotNull
    HaxeBuildFileInfo toInfo() {
      return new HaxeBuildFileInfo(target, targetOutput,
                                   List.copyOf(defines),
                                   List.copyOf(libraries),
                                   List.copyOf(classpaths));
    }
  }
}
