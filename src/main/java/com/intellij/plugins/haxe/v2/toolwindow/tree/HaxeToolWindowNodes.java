package com.intellij.plugins.haxe.v2.toolwindow.tree;

import com.intellij.plugins.haxe.v2.buildtools.settings.DefineEffect;
import com.intellij.plugins.haxe.HaxeBundle;
import com.intellij.plugins.haxe.config.HaxeTarget;
import com.intellij.plugins.haxe.v2.buildsystem.HaxeBuildFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * User objects for the Haxe tool window tree. Kept as plain data records so tree
 * nodes never retain disposable platform objects (modules, PSI).
 */
public final class HaxeToolWindowNodes {

  private HaxeToolWindowNodes() {
  }

  /**
   * Behaviour every tree user object provides itself, so the panel needs no
   * per-type switches that grow with each node kind.
   */
  public sealed interface HaxeToolWindowNode {
    /** Stable identity used to preserve expansion state across tree rebuilds. */
    @NotNull
    String expansionKey();

    /** The text the tree's speed search matches against. */
    @NotNull
    String speedSearchText();
  }

  public record ModuleNode(@NotNull String name) implements HaxeToolWindowNode {
    @Override
    public String expansionKey() {
      return "module:" + name;
    }

    @Override
    public String speedSearchText() {
      return name;
    }
  }

  /** Project-root container row, listing build files that belong to no module. */
  public record ProjectNode(@NotNull String name) implements HaxeToolWindowNode {
    @Override
    public String expansionKey() {
      return "project";
    }

    @Override
    public String speedSearchText() {
      return name;
    }
  }

  /**
   * A build file row; at most one per container is active (its configuration drives
   * the build). Manual rows were added by hand and can be removed; auto-detected
   * rows can only be hidden.
   */
  public record BuildFileRow(@NotNull HaxeBuildFile buildFile, @NotNull String containerId, boolean active, boolean manual)
    implements HaxeToolWindowNode {
    @Override
    public String expansionKey() {
      return "file:" + buildFile.file().getPath();
    }

    @Override
    public String speedSearchText() {
      return buildFile.file().getName();
    }
  }

  /** "Build" grouping row containing the container's build files. */
  public record BuildGroupNode(@NotNull String containerId, int count) implements HaxeToolWindowNode {
    @Override
    public String expansionKey() {
      return "build";
    }

    @Override
    public String speedSearchText() {
      return HaxeBundle.message("haxe.toolwindow.node.build");
    }
  }

  /** "Actions" grouping row under a build file; ownerId is the build file's path. */
  public record ActionsGroupNode(@NotNull String ownerId, int count) implements HaxeToolWindowNode {
    @Override
    public String expansionKey() {
      return "actions";
    }

    @Override
    public String speedSearchText() {
      return HaxeBundle.message("haxe.toolwindow.node.actions");
    }
  }

  /**
   * A runnable action row under a build file. The command is resolved at tree-build
   * time against that file's type, selected target and its container's environment
   * SDK. {@code presentableCommand} is the short display form (and, for custom
   * actions, the editable text).
   */
  public record ActionNode(@NotNull String ownerId,
                           @NotNull String name,
                           @NotNull List<String> command,
                           @Nullable String workDirectory,
                           @NotNull String presentableCommand,
                           boolean custom) implements HaxeToolWindowNode {
    @Override
    public String expansionKey() {
      return "action:" + name;
    }

    @Override
    public String speedSearchText() {
      return name;
    }
  }

  /**
   * "Build &amp; run" row under a build file's Actions: launches the target's
   * output through its run configuration (the build attached as a before-launch
   * step). Present only when the target output is launchable; {@code kind} is the
   * configuration kind shown in gray ("HashLink Application", …). Target and
   * output are captured at tree-build time - for lime files they come from the
   * selected target's `lime display` hxml.
   */
  public record ProgramNode(@NotNull HaxeBuildFile buildFile,
                            @NotNull String kind,
                            @NotNull HaxeTarget target,
                            @NotNull String targetOutput) implements HaxeToolWindowNode {
    @Override
    public String expansionKey() {
      return "program";
    }

    @Override
    public String speedSearchText() {
      return HaxeBundle.message("haxe.toolwindow.node.program");
    }
  }

  /** Target row under a build file; selectable for XML/HXP projects, static for HXML. */
  public record TargetNode(@NotNull HaxeBuildFile buildFile, @NotNull String displayName, boolean selectable)
    implements HaxeToolWindowNode {
    @Override
    public String expansionKey() {
      return "target:" + buildFile.file().getPath();
    }

    @Override
    public String speedSearchText() {
      return displayName;
    }
  }

  public enum GroupKind { DEFINES, LIBRARIES }

  /** "Defines" / "Libraries" grouping row with its child count. */
  public record GroupNode(@NotNull GroupKind kind, int count) implements HaxeToolWindowNode {
    @Override
    public String expansionKey() {
      return "group:" + kind;
    }

    @Override
    public String speedSearchText() {
      return kind == GroupKind.LIBRARIES
             ? HaxeBundle.message("haxe.toolwindow.node.libraries")
             : HaxeBundle.message("haxe.toolwindow.node.defines");
    }
  }

