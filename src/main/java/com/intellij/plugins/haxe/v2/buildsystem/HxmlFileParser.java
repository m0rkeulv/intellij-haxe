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
 * merged first through {@link #flatten}; a {@code --next} chain splits into
 * isolated per-compilation blocks through {@link #sections}, each parsed on its own.
 */
public final class HxmlFileParser {

  /** Reads an included hxml's content by the reference spelled in the file, or null when unreadable. */
  @FunctionalInterface
  public interface IncludeReader {
    @Nullable
    String read(@NotNull String path);
  }

  // written above every expanded reference so a section keeps the name of the
  // file it came from (--next chains are typically one include per section)
  private static final String INCLUDE_MARKER = "# include ";

  /**
   * The section separators. A trailing token on their line is ONE argument of
   * the FOLLOWING content — an hxml reference or a dot path; the compiler
   * takes the whole rest as a single argument and rejects flags there
   * (verified live: {@code --next -js out.js} fails as "unknown option").
   */
  private static final Set<String> SECTION_FLAGS = Set.of("--next", "--each");

  /**
   * The build's EFFECTIVE hxml: every referenced-hxml line replaced by that
   * file's content, recursively. haxe resolves a reference against the
   * invocation's working directory, NOT the file that declares it (a nested
   * include in a subfolder still reads next to the root — verified against a
   * live compile), so one reader rooted at the build file's directory serves
   * every nesting level. A revisited reference is dropped: haxe would loop on
   * such input. Unreadable references stay as lines; {@link #parse} skips them.
   * Each expansion is preceded by a comment naming the included file (see
   * {@link #leadingIncludedFile}), and a reference trailing a separator
   * ({@code --next other.hxml} inside an included file) is split off so it
   * still expands.
   */
  @NotNull
  public static String flatten(@NotNull String content, @NotNull IncludeReader reader) {
    StringBuilder flat = new StringBuilder();
    flattenInto(content, reader, new HashSet<>(), flat);
    return flat.toString();
  }

  private static void flattenInto(@NotNull String content,
                                  @NotNull IncludeReader reader,
                                  @NotNull Set<String> visited,
                                  @NotNull StringBuilder out) {
    for (String rawLine : content.lines().toList()) {
      appendFlattened(rawLine, reader, visited, out);
    }
  }

  private static void appendFlattened(@NotNull String rawLine,
                                      @NotNull IncludeReader reader,
                                      @NotNull Set<String> visited,
                                      @NotNull StringBuilder out) {
    String line = rawLine.trim();
    String[] tokens = line.split("\\s+", 2);
    if (SECTION_FLAGS.contains(tokens[0]) && tokens.length > 1) {
      out.append(tokens[0]).append('\n');
      appendFlattened(tokens[1], reader, visited, out);
      return;
    }
    if (isIncludeReference(line)) {
      if (!visited.add(line)) return;
      String included = reader.read(line);
      if (included != null) {
        out.append(INCLUDE_MARKER).append(line).append('\n');
        flattenInto(included, reader, visited, out);
        return;
      }
    }
    out.append(rawLine).append('\n');
  }

  /**
   * The hxml file this section's LEADING content was expanded from by
   * {@link #flatten}, or null when the section starts with inline lines.
   * The FIRST marker wins: it is the chain's own entry, and any marker
   * stacked after it is a nested include — that entry's implementation detail.
   */
  @Nullable
  public static String leadingIncludedFile(@NotNull String sectionContent) {
    for (String rawLine : sectionContent.lines().toList()) {
      String line = rawLine.trim();
      if (line.startsWith(INCLUDE_MARKER)) {
        String reference = line.substring(INCLUDE_MARKER.length()).trim();
        if (isIncludeReference(reference)) return reference;
        continue;
      }
      if (isSignificantLine(line)) break;
    }
    return null;
  }

  private static final Map<String, HaxeTarget> TARGET_FLAGS = buildTargetFlagMap();

  /** The define flag's two spellings; the flag's value is {@code name} or {@code name=value}. */
  public static final Set<String> DEFINE_FLAGS = Set.of("-D", "--define");

  private static final Set<String> LIBRARY_FLAGS = Set.of("-lib", "--library", "-L");
  private static final Set<String> CLASSPATH_FLAGS = Set.of("-cp", "-p", "--class-path");

  private HxmlFileParser() {
  }

  /** A line the compiler acts on - not blank, not a comment (which covers the {@code # include} markers). */
  private static boolean isSignificantLine(@NotNull String rawLine) {
    String line = rawLine.trim();
    return !line.isEmpty() && !line.startsWith("#");
  }

  /**
   * A stable identity per section content (see
   * {@code HaxeBuildFileInspector.sectionContents}): the file the section's
   * leading content came from (the root file's name for inline sections),
   * with {@code #n} appended from the second occurrence on
   * ({@code compile-cs.hxml}, {@code compile-cs.hxml#2}). Selections stored by
   * identity survive chain edits; a removed section simply stops matching.
   */
  @NotNull
  public static List<String> sectionIds(@NotNull String rootFileName, @NotNull List<String> sections) {
    List<String> ids = new ArrayList<>();
    Map<String, Integer> occurrences = new HashMap<>();
    for (String section : sections) {
      String leading = leadingIncludedFile(section);
      String name = leading != null ? leading : rootFileName;
      int occurrence = occurrences.merge(name, 1, Integer::sum);
      ids.add(occurrence == 1 ? name : name + "#" + occurrence);
    }
    return ids;
  }

  /** Parses effective (include-merged) hxml content — see {@link #flatten}. */
  @NotNull
  public static HaxeBuildFileInfo parse(@NotNull String content) {
    ParseAccumulator accumulator = new ParseAccumulator();
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
    }
    return accumulator.toInfo();
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

  private static final Set<String> DEBUG_FLAGS = Set.of("-debug", "--debug");

  /** Whether effective (include-merged) content declares a debug compile — hxcpp renames its binary on it. */
  public static boolean hasDebugFlag(@NotNull String content) {
    return content.lines()
      .map(String::trim)
      .anyMatch(DEBUG_FLAGS::contains);
  }

  private static final Set<String> MAIN_FLAGS = Set.of("-main", "--main", "-m");

  /** Whether the flag declares the build's main class ({@code -main}/{@code --main}/{@code -m}). */
  public static boolean isMainFlag(@NotNull String flag) {
    return MAIN_FLAGS.contains(flag);
  }

  /**
   * The build's {@code --main} class from effective (include-merged) content
   * (first declaration wins), or null when none is declared. Hxcpp names the
   * produced executable after this class's simple name, which is why it is
   * read separately from {@link #parse} - only artifact launching needs it.
   */
  @Nullable
  public static String mainClass(@NotNull String content) {
    for (String rawLine : content.lines().toList()) {
      String line = rawLine.trim();
      if (line.isEmpty() || line.startsWith("#")) continue;

      String[] tokens = line.split("\\s+", 2);
      String flag = tokens[0];
      String value = tokens.length > 1 ? tokens[1].trim() : null;
      if (MAIN_FLAGS.contains(flag) && value != null) {
        return value;
      }
    }
    return null;
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
