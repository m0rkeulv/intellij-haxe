package com.intellij.plugins.haxe.v2.toolwindow.ui;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.plugins.haxe.HaxeBundle;
import com.intellij.plugins.haxe.util.ui.HaxeDialogHints;
import com.intellij.plugins.haxe.v2.buildtools.settings.HaxeCustomActionsStore.CustomAction;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;

/**
 * Add/edit dialog for a custom tool window action: a name and the command line to
 * run (executed with the active build file's directory as working directory).
 * The layout lives in the matching .form (labels bind their bundle keys there).
 */
public final class HaxeCustomActionDialog extends DialogWrapper {

  private JPanel panel;
  private JBTextField nameField;
  private JBTextField commandField;
  private JTextPane hintArea;

  /** The name/command pair being edited; action rows and tool rows share the dialog. */
  public record NameAndCommand(@NotNull String name, @NotNull String command) {
  }

  private final String nameRequiredKey;

  private HaxeCustomActionDialog(@NotNull Project project, @Nullable NameAndCommand initial,
                                 @NotNull String addTitleKey, @NotNull String editTitleKey,
                                 @Nullable String hintKey, @NotNull String nameRequiredKey) {
    super(project);
    this.nameRequiredKey = nameRequiredKey;
    setTitle(HaxeBundle.message(initial == null ? addTitleKey : editTitleKey));
    HaxeDialogHints.style(hintArea);
    if (hintKey != null) {
      hintArea.setText(HaxeBundle.message(hintKey));
    }
    if (initial != null) {
      nameField.setText(initial.name());
      commandField.setText(initial.command());
    }
    init();
  }

  /** A build file's custom action: the hint documents work directory and placeholder expansion. */
  @NotNull
  public static HaxeCustomActionDialog forAction(@NotNull Project project, @Nullable CustomAction initial) {
    NameAndCommand nameAndCommand = initial == null ? null : new NameAndCommand(initial.name(), initial.command());
    return new HaxeCustomActionDialog(project, nameAndCommand,
                                      "haxe.custom.action.dialog.add.title", "haxe.custom.action.dialog.edit.title",
                                      null, "haxe.custom.action.dialog.name.required");
  }

  /** A container's custom tool: no target placeholders, runs in the container root. */
  @NotNull
  public static HaxeCustomActionDialog forTool(@NotNull Project project, @Nullable NameAndCommand initial) {
    return new HaxeCustomActionDialog(project, initial,
                                      "haxe.custom.tool.dialog.add.title", "haxe.custom.tool.dialog.edit.title",
                                      "haxe.custom.tool.dialog.hint", "haxe.custom.tool.dialog.name.required");
  }

  @Override
  protected @NotNull JComponent createCenterPanel() {
    panel.setPreferredSize(JBUI.size(480, -1));
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
    if (StringUtil.isEmptyOrSpaces(commandField.getText())) {
      return new ValidationInfo(HaxeBundle.message("haxe.custom.action.dialog.command.required"), commandField);
    }
    return null;
  }

  @NotNull
  public CustomAction getAction() {
    return new CustomAction(nameField.getText().trim(), commandField.getText().trim());
  }

  /** The edited values, independent of which store they land in. */
  @NotNull
  public NameAndCommand getNameAndCommand() {
    CustomAction action = getAction();
    return new NameAndCommand(action.name(), action.command());
  }
}
