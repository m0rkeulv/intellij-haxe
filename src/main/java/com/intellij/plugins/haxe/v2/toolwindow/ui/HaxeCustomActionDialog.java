package com.intellij.plugins.haxe.v2.toolwindow.ui;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.plugins.haxe.HaxeBundle;
import com.intellij.plugins.haxe.util.ui.HaxeDialogHints;
import com.intellij.plugins.haxe.v2.buildtools.HaxeCustomCommands;
import com.intellij.plugins.haxe.v2.buildtools.settings.HaxeCustomActionsStore;
import com.intellij.plugins.haxe.v2.buildtools.settings.HaxeCustomActionsStore.CustomAction;
import com.intellij.plugins.haxe.v2.buildsystem.HaxeBuildFile;
import com.intellij.plugins.haxe.v2.buildtools.HaxeBuildSystem;
import com.intellij.plugins.haxe.v2.buildtools.settings.HaxeCustomActionsStore.Launch;
import com.intellij.plugins.haxe.v2.runconfig.HaxeLaunchKind;
import com.intellij.plugins.haxe.v2.runconfig.HaxeLaunchKind.Parameter;
import com.intellij.plugins.haxe.v2.runconfig.HaxeProgramLaunches;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.util.function.Function;
import java.util.HashSet;
import java.util.Set;
import java.util.Map;
import java.awt.BorderLayout;

/**
 * Add/edit dialog for a custom tool window action: a name, the command line to
 * run and its working directory, and for a build file's action the optional
 * run configuration it creates or starts ({@link HaxeLaunchSection}). A blank
 * work directory falls back to the row
 * kind's default (the build file's directory for actions, the container root
 * for tools); {@code ${moduleRoot}} and {@code ${projectRoot}} expand in both
 * the command and the work directory. The layout lives in the matching .form
 * (labels bind their bundle keys there).
 */
public final class HaxeCustomActionDialog extends DialogWrapper {

  /** The values being edited; action rows and tool rows share the dialog. */
  public record EditedCommand(@NotNull String name, @NotNull String command, @NotNull String workDirectory) {
  }

  private final String nameRequiredKey;
  /** The names the edited action may not take; see {@link #takenNames}. */
  private final Set<String> takenNames;
  /** The launch section of a build file's action; null for a tool. */
  private final HaxeLaunchSection launchSection;

  private JPanel panel;
  private JBTextField nameField;
  private JBTextField commandField;
  private JBTextField workDirectoryField;
  private JPanel launchPanel;
  private JTextPane hintArea;

  private HaxeCustomActionDialog(@NotNull Project project, @Nullable EditedCommand initial, boolean adding,
                                 @NotNull String addTitleKey, @NotNull String editTitleKey,
                                 @Nullable String hintKey, @NotNull String nameRequiredKey,
                                 @NotNull Set<String> takenNames, @Nullable HaxeLaunchSection launchSection) {
    super(project);
    this.nameRequiredKey = nameRequiredKey;
    this.takenNames = takenNames;
    this.launchSection = launchSection;
    setTitle(HaxeBundle.message(adding ? addTitleKey : editTitleKey));
    HaxeDialogHints.style(hintArea);
    if (hintKey != null) {
      hintArea.setText(HaxeBundle.message(hintKey));
    }
    if (launchSection != null) {
      launchPanel.add(launchSection.getComponent(), BorderLayout.CENTER);
    }
    else {
      launchPanel.setVisible(false);
    }
    if (initial != null) {
      nameField.setText(initial.name());
      commandField.setText(initial.command());
      workDirectoryField.setText(initial.workDirectory());
    }
    else {
      workDirectoryField.setText(HaxeCustomCommands.MODULE_ROOT_VARIABLE);
    }
    init();
  }

  /**
   * A build file's custom action: the hint documents work directory and
   * placeholder expansion; the launch section offers the kinds with the
   * defaults derived from the file's current target output ({@code launchOutput},
   * null when the file produces nothing launchable).
   */
  @NotNull
  public static HaxeCustomActionDialog forAction(@NotNull Project project,
                                                 @NotNull HaxeBuildFile buildFile,
                                                 @Nullable String launchOutput,
                                                 @Nullable CustomAction initial) {
    return forAction(project, buildFile, launchOutput, initial, initial == null);
  }

  /** A copy of a row as a NEW action: the Add title over pre-filled values. */
  @NotNull
  public static HaxeCustomActionDialog forDuplicate(@NotNull Project project,
                                                    @NotNull HaxeBuildFile buildFile,
                                                    @Nullable String launchOutput,
                                                    @NotNull CustomAction template) {
    return forAction(project, buildFile, launchOutput, template, true);
  }

