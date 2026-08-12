package com.intellij.plugins.haxe.v2.toolwindow.tree;

import com.intellij.plugins.haxe.v2.buildtools.settings.DefineEffect;
import com.intellij.execution.runners.ExecutionUtil;
import com.intellij.icons.AllIcons;
import com.intellij.plugins.haxe.HaxeBundle;
import com.intellij.plugins.haxe.v2.toolwindow.tree.HaxeToolWindowNodes.*;
import com.intellij.ui.ColoredTreeCellRenderer;
import com.intellij.ui.SimpleTextAttributes;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;

/**
 * Renders the Haxe tool window tree: modules, build files, target, define and
 * library rows. Missing libraries are shown in error color.
 */
public final class HaxeToolWindowTreeRenderer extends ColoredTreeCellRenderer {

  private static final SimpleTextAttributes STRIKEOUT_ATTRIBUTES =
    new SimpleTextAttributes(SimpleTextAttributes.STYLE_STRIKEOUT, null);

  @Override
  public void customizeCellRenderer(@NotNull JTree tree,
                                    Object value,
                                    boolean selected,
                                    boolean expanded,
                                    boolean leaf,
                                    int row,
                                    boolean hasFocus) {
    if (!(value instanceof DefaultMutableTreeNode node)) return;

    // the renderer instance is shared across rows - a stale tooltip must not leak
    setToolTipText(tooltipFor(node.getUserObject()));
    switch (node.getUserObject()) {
      case ModuleNode moduleNode -> {
        setIcon(AllIcons.Nodes.Module);
        append(moduleNode.name());
      }
      case ProjectNode projectNode -> {
        setIcon(AllIcons.Nodes.Project);
        append(projectNode.name());
      }
      case BuildFileRow fileRow -> {
        setIcon(fileRow.buildFile().type().getIcon());
        if (fileRow.active()) {
          append(fileRow.buildFile().file().getName(), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES);
          append("  " + HaxeBundle.message("haxe.toolwindow.node.build.file.active"),
                 SimpleTextAttributes.GRAYED_ATTRIBUTES);
        }
        else {
          append(fileRow.buildFile().file().getName());
        }
      }
      case TargetNode targetNode -> {
        setIcon(AllIcons.RunConfigurations.Application);
        append(HaxeBundle.message("haxe.toolwindow.node.target"));
        append("  " + targetNode.displayName(), SimpleTextAttributes.GRAYED_ATTRIBUTES);
        if (targetNode.selectable()) {
          append(" ▾", SimpleTextAttributes.GRAYED_ATTRIBUTES);
        }
      }
      case GroupNode groupNode -> {
        setIcon(groupNode.kind() == GroupKind.LIBRARIES ? AllIcons.Nodes.PpLibFolder : AllIcons.Nodes.Folder);
        append(groupLabel(groupNode.kind()));
        append(" (" + groupNode.count() + ")", SimpleTextAttributes.GRAYED_ATTRIBUTES);
      }
      case DefineNode defineNode -> {
        setIcon(AllIcons.Nodes.Property);
        append(defineNode.name());
        if (defineNode.value() != null) {
          append(" = " + defineNode.value(), SimpleTextAttributes.GRAYED_ATTRIBUTES);
        }
      }
      case EnvironmentNode ignored -> {
        setIcon(AllIcons.General.Settings);
        append(HaxeBundle.message("haxe.toolwindow.node.environment"));
      }
      case BuildGroupNode buildGroup -> {
        setIcon(AllIcons.Nodes.Folder);
        append(HaxeBundle.message("haxe.toolwindow.node.build"));
        append(" (" + buildGroup.count() + ")", SimpleTextAttributes.GRAYED_ATTRIBUTES);
      }
      case CompilationGroupNode ignored -> {
        setIcon(AllIcons.General.Settings);
        append(HaxeBundle.message("haxe.toolwindow.node.compilation"));
      }
      case CompilationServerNode serverNode -> {
        setIcon(serverNode.running() ? ExecutionUtil.getLiveIndicator(AllIcons.Webreferences.Server)
                                     : AllIcons.Webreferences.Server);
        append(HaxeBundle.message("haxe.toolwindow.node.server"));
        append("  " + serverNode.display(), SimpleTextAttributes.GRAYED_ATTRIBUTES);
        append(" ▾", SimpleTextAttributes.GRAYED_ATTRIBUTES);
        if (serverNode.contextFailure() != null) {
          append("  " + HaxeBundle.message("haxe.toolwindow.server.context.failing"), SimpleTextAttributes.ERROR_ATTRIBUTES,
                 new HaxeToolWindowNodes.ServerFailureLink(serverNode.containerId()));
        }
      }
      case ActionsGroupNode actionsGroup -> {
        setIcon(AllIcons.Nodes.ConfigFolder);
        append(HaxeBundle.message("haxe.toolwindow.node.actions"));
        append(" (" + actionsGroup.count() + ")", SimpleTextAttributes.GRAYED_ATTRIBUTES);
      }
      case ActionNode actionNode -> {
        setIcon(isBuildAction(actionNode.name()) ? AllIcons.Actions.Compile : AllIcons.Actions.Execute);
        append(actionNode.name());
        append("  " + actionNode.presentableCommand(), SimpleTextAttributes.GRAYED_ATTRIBUTES);
      }
      case ProgramNode programNode -> {
        setIcon(AllIcons.Actions.Execute);
        append(HaxeBundle.message("haxe.toolwindow.node.program"));
        append("  " + programNode.kind(), SimpleTextAttributes.GRAYED_ATTRIBUTES);
      }
      case EnvSdkNode sdkNode -> {
        setIcon(AllIcons.Nodes.PpJdk);
        append(HaxeBundle.message("haxe.toolwindow.node.environment.sdk"));
        append("  " + sdkNode.displayName(),
               sdkNode.missing() ? SimpleTextAttributes.ERROR_ATTRIBUTES : SimpleTextAttributes.GRAYED_ATTRIBUTES);
        append(" ▾", SimpleTextAttributes.GRAYED_ATTRIBUTES);
      }
      case EnvCompileCommandNode buildCommand -> {
        setIcon(AllIcons.Actions.Compile);
        append(HaxeBundle.message("haxe.toolwindow.node.compile.command"));
        append("  " + buildCommand.display(), SimpleTextAttributes.GRAYED_ATTRIBUTES);
        append(" ▾", SimpleTextAttributes.GRAYED_ATTRIBUTES);
      }
      case EnvLanguageLevelNode levelNode -> {
        setIcon(AllIcons.Nodes.Property);
        append(HaxeBundle.message("haxe.toolwindow.node.environment.language.level"));
        append("  " + levelNode.displayName(), SimpleTextAttributes.GRAYED_ATTRIBUTES);
        append(" ▾", SimpleTextAttributes.GRAYED_ATTRIBUTES);
      }
      case EnvDefinesNode definesNode -> {
        setIcon(AllIcons.Nodes.Folder);
        append(HaxeBundle.message("haxe.toolwindow.node.environment.defines"));
        append(" (" + definesNode.count() + ")", SimpleTextAttributes.GRAYED_ATTRIBUTES);
      }
      case EnvDefineNode defineNode -> {
        setIcon(AllIcons.Nodes.Property);
        if (defineNode.effect() == DefineEffect.REMOVE) {
          append(defineNode.name(), STRIKEOUT_ATTRIBUTES);
          append("  " + HaxeBundle.message("haxe.environment.effect.remove"), SimpleTextAttributes.GRAYED_ATTRIBUTES);
        }
        else {
          append(defineNode.name());
          if (!defineNode.value().isEmpty()) {
            append(" = " + defineNode.value(), SimpleTextAttributes.GRAYED_ATTRIBUTES);
          }
          if (defineNode.inBuildFile()) {
            append("  " + HaxeBundle.message("haxe.toolwindow.node.define.overriding"), SimpleTextAttributes.GRAYED_ATTRIBUTES);
          }
        }
      }
      case LibraryNode libraryNode -> {
        setIcon(AllIcons.Nodes.PpLib);
        var attributes = libraryNode.installed() ? SimpleTextAttributes.REGULAR_ATTRIBUTES
                                                 : SimpleTextAttributes.ERROR_ATTRIBUTES;
        append(libraryNode.name(), attributes);
        if (libraryNode.displayVersion() != null) {
          append(" : " + libraryNode.displayVersion(), SimpleTextAttributes.GRAYED_ATTRIBUTES);
        }
        if (!libraryNode.installed()) {
          // a resolved version means the library name IS installed - only the pinned version is absent
          String message = libraryNode.resolvedVersion() != null
                           ? HaxeBundle.message("haxe.toolwindow.node.library.version.missing")
                           : HaxeBundle.message("haxe.toolwindow.node.library.missing");
          append("  " + message, SimpleTextAttributes.GRAYED_ATTRIBUTES);
        }
      }
      case null, default -> {
        Object userObject = node.getUserObject();
        if (userObject != null) append(String.valueOf(userObject));
      }
    }
  }

