package com.intellij.plugins.haxe.v2.testing.run;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.plugins.haxe.config.HaxeTarget;
import com.intellij.plugins.haxe.v2.buildsystem.*;
import com.intellij.plugins.haxe.v2.buildtools.*;
import com.intellij.plugins.haxe.v2.buildtools.settings.HaxeEnvironmentStore;
import com.intellij.plugins.haxe.v2.testing.HaxeTestFramework;
import com.intellij.util.execution.ParametersListUtil;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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
 * main's classpath appended. Templates live under
 * {@code resources/testFrameworks/<framework>/}; {@code ${TEST_CLASS}} /
 * {@code ${TEST_METHOD}} are substituted at generation time.
 */
@CustomLog
final class HaxeTestSingleRuns {

  /** The gutter selection a run narrows to: a suite class, optionally one of its test methods. */
  record SingleRun(@NotNull String testClass, @Nullable String testMethod) {
    boolean singleTest() {
      return testMethod != null;
    }
  }

  /** The generated entry point's class name (hxcpp names its binary after it). */
  static final String MAIN_CLASS = "IjSingleRun";

  private HaxeTestSingleRuns() {
  }

  /**
   * The single-run compile, built on the container's resolved build command:
   * the build file token is replaced by the selected section's arguments with
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

    int fileToken = resolved.command().indexOf(buildFile.getName());
    if (fileToken < 0) return null;

    String section = HaxeBuildSections.selectedSectionContent(
      project, new HaxeBuildFile(buildFile, HaxeBuildFileType.HXML));
    if (section == null) return null;

    Path generated = generatedDirectory(buildFile.getPath(), framework, singleRun);
    if (generated == null) return null;

    List<String> sectionArguments = swapEntryPoint(
      HxmlArguments.parseLines(section.lines().toList()), generated);
    List<String> command = new ArrayList<>(resolved.command().subList(0, fileToken));
    command.addAll(sectionArguments);
    command.addAll(resolved.command().subList(fileToken + 1, resolved.command().size()));
    command.add("-cp");
    command.add(generated.toString());
    command.add("--main");
    command.add(MAIN_CLASS);
    return new HaxeCompileCommands.Resolved(
      resolved.containerId(), command, resolved.workDirectory(), resolved.presentable(), resolved.connectEligible());
  }

  /**
   * The lime-family flavor of {@link #resolveCompile}: a DIRECT haxe compile
   * over the tool's effective display arguments — the tool itself cannot
   * compile a substitute main. Entry point swapped and output redirected as
   * in the hxml path. The display output ECHOES arguments injected into
   * earlier builds (the tool persists CLI extras in its export state), so
   * appended pairs already present verbatim are skipped — the reporter
   * macro attached twice would stream every event twice. Call in a read
   * action; the caller fetches the display arguments OUTSIDE it.
   */
  @Nullable
  static HaxeCompileCommands.Resolved resolveLimeCompile(@NotNull Project project,
                                                         @NotNull VirtualFile buildFile,
                                                         @NotNull HaxeTestFramework framework,
                                                         @NotNull String extraArguments,
                                                         @NotNull SingleRun singleRun,
                                                         @NotNull List<String> effectiveArguments) {
    Path generated = generatedDirectory(buildFile.getPath(), framework, singleRun);
    if (generated == null) return null;

    String containerId = HaxeContainers.containerIdFor(project, buildFile);
    String environmentSdk = HaxeEnvironmentStore.getInstance(project).getSdkName(containerId);
    List<String> command = new ArrayList<>();
    command.add(HaxeToolPathResolver.resolveHaxeExecutable(project, environmentSdk));
    command.addAll(swapEntryPoint(sanitizedDisplayArguments(effectiveArguments), generated));
    appendSkippingDuplicates(command, ParametersListUtil.parse(extraArguments));
    command.add("-cp");
    command.add(generated.toString());
    command.add("--main");
    command.add(MAIN_CLASS);

    VirtualFile parent = buildFile.getParent();
    String workDirectory = parent != null ? parent.getPath() : null;
    // never server-connected: the run must own its artifact, and swf output through the server corrupts
    return new HaxeCompileCommands.Resolved(containerId, command, workDirectory, String.join(" ", command), false);
  }