  @NotNull
  private static HaxeCustomActionDialog forAction(@NotNull Project project,
                                                  @NotNull HaxeBuildFile buildFile,
                                                  @Nullable String launchOutput,
                                                  @Nullable CustomAction initial,
                                                  boolean adding) {
    EditedCommand edited = initial == null
                           ? null
                           : new EditedCommand(initial.name(), initial.command(), initial.workDirectory());
    Function<HaxeLaunchKind, Map<Parameter, String>> defaults = kind -> launchDefaults(project, buildFile, launchOutput, kind);
    HaxeLaunchSection launchSection = new HaxeLaunchSection(project, defaults, initial == null ? null : initial.launch());
    Set<String> takenNames = takenNames(project, buildFile, adding || initial == null ? null : initial.name());
    return new HaxeCustomActionDialog(project, edited, adding,
                                      "haxe.custom.action.dialog.add.title", "haxe.custom.action.dialog.edit.title",
                                      null, "haxe.custom.action.dialog.name.required", takenNames, launchSection);
  }

  /**
   * The build file's reserved action names (the build step resolves them before
   * any custom one) and its other custom actions' names (the store keeps one
   * action per name); {@code editedName} is the edited action's current name,
   * still free to keep.
   */
  @NotNull
  private static Set<String> takenNames(@NotNull Project project, @NotNull HaxeBuildFile buildFile, @Nullable String editedName) {
    Set<String> taken = new HashSet<>(HaxeBuildSystem.of(buildFile.type()).reservedActionNames());
    for (CustomAction custom : HaxeCustomActionsStore.getInstance(project).getActions(buildFile.file().getPath())) {
      if (!custom.name().equals(editedName)) taken.add(custom.name());
    }
    return Set.copyOf(taken);
  }

  /** A container's custom tool: no target placeholders, defaults to the container root, no launch. */
  @NotNull
  public static HaxeCustomActionDialog forTool(@NotNull Project project, @Nullable EditedCommand initial) {
    return new HaxeCustomActionDialog(project, initial, initial == null,
                                      "haxe.custom.tool.dialog.add.title", "haxe.custom.tool.dialog.edit.title",
                                      "haxe.custom.tool.dialog.hint", "haxe.custom.tool.dialog.name.required",
                                      Set.of(), null);
  }

  @NotNull
  private static Map<Parameter, String> launchDefaults(@NotNull Project project,
                                                       @NotNull HaxeBuildFile buildFile,
                                                       @Nullable String launchOutput,
                                                       @NotNull HaxeLaunchKind kind) {
    if (launchOutput == null) return Map.of();
    return HaxeProgramLaunches.defaultParameters(project, buildFile, kind, launchOutput);
  }

  /** The form's launch slot; the section's panel lands in it when the dialog edits a build file's action. */
  private void createUIComponents() {
    launchPanel = new JPanel(new BorderLayout());
  }

  @Override
  protected @NotNull JComponent createCenterPanel() {
    panel.setPreferredSize(JBUI.size(560, -1));
    return panel;
  }

  @Override
  public @Nullable JComponent getPreferredFocusedComponent() {
    return nameField;
  }

  @Override
  protected @Nullable ValidationInfo doValidate() {
    if (StringUtil.isEmptyOrSpaces(nameField.getText())) {
      return new ValidationInfo(HaxeBundle.message(nameRequiredKey), nameField);
    }
    String name = nameField.getText().trim();
    if (takenNames.contains(name)) {
      return new ValidationInfo(HaxeBundle.message("haxe.custom.action.dialog.name.taken", name), nameField);
    }
    // a bare trigger of an existing configuration needs no command; everything else does
    boolean triggersExisting = launchSection != null && launchSection.launch() instanceof Launch.Existing;
    if (StringUtil.isEmptyOrSpaces(commandField.getText()) && !triggersExisting) {
      return new ValidationInfo(HaxeBundle.message("haxe.custom.action.dialog.command.required"), commandField);
    }
    return null;
  }

  @NotNull
  public CustomAction getAction() {
    EditedCommand edited = getEditedCommand();
    Launch launch = launchSection == null ? null : launchSection.launch();
    return new CustomAction(edited.name(), edited.command(), edited.workDirectory(), launch);
  }

  /** The edited values, independent of which store they land in. */
  @NotNull
  public EditedCommand getEditedCommand() {
    return new EditedCommand(nameField.getText().trim(), commandField.getText().trim(),
                             workDirectoryField.getText().trim());
  }
}
