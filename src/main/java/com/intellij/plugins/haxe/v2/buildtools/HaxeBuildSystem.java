package com.intellij.plugins.haxe.v2.buildtools;

import com.intellij.openapi.project.Project;
import com.intellij.plugins.haxe.config.HaxeTarget;
import com.intellij.plugins.haxe.v2.buildsystem.HaxeBuildFile;
import com.intellij.plugins.haxe.v2.buildsystem.HaxeBuildFileType;
import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The per-build-system traits behind one interface, so run/debug callers stop
 * growing per-type switches. Each implementation resolves the file's CURRENT
 * selection itself — the tool window's target row for lime/nme, the selected
 * {@code --next} section for hxml. Whether a resolved target can be DEBUGGED
 * is not a build-system trait: that lives in {@code HaxeDebugSupport}, one
 * home shared by program and test sessions.
 */
public interface HaxeBuildSystem {

  /**
   * The haxe target the build file's current selection compiles to, or null
   * when it declares none (an hxml without a target flag, an hxp script
   * deciding targets in code, an unknown lime/nme target id).
   * Call inside a read action.
   */
  @Nullable
  HaxeTarget launchTarget(@NotNull Project project, @NotNull HaxeBuildFile buildFile);

  /**
   * The extra compile arguments that make the current selection's output
   * debuggable (applied by the before-run compile under the Debug executor
   * only), or null when the selection has no debugger support yet.
   * Call inside a read action.
   */
  @Nullable
  List<String> debugCompileAdditions(@NotNull Project project, @NotNull HaxeBuildFile buildFile);

  /** The build system serving the file type. */
  @NotNull
  static HaxeBuildSystem of(@NotNull HaxeBuildFileType type) {
    return switch (type) {
      case HXML -> HXML_SYSTEM;
      case OPENFL, LIME, HXP_PROJECT -> LIME_SYSTEM;
      case NMML -> NME_SYSTEM;
      // a plain hxp script generates its compiler args in code - nothing to derive statically
      case HXP_SCRIPT -> UNKNOWN_SYSTEM;
    };
  }

  /** Target ids compiled through hxcpp whose output the HXCPP (IntelliJ) debugger can attach to ("cpp" is nme's host-desktop word; lime and nme share the rest). */
  List<String> DESKTOP_CPP_TARGETS = List.of("windows", "linux", "mac", "cpp");

  HaxeBuildSystem HXML_SYSTEM = new HaxeBuildSystem() {
    @Override
    public HaxeTarget launchTarget(@NotNull Project project, @NotNull HaxeBuildFile buildFile) {
      return HaxeBuildSections.inspectSelected(project, buildFile).target();
    }

    @Override
    public List<String> debugCompileAdditions(@NotNull Project project, @NotNull HaxeBuildFile buildFile) {
      HaxeTarget target = launchTarget(project, buildFile);
      return target != null ? HaxeDebugAdditions.forTarget(target) : null;
    }
  };

  HaxeBuildSystem LIME_SYSTEM = new HaxeBuildSystem() {
    @Override
    public HaxeTarget launchTarget(@NotNull Project project, @NotNull HaxeBuildFile buildFile) {
      return LimeProjects.targetFor(LimeProjects.selectedTargetFlag(project, buildFile.type(), buildFile.file()));
    }

    @Override
    public List<String> debugCompileAdditions(@NotNull Project project, @NotNull HaxeBuildFile buildFile) {
      // the lime tool takes -debug itself and forwards it into the haxe build it
      // generates - one flag covers every lime target
      List<String> additions = new ArrayList<>();
      additions.add("-debug");
      String targetFlag = LimeProjects.selectedTargetFlag(project, buildFile.type(), buildFile.file());
      // hxcpp debugging needs the in-debuggee DAP server compiled in; lime's
      // --haxelib override merges the lib exactly like a project <haxelib>
      // entry (include.xml and extraParams included), so no project.xml edit.
      // Run builds never get this: additions apply only under the Debug executor.
      if (DESKTOP_CPP_TARGETS.contains(targetFlag)) {
        additions.add("--haxelib=intellij-hxcpp-debug-server");
      }
      return additions;
    }
  };

  HaxeBuildSystem NME_SYSTEM = new HaxeBuildSystem() {
    @Override
    public HaxeTarget launchTarget(@NotNull Project project, @NotNull HaxeBuildFile buildFile) {
      return NmeProjects.targetFor(NmeProjects.selectedTargetFlag(project, buildFile.file()));
    }

    @Override
    public List<String> debugCompileAdditions(@NotNull Project project, @NotNull HaxeBuildFile buildFile) {
      List<String> additions = new ArrayList<>();
      additions.add("-debug");
      String targetFlag = NmeProjects.selectedTargetFlag(project, buildFile.file());
      // nme has no lime-style --haxelib override; a single-token "--library
      // <lib>" haxeflag becomes one line of the generated build.hxml, and
      // haxe pulls the lib with its extraParams (the server-injection macro).
      if (DESKTOP_CPP_TARGETS.contains(targetFlag)) {
        additions.add("--library intellij-hxcpp-debug-server");
      }
      return additions;
    }
  };

  HaxeBuildSystem UNKNOWN_SYSTEM = new HaxeBuildSystem() {
    @Override
    public HaxeTarget launchTarget(@NotNull Project project, @NotNull HaxeBuildFile buildFile) {
      return null;
    }

    @Override
    public List<String> debugCompileAdditions(@NotNull Project project, @NotNull HaxeBuildFile buildFile) {
      return null;
    }
  };
}
