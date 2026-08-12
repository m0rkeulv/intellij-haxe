package com.intellij.plugins.haxe.v2.toolwindow.ui;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.plugins.haxe.HaxeBundle;
import com.intellij.plugins.haxe.v2.buildtools.settings.HaxeCustomActionsStore.CustomAction;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;

/**
 * Add/edit dialog for a custom tool window action: a name and the command line to
 * run (executed with the active build file's directory as working directory).
 */
public final class HaxeCustomActionDialog extends DialogWrapper {

  private final JBTextField nameField = new JBTextField();
  private final JBTextField commandField = new JBTextField();

  public HaxeCustomActionDialog(@NotNull Project project, @Nullable CustomAction initial) {
    super(project);
    setTitle(HaxeBundle.message(initial == null ? "haxe.custom.action.dialog.add.title"
                                                : "haxe.custom.action.dialog.edit.title"));
    if (initial != null) {
      nameField.setText(initial.name());
      commandField.setText(initial.command());
    }
    init();
  }

  @Override
  protected @NotNull JComponent createCenterPanel() {
    JPanel panel = FormBuilder.createFormBuilder()
      .addLabeledComponent(HaxeBundle.message("haxe.custom.action.dialog.name"), nameField)
      .addLabeledComponent(HaxeBundle.message("haxe.custom.action.dialog.command"), commandField)
      .addTooltip(HaxeBundle.message("haxe.custom.action.dialog.hint"))
      .getPanel();
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
      return new ValidationInfo(HaxeBundle.message("haxe.custom.action.dialog.name.required"), nameField);
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
}