  public record DefineNode(@NotNull HaxeBuildFile owner, @NotNull String name, @Nullable String value)
    implements HaxeToolWindowNode {
    @Override
    public String expansionKey() {
      return "define:" + name;
    }

    @Override
    public String speedSearchText() {
      return name;
    }
  }

  /** "Environment" row under a container: its SDK choice and user defines. */
  public record EnvironmentNode(@NotNull String containerId,
                                @NotNull String displayName,
                                @NotNull Set<String> activeBuildFileDefines) implements HaxeToolWindowNode {
    @Override
    public String expansionKey() {
      return "env";
    }

    @Override
    public String speedSearchText() {
      return HaxeBundle.message("haxe.toolwindow.node.environment");
    }
  }

  /** Environment SDK row; clicking opens the SDK chooser popup. */
  public record EnvSdkNode(@NotNull String containerId, @NotNull String displayName, boolean missing)
    implements HaxeToolWindowNode {
    @Override
    public String expansionKey() {
      return "envsdk";
    }

    @Override
    public String speedSearchText() {
      return displayName;
    }
  }

  /** Environment language-level row; clicking opens the level chooser popup. Backed by the Haxe Compiler settings page. */
  public record EnvLanguageLevelNode(@NotNull String containerId, @NotNull String displayName)
    implements HaxeToolWindowNode {
    @Override
    public String expansionKey() {
      return "envlevel";
    }

    @Override
    public String speedSearchText() {
      return displayName;
    }
  }

  /** Environment "Defines" grouping row. */
  public record EnvDefinesNode(@NotNull String containerId, int count) implements HaxeToolWindowNode {
    @Override
    public String expansionKey() {
      return "envdefines";
    }

    @Override
    public String speedSearchText() {
      return HaxeBundle.message("haxe.toolwindow.node.environment.defines");
    }
  }

  /** "Compilation" grouping row: the compile command and compilation server rows. */
  public record CompilationGroupNode(@NotNull String containerId) implements HaxeToolWindowNode {
    @Override
    public String expansionKey() {
      return "compilation";
    }

    @Override
    public String speedSearchText() {
      return HaxeBundle.message("haxe.toolwindow.node.compilation");
    }
  }

  /**
   * Compilation server row: shows the project server's state for this container.
   * Clicking toggles the container's participation, or opens Build Tools settings
   * while the server is disabled project-wide. {@code contextFailure} carries the
   * container's last failed compiler request (a build context that does not
   * compile), shown as a warning on the row.
   */
  /** Renderer fragment tag on the server row's failure text: clicking it opens the server console's status view. */
  public record ServerFailureLink(@NotNull String containerId) {
  }

  public record CompilationServerNode(@NotNull String containerId,
                                      @NotNull String display,
                                      boolean projectEnabled,
                                      boolean moduleUses,
                                      boolean running,
                                      boolean connectEligible,
                                      @Nullable String contextFailure) implements HaxeToolWindowNode {
    @Override
    public String expansionKey() {
      return "server";
    }

    @Override
    public String speedSearchText() {
      return display;
    }
  }

  /**
   * "Compile command" row: what compiling this container runs.
   * {@code command} is null while unconfigured (or the file is gone). Candidates
   * feed the configure dialog: the container's build file paths and, per file,
   * the action names available as command overrides.
   */
  public record EnvCompileCommandNode(@NotNull String containerId,
                                      @NotNull String display,
                                      @Nullable List<String> command,
                                      @Nullable String workDirectory,
                                      @NotNull List<String> candidateFilePaths,
                                      @NotNull Map<String, List<String>> actionNamesByFile,
                                      boolean connectEligible) implements HaxeToolWindowNode {
    @Override
    public String expansionKey() {
      return "envcompile";
    }

    @Override
    public String speedSearchText() {
      return display;
    }
  }

  /** A user define entry; inBuildFile = the container's active build file declares the same name. */
  public record EnvDefineNode(@NotNull String containerId,
                              @NotNull String name,
                              @NotNull String value,
                              @NotNull DefineEffect effect,
                              boolean inBuildFile) implements HaxeToolWindowNode {
    @Override
    public String expansionKey() {
      return "envdef:" + name;
    }

    @Override
    public String speedSearchText() {
      return name;
    }
  }

  /**
   * A haxelib dependency; missing = the name - or the PINNED version - is not
   * installed according to haxelib. {@code version} is what the build file pins;
   * {@code resolvedVersion} is the version haxelib has selected (possibly "dev"
   * or "git") and is used when the file pins nothing.
   */
  public record LibraryNode(@NotNull HaxeBuildFile owner,
                            @NotNull String name,
                            @Nullable String version,
                            @Nullable String resolvedVersion,
                            boolean installed) implements HaxeToolWindowNode {

    /** The version to show: the pinned one when declared, otherwise haxelib's selected version. */
    @Nullable
    public String displayVersion() {
      return version != null ? version : resolvedVersion;
    }

    @Override
    public String expansionKey() {
      return "lib:" + name;
    }

    @Override
    public String speedSearchText() {
      return name;
    }
  }
}