  // no-compilation: the display output describes a TYPING run - it suppresses
  //   hxcpp's native step and pairs with --no-output (dropped below), which
  //   suppresses generation entirely; both must go to produce an artifact.
  // lime-cffi: lime ships a SHADOWING haxe/Timer.hx whose lime_cffi branch
  //   swaps haxe.Timer for lime's event-loop timer - the frameworks use
  //   Timer, so the define drags the whole lime runtime into the headless
  //   single-run binary and its machinery keeps the process alive after the
  //   tests (no exit on hl). Without it the std-like branch compiles and
  //   lime/openfl classes fall to their stub backends.
  private static final Set<String> DROPPED_DEFINES = Set.of("no-compilation", "lime-cffi");

  /**
   * Display output repurposed as a compilable argument list: drops
   * {@code --no-output}, the {@link #DROPPED_DEFINES} and any echoed
   * {@code --connect} pair (persisted from an earlier server build) — the
   * single-run compile owns its artifact and never rides the compilation
   * server.
   */
  @NotNull
  private static List<String> sanitizedDisplayArguments(@NotNull List<String> arguments) {
    List<String> sanitized = new ArrayList<>(arguments.size());
    for (int i = 0; i < arguments.size(); i++) {
      String argument = arguments.get(i);
      boolean hasValue = i + 1 < arguments.size();
      if (argument.equals("--no-output")) continue;
      if (argument.equals("--connect") && hasValue) {
        i++;
        continue;
      }
      boolean droppedDefine = HxmlFileParser.DEFINE_FLAGS.contains(argument)
                              && hasValue && DROPPED_DEFINES.contains(arguments.get(i + 1));
      if (droppedDefine) {
        i++;
        continue;
      }
      sanitized.add(argument);
    }
    return sanitized;
  }

  /** Appends flag/value pairs, skipping pairs the command already carries verbatim (see {@link #resolveLimeCompile}). */
  private static void appendSkippingDuplicates(@NotNull List<String> command, @NotNull List<String> pairs) {
    for (int i = 0; i + 1 < pairs.size(); i += 2) {
      String flag = pairs.get(i);
      String value = pairs.get(i + 1);
      boolean present = false;
      for (int j = 0; j + 1 < command.size(); j++) {
        if (command.get(j).equals(flag) && command.get(j + 1).equals(value)) {
          present = true;
          break;
        }
      }
      if (!present) {
        command.add(flag);
        command.add(value);
      }
    }
    if (pairs.size() % 2 == 1) {
      command.add(pairs.get(pairs.size() - 1));
    }
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

  /**
   * Writes the one-page host for a BROWSER-hosted single run beside the js
   * artifact and returns its directory (the served web root); null when the
   * write fails. Lime's whole-build html5 output ships its own index.html —
   * only the direct-haxe single-run compile needs this harness.
   */
  @Nullable
  static Path browserHarnessRoot(@NotNull Path jsArtifact) {
    Path directory = jsArtifact.getParent();
    if (directory == null) return null;
    String page = """
      <!DOCTYPE html><html><head><meta charset="utf-8"></head>\
      <body><script src="%s"></script></body></html>""".formatted(jsArtifact.getFileName());
    try {
      Files.createDirectories(directory);
      Files.writeString(directory.resolve("index.html"), page);
      return directory;
    } catch (IOException e) {
      log.warn("cannot write the browser test harness: " + e.getMessage());
      return null;
    }
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
  private static Path generatedDirectory(@NotNull String buildFilePath,
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
      String substituted = template.replace("${TEST_CLASS}", singleRun.testClass());
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
