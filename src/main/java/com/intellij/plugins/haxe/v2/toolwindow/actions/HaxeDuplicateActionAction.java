package com.intellij.plugins.haxe.v2.toolwindow.actions;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.plugins.haxe.HaxeBundle;
import com.intellij.plugins.haxe.v2.buildsystem.HaxeBuildFile;
import com.intellij.plugins.haxe.v2.buildtools.HaxeBuildSystem;
import com.intellij.plugins.haxe.v2.buildtools.HaxeBuildWorkDirectories;
import com.intellij.plugins.haxe.v2.buildtools.HaxeContainers;
import com.intellij.plugins.haxe.v2.buildtools.settings.HaxeCustomActionsStore;
import com.intellij.plugins.haxe.v2.buildtools.settings.HaxeCustomActionsStore.CustomAction;
import com.intellij.plugins.haxe.v2.buildtools.settings.HaxeCustomActionsStore.Launch;
import com.intellij.plugins.haxe.v2.buildtools.settings.HaxeEnvironmentStore;
import com.intellij.plugins.haxe.v2.toolwindow.HaxeToolWindowPanel;
import com.intellij.plugins.haxe.v2.toolwindow.tree.HaxeToolWindowNodes.ActionNode;
import com.intellij.plugins.haxe.v2.toolwindow.tree.HaxeToolWindowNodes.BuildFileRow;
import com.intellij.plugins.haxe.v2.toolwindow.tree.HaxeToolWindowNodes.ProgramNode;
import com.intellij.plugins.haxe.v2.toolwindow.ui.HaxeCustomActionDialog;
import com.intellij.util.execution.ParametersListUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Tree context menu on any action row or the Build &amp; run row: opens the
 * Add Action dialog pre-filled with a copy of the row - a generated action's
 * resolved command and work directory, a custom action's stored values, the
 * Build &amp; run row's default build command with its launch - so a built-in
 * row becomes a custom one to change.
 */
public final class HaxeDuplicateActionAction extends DumbAwareAction {

  private final HaxeToolWindowPanel panel;

  public HaxeDuplicateActionAction(@NotNull HaxeToolWindowPanel panel) {
    super(HaxeBundle.message("haxe.toolwindow.duplicate.action"));
    this.panel = panel;
  }

  @Override
  public void actionPerformed(@NotNull AnActionEvent e) {
    Project project = e.getProject();
    BuildFileRow row = panel.getSelectedBuildFileRowAncestor();
    if (project == null || row == null) return;
    CustomAction template = templateOf(project, row.buildFile(), panel.getSelectedUserObject());
    if (template == null) return;

    String launchOutput = launchOutputOf(panel.getSelectedUserObject());
    HaxeCustomActionDialog dialog = HaxeCustomActionDialog.forDuplicate(project, row.buildFile(), launchOutput, template);
    if (dialog.showAndGet()) {
      HaxeCustomActionsStore.getInstance(project).addAction(row.buildFile().file().getPath(), dialog.getAction());
      panel.refreshTree();
    }
  }

  @Override
  public void update(@NotNull AnActionEvent e) {
    Object selected = panel.getSelectedUserObject();
    e.getPresentation().setEnabledAndVisible(selected instanceof ActionNode || selected instanceof ProgramNode);
  }

  @Override
  public @NotNull ActionUpdateThread getActionUpdateThread() {
    return ActionUpdateThread.EDT;
  }

  @Nullable
  private static CustomAction templateOf(@NotNull Project project, @NotNull HaxeBuildFile buildFile, @Nullable Object selected) {
    return switch (selected) {
      case ActionNode actionNode when actionNode.custom() -> copyOfStored(project, actionNode);
      case ActionNode actionNode -> copyOfGenerated(project, buildFile, actionNode);
      case ProgramNode programNode -> copyOfBuildAndRun(project, programNode);
      case null, default -> null;
    };
  }

  @Nullable
  private static String launchOutputOf(@Nullable Object selected) {
    return switch (selected) {
      case ActionNode actionNode -> actionNode.launchOutput();
      case ProgramNode programNode -> programNode.targetOutput();
      case null, default -> null;
    };
  }

  /** The stored values, not the row's ${target}-expanded display form. */
  @NotNull
  private static CustomAction copyOfStored(@NotNull Project project, @NotNull ActionNode actionNode) {
    CustomAction stored = HaxeCustomActionsStore.getInstance(project).getActions(actionNode.ownerId()).stream()
      .filter(action -> action.name().equals(actionNode.name()))
      .findFirst()
      .orElse(new CustomAction(actionNode.name(), actionNode.presentableCommand(), ""));
    return new CustomAction(copyName(stored.name()), stored.command(), stored.workDirectory(), stored.launch());
  }

  /** The resolved command line as the row runs it; the work directory stays blank when it is the build file's own. */
  @NotNull
  private static CustomAction copyOfGenerated(@NotNull Project project, @NotNull HaxeBuildFile buildFile, @NotNull ActionNode actionNode) {
    String command = ParametersListUtil.join(actionNode.command());
    return new CustomAction(copyName(actionNode.name()), command, workDirectoryOverride(project, buildFile, actionNode.workDirectory()), null);
  }

  /** The default build action's command with a launch of the row's kind, every parameter at its default. */
  @Nullable
  private static CustomAction copyOfBuildAndRun(@NotNull Project project, @NotNull ProgramNode programNode) {
    HaxeBuildFile buildFile = programNode.buildFile();
    VirtualFile file = buildFile.file();
    HaxeBuildSystem buildSystem = HaxeBuildSystem.of(buildFile.type());
    String containerId = ReadAction.computeBlocking(() -> HaxeContainers.containerIdFor(project, file));
    String environmentSdk = HaxeEnvironmentStore.getInstance(project).getSdkName(containerId);
    List<String> command = buildSystem.actionCommand(project, environmentSdk, buildFile, buildSystem.defaultBuildActionName());
    if (command == null) return null;

    Launch launch = new Launch.Create(programNode.kind().name(), Map.of(), true);
    String name = copyName(HaxeBundle.message("haxe.toolwindow.node.program"));
    return new CustomAction(name, ParametersListUtil.join(command), "", launch);
  }

  @NotNull
  private static String workDirectoryOverride(@NotNull Project project, @NotNull HaxeBuildFile buildFile, @Nullable String workDirectory) {
    String fileWorkDirectory = HaxeBuildWorkDirectories.workDirectory(project, buildFile.file());
    return workDirectory == null || Objects.equals(workDirectory, fileWorkDirectory) ? "" : workDirectory;
  }

  @NotNull
  private static String copyName(@NotNull String name) {
    return HaxeBundle.message("haxe.toolwindow.duplicate.action.name", name);
  }
}
