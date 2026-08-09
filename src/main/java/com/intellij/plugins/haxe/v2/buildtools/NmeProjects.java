package com.intellij.plugins.haxe.v2.buildtools;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.plugins.haxe.config.HaxeTarget;
import com.intellij.plugins.haxe.v2.buildsystem.HaxeBuildFileInfo;
import com.intellij.plugins.haxe.v2.buildsystem.HaxeBuildFileInspector;
import com.intellij.plugins.haxe.v2.buildsystem.ProjectXmlParser;
import com.intellij.plugins.haxe.v2.toolwindow.HaxeTargetOptions;
import com.intellij.plugins.haxe.v2.toolwindow.HaxeTargetSelectionStore;
import com.intellij.plugins.haxe.v2.toolwindow.tree.HaxeBuildFileType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Facts about NME projects (project.nmml): the nme tool's commands. The
 * lime-family counterpart is {@link LimeProjects}.
 */
public final class NmeProjects {

  /**
   * The nme tool's core project actions (it accepts many more - installer,
   * trace, update... - which stay available as custom actions). "test" is
   * update + build + run, mirroring lime's.
   */
  public static final List<String> DEFAULT_ACTIONS = List.of("test", "run", "build", "clean");

  /** The default action an nmml file compiles with. */
  public static final String BUILD_ACTION = "build";

  private NmeProjects() {
  }

  /** The file's currently selected NME target as the tool's target argument (e.g. "html5", "windows"). */
  @NotNull
  public static String selectedTargetFlag(@NotNull Project project, @NotNull VirtualFile file) {
    String targetId = HaxeTargetSelectionStore.getInstance(project).getSelectedTargetId(file);
    return HaxeTargetOptions.targetFlagFor(HaxeBuildFileType.NMML, targetId);
  }

  /** The selected target's full flag list — the target word plus configured extras (e.g. "-64"). */
  @NotNull
  public static List<String> selectedTargetFlags(@NotNull Project project, @NotNull VirtualFile file) {
    String targetId = HaxeTargetSelectionStore.getInstance(project).getSelectedTargetId(file);
    return HaxeTargetOptions.targetFlagsFor(HaxeBuildFileType.NMML, targetId);
  }

  /**
   * A {@code haxelib run nme …} invocation. These can compile through the
   * server, but only as a single {@code "--connect <port>"} token - the tool's
   * two-token forwarding is broken (see
   * {@link HaxeCompileCommands#connectIfEnabled}).
   */
  public static boolean isToolCommand(@NotNull List<String> command) {
    return command.size() >= 3 && "run".equals(command.get(1)) && "nme".equals(command.get(2));
  }

  /**
   * One of the tool's actions as a full command line, using the file's selected
   * target (all its flags). The tool recognizes the target token anywhere in
   * the argument list and treats the remaining first word as the project file,
   * so the action-file-target order matches the lime commands.
   */
  @NotNull
  public static List<String> actionCommand(@NotNull Project project,
                                           @Nullable String environmentSdk,
                                           @NotNull VirtualFile file,
                                           @NotNull String actionName) {
    List<String> command = new ArrayList<>(List.of(HaxeToolPathResolver.resolveHaxelibExecutable(project, environmentSdk),
                                                   "run", "nme", actionName, file.getName()));
    command.addAll(selectedTargetFlags(project, file));
    return command;
  }

  /** The haxe target compiled and the artifact packaged for a selected nme target. */
  public record TargetArtifact(@NotNull HaxeTarget target, @NotNull String relativeOutput) {
  }

  /**
   * The raw nmml info completed with the selected target's haxe target and
   * artifact path - the xml declares neither. Unchanged when the target's
   * artifact is not mapped or the nmml has no {@code <app file>}.
   * Call in a read action.
   */
  @NotNull
  public static HaxeBuildFileInfo withTargetArtifact(@NotNull Project project,
                                                     @NotNull VirtualFile file,
                                                     @NotNull HaxeBuildFileInfo raw) {
    String content = HaxeBuildFileInspector.loadText(file);
    String appFile = content == null ? null : ProjectXmlParser.parseAppFile(content);
    if (appFile == null) return raw;

    // the nmml's <app path> overrides the tool's default "bin" output root
    String appPath = ProjectXmlParser.parseAppPath(content);
    String outputRoot = appPath != null ? appPath : "bin";
    TargetArtifact artifact = targetArtifact(selectedTargetFlag(project, file), appFile, outputRoot);
    if (artifact == null) return raw;
    return new HaxeBuildFileInfo(artifact.target(), artifact.relativeOutput(), raw.defines(), raw.libraries(), raw.classpaths());
  }

  /**
   * Where the nme tool packages a target's runnable artifact, relative to the
   * project file: {@code <output root>/<platform dir>/<app file>/...} (mac
   * wraps an .app bundle instead of a plain directory). The output root is the
   * nmml's {@code <app path>}, defaulting to {@code bin}. Platform dirs follow
   * the tool's naming, which suffixes "64" for the 64-bit desktop builds every
   * modern mac/linux host produces. "cpp" builds for the host desktop. Target
   * flags without a launchable artifact mapping (android, ios, neko's
   * bootstrapped executable, user-configured console targets...) return null.
   */
  @Nullable
  public static TargetArtifact targetArtifact(@NotNull String targetFlag, @NotNull String appFile, @NotNull String outputRoot) {
    return switch (targetFlag) {
      case "cpp" -> hostDesktopArtifact(appFile, outputRoot);
      case "windows" -> windowsArtifact(appFile, outputRoot);
      case "linux" -> linuxArtifact(appFile, outputRoot);
      case "mac" -> macArtifact(appFile, outputRoot);
      case "flash" -> new TargetArtifact(HaxeTarget.FLASH, outputRoot + "/flash/" + appFile + "/" + appFile + ".swf");
      default -> null;
    };
  }

  @NotNull
  private static TargetArtifact hostDesktopArtifact(@NotNull String appFile, @NotNull String outputRoot) {
    if (SystemInfo.isWindows) return windowsArtifact(appFile, outputRoot);
    if (SystemInfo.isMac) return macArtifact(appFile, outputRoot);
    return linuxArtifact(appFile, outputRoot);
  }

  @NotNull
  private static TargetArtifact windowsArtifact(@NotNull String appFile, @NotNull String outputRoot) {
    return new TargetArtifact(HaxeTarget.CPP, outputRoot + "/windows/" + appFile + "/" + appFile + ".exe");
  }

  @NotNull
  private static TargetArtifact linuxArtifact(@NotNull String appFile, @NotNull String outputRoot) {
    return new TargetArtifact(HaxeTarget.CPP, outputRoot + "/linux64/" + appFile + "/" + appFile);
  }

  @NotNull
  private static TargetArtifact macArtifact(@NotNull String appFile, @NotNull String outputRoot) {
    return new TargetArtifact(HaxeTarget.CPP, outputRoot + "/mac64/" + appFile + ".app/Contents/MacOS/" + appFile);
  }
}