  /** What a row means, shown as its tooltip; null for self-explanatory rows. */
  private static String tooltipFor(Object userObject) {
    return switch (userObject) {
      case CompilationGroupNode ignored -> HaxeBundle.message("haxe.toolwindow.tooltip.compilation");
      case EnvCompileCommandNode ignored -> HaxeBundle.message("haxe.toolwindow.tooltip.compile.command");
      case CompilationServerNode serverNode -> {
        if (serverNode.contextFailure() != null) {
          yield HaxeBundle.message("haxe.toolwindow.tooltip.server.context.failing", serverNode.contextFailure());
        }
        yield serverNode.connectEligible() ? HaxeBundle.message("haxe.toolwindow.tooltip.server")
                                           : HaxeBundle.message("haxe.toolwindow.tooltip.server.not.connectable");
      }
      case EnvironmentNode ignored -> HaxeBundle.message("haxe.toolwindow.tooltip.environment");
      case EnvSdkNode ignored -> HaxeBundle.message("haxe.toolwindow.tooltip.environment.sdk");
      case EnvLanguageLevelNode ignored -> HaxeBundle.message("haxe.toolwindow.tooltip.environment.language.level");
      case EnvDefinesNode ignored -> HaxeBundle.message("haxe.toolwindow.tooltip.environment.defines");
      case BuildGroupNode ignored -> HaxeBundle.message("haxe.toolwindow.tooltip.build.files");
      case TargetNode targetNode ->
        HaxeBundle.message(targetNode.selectable() ? "haxe.toolwindow.tooltip.target.selectable"
                                                   : "haxe.toolwindow.tooltip.target");
      case GroupNode groupNode ->
        HaxeBundle.message(groupNode.kind() == GroupKind.LIBRARIES ? "haxe.toolwindow.tooltip.libraries"
                                                                   : "haxe.toolwindow.tooltip.defines");
      case ActionsGroupNode ignored -> HaxeBundle.message("haxe.toolwindow.tooltip.actions");
      case ProgramNode programNode -> HaxeBundle.message("haxe.toolwindow.tooltip.program", programNode.kind());
      case null, default -> null;
    };
  }

  /** Actions that only produce output get the build hammer; ones that run something keep the play icon. */
  private static boolean isBuildAction(@NotNull String name) {
    return name.equalsIgnoreCase("build") || name.equals("compile") || name.equalsIgnoreCase("clean");
  }

  @NotNull
  private static String groupLabel(@NotNull GroupKind kind) {
    return kind == GroupKind.LIBRARIES
           ? HaxeBundle.message("haxe.toolwindow.node.libraries")
           : HaxeBundle.message("haxe.toolwindow.node.defines");
  }
}
