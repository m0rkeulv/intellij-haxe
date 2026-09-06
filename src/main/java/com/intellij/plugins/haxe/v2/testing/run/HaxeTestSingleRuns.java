package com.intellij.plugins.haxe.v2.testing.run;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.plugins.haxe.config.HaxeTarget;
import com.intellij.plugins.haxe.v2.buildsystem.*;
import com.intellij.plugins.haxe.v2.buildtools.*;
import com.intellij.plugins.haxe.v2.testing.HaxeTestFramework;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import lombok.CustomLog;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The compile behind a gutter-started single-suite/-test run: the tests
 * build's SELECTED section with its entry point swapped for a generated
 * template main — the original {@code --main}/{@code -x} stripped, the
 * target's output redirected into a per-run directory under a short temp
 * root (the real tests artifact must not be overwritten; see
 * {@link #generatedDirectory} for why short), and the generated
 * main's classpath appended. A lime-family build instead compiles through
 * its tool with the generated main overriding the app's (see
 * {@code HaxeTestLaunchPlanner}); only {@link #generatedDirectory} serves
 * it. Templates live under
 * {@code resources/testFrameworks/<framework>/}; the suite list is
 * substituted at generation time as {@code ${NEW_SUITES}} ({@code new A(),
 * new B()}) or {@code ${ADD_SUITES}} ({@code add(A); add(B);}), a
 * single-class template as {@code ${TEST_CLASS}} / {@code ${TEST_METHOD}}.
 */
@CustomLog
final class HaxeTestSingleRuns {

  /** The selection a run narrows to: one or more suite classes, or one test method of a single suite. */
  record SingleRun(@NotNull List<String> testClasses, @Nullable String testMethod) {
    boolean singleTest() {
      return testMethod != null;
    }

    @NotNull
    String firstClass() {
      return testClasses.get(0);
    }
  }

  /** The generated entry point's class name (hxcpp names its binary after it). */
  static final String MAIN_CLASS = "IjSingleRun";

  private HaxeTestSingleRuns() {
  }

  /**
   * The single-run compile, built on the container's resolved build command:
   * the build file argument is replaced by the selected section's arguments with
   * the entry point swapped (see class doc), keeping the resolved haxe
   * executable, work directory and compilation-server eligibility. Null when
   * the command, section, template or generation directory cannot be
   * resolved. Call in a read action.
   */
  @Nullable
  static HaxeCompileCommands.Resolved resolveCompile(@NotNull Project project,
                                                     @NotNull VirtualFile buildFile,
                                                     @NotNull HaxeTestFramework framework,
                                                     @NotNull String extraArguments,
                                                     @NotNull SingleRun singleRun) {
    HaxeCompileCommands.Resolved resolved = HaxeCompileCommands.resolveAction(
      project, buildFile.getPath(), defaultBuildAction(), extraArguments);
    if (resolved == null) return null;

    int fileArgumentIndex = resolved.command().indexOf(HaxeBuildWorkDirectories.fileArgument(project, buildFile));
    if (fileArgumentIndex < 0) return null;

    String section = HaxeBuildSections.selectedSectionContent(
      project, new HaxeBuildFile(buildFile, HaxeBuildFileType.HXML));
    if (section == null) return null;

    Path generated = generatedDirectory(buildFile.getPath(), framework, singleRun);
    if (generated == null) return null;

    List<String> sectionArguments = swapEntryPoint(
      HxmlArguments.parseLines(section.lines().toList()), generated);
    List<String> command = new ArrayList<>(resolved.command().subList(0, fileArgumentIndex));
    command.addAll(sectionArguments);
    command.addAll(resolved.command().subList(fileArgumentIndex + 1, resolved.command().size()));
    command.add("-cp");
    command.add(generated.toString());
    command.add("--main");
    command.add(MAIN_CLASS);
    return new HaxeCompileCommands.Resolved(
      resolved.containerId(), command, resolved.workDirectory(), resolved.presentable(), resolved.connectEligible());
  }

  /**
   * The artifact the redirected target flag writes, resolved the same way
   * {@link #swapEntryPoint} redirects it; null for target-less/interp
   * sections (the compile IS the run) or when generation state is missing.
   */
  @Nullable
  static Path artifact(@NotNull VirtualFile buildFile,
                       @NotNull HaxeTestFramework framework,
                       @NotNull SingleRun singleRun,
                       @NotNull HaxeTarget target) {
    Path generated = generatedDirectory(buildFile.getPath(), framework, singleRun);
    return generated == null ? null : outputFor(generated, target);
  }

  // Strips the section's entry point (--main/-x) and redirects its target
  // flag's output into the generated directory; everything else (classpaths,
  // defines, libs) passes through untouched.
  @NotNull
  private static List<String> swapEntryPoint(@NotNull List<String> sectionArguments, @NotNull Path generated) {
    List<String> swapped = new ArrayList<>(sectionArguments.size());
    for (int i = 0; i < sectionArguments.size(); i++) {
      String argument = sectionArguments.get(i);
      boolean hasValue = i + 1 < sectionArguments.size();
      if (HxmlFileParser.isMainFlag(argument) && hasValue) {
        i++;
        continue;
      }
      // -x <Main> is main + interp + run in one flag; the swapped build keeps
      // only the interp part
      if (argument.equals("-x") && hasValue) {
        i++;
        swapped.add("--interp");
        continue;
      }
      HaxeTarget target = HxmlFileParser.targetForFlag(argument);
      if (target != null && target != HaxeTarget.INTERP && hasValue) {
        swapped.add(argument);
        swapped.add(outputFor(generated, target).toString());
        i++;
        continue;
      }
      swapped.add(argument);
    }
    return swapped;
  }

  /** The redirected output path per target kind (directory-producing targets get a subdirectory). */
  @NotNull
  private static Path outputFor(@NotNull Path generated, @NotNull HaxeTarget target) {
    Path out = generated.resolve("out");
    return switch (target) {
      case HL -> out.resolve("single.hl");
      case NEKO -> out.resolve("single.n");
      case JAVA -> out.resolve("single.jar");
      case JAVA_SCRIPT -> out.resolve("single.js");
      case FLASH -> out.resolve("single.swf");
      default -> out; // hxcpp and other directory-shaped outputs
    };
  }

  /**
   * The directory holding the generated {@code IjSingleRun.hx} (and the
   * redirected {@code out/}), keyed by a content hash of the substituted
   * source plus the build file path — a changed template, selection or
   * build lands in a fresh directory, an unchanged one is reused. Null when
   * the template is missing or the write fails.
   */
  @Nullable
  static Path generatedDirectory(@NotNull String buildFilePath,
                                         @NotNull HaxeTestFramework framework,
                                         @NotNull SingleRun singleRun) {
    String templateName = framework.singleRunTemplate(singleRun.singleTest());
    if (templateName == null) return null;
    String source = substitutedTemplate(framework, templateName, singleRun);
    if (source == null) return null;
    try {
      String contentHash = HaxeSystemPaths.shortHash(buildFilePath.getBytes(StandardCharsets.UTF_8),
                                                     source.getBytes(StandardCharsets.UTF_8));

      // the system TEMP dir, not the IDE system dir: hxcpp nests deep type
      // paths under out/ (src/... plus obj/<toolchain>/..., with generic
      // instantiation names running 30+ characters) and its msvc toolchain
      // still lives with MAX_PATH - the IDE system dir alone can eat 150+
      // characters and haxe then silently fails to write the longest files
      Path root = Path.of(System.getProperty("java.io.tmpdir"), "haxe-single-run-" + contentHash);
      Path main = root.resolve(MAIN_CLASS + ".hx");
      if (!Files.exists(main)) {
        Files.createDirectories(root);
        Files.writeString(main, source);
      }
      return root;
    } catch (IOException e) {
      log.warn("cannot generate the single-run main: " + e.getMessage());
      return null;
    }
  }

  @Nullable
  private static String substitutedTemplate(@NotNull HaxeTestFramework framework,
                                            @NotNull String templateName,
                                            @NotNull SingleRun singleRun) {
    String resource = "/testFrameworks/" + framework.libraryName() + "/" + templateName;
    try (InputStream stream = HaxeTestSingleRuns.class.getResourceAsStream(resource)) {
      if (stream == null) {
        log.warn("single-run template missing: " + resource);
        return null;
      }
      String template = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
      String newSuites = singleRun.testClasses().stream().map(suite -> "new " + suite + "()").collect(Collectors.joining(", "));
      String addSuites = singleRun.testClasses().stream().map(suite -> "add(" + suite + ");").collect(Collectors.joining("\n\t\t"));
      String substituted = template
        .replace("${NEW_SUITES}", newSuites)
        .replace("${ADD_SUITES}", addSuites)
        .replace("${TEST_CLASS}", singleRun.firstClass());
      if (singleRun.testMethod() != null) {
        substituted = substituted.replace("${TEST_METHOD}", singleRun.testMethod());
      }
      return substituted;
    } catch (IOException e) {
      log.warn("cannot read single-run template " + resource + ": " + e.getMessage());
      return null;
    }
  }

  @NotNull
  private static String defaultBuildAction() {
    return HaxeBuildFileActions.defaultBuildActionName(HaxeBuildFileType.HXML);
  }
}
