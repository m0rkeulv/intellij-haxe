package com.intellij.plugins.haxe.v2.runconfig;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.plugins.haxe.HaxeDebuggerBundle;
import com.intellij.plugins.haxe.v2.buildtools.HaxeCompileCommands;
import com.intellij.plugins.haxe.v2.runconfig.HaxeActionBeforeRunTaskProvider.Task;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.ui.dsl.listCellRenderer.BuilderKt;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.event.DocumentEvent;

/**
 * Configures a "Run Haxe action" before-launch step: the build file, the action to
 * run and extra arguments. Applies to the task on OK.
 */
final class HaxeActionBeforeRunDialog extends DialogWrapper {

  private final Project project;
  private final Task task;
  private final TextFieldWithBrowseButton fileField = new TextFieldWithBrowseButton();
  private final ComboBox<String> actionCombo = new ComboBox<>();
  private final JBTextField argumentsField = new JBTextField();

  HaxeActionBeforeRunDialog(@NotNull Project project, @NotNull Task task) {
    super(project);
    this.project = project;
    this.task = task;
    setTitle(HaxeDebuggerBundle.message("haxe.before.run.dialog.title"));

    FileChooserDescriptor descriptor = FileChooserDescriptorFactory.singleFile()
      .withTitle(HaxeDebuggerBundle.message("haxe.before.run.dialog.build.file.chooser"));
    fileField.addBrowseFolderListener(project, descriptor);
    fileField.getTextField().getDocument().addDocumentListener(new DocumentAdapter() {
      @Override
      protected void textChanged(@NotNull DocumentEvent e) {
        refillActionCombo(null);
      }
    });

    actionCombo.setRenderer(BuilderKt.textListCellRenderer("", name -> name));
    actionCombo.setEditable(true);

    // build file paths are long - default the dialog to a comfortable width
    // (columns only affect the preferred size, so it stays freely resizable)
    fileField.getTextField().setColumns(45);

    fileField.setText(task.getBuildFilePath());
    refillActionCombo(task.getActionName());
    argumentsField.setText(task.getExtraArguments());
    init();
  }

  @Override
  protected @Nullable JComponent createCenterPanel() {
    JBLabel debugHint = new JBLabel(HaxeDebuggerBundle.message("haxe.before.run.dialog.debug.hint"),
                                    UIUtil.ComponentStyle.SMALL, UIUtil.FontColor.BRIGHTER);
    return FormBuilder.createFormBuilder()
      .addLabeledComponent(HaxeDebuggerBundle.message("haxe.before.run.dialog.build.file"), fileField)
      .addLabeledComponent(HaxeDebuggerBundle.message("haxe.before.run.dialog.action"), actionCombo)
      .addLabeledComponent(HaxeDebuggerBundle.message("haxe.before.run.dialog.arguments"), argumentsField)
      .addComponentToRightColumn(debugHint)
      .getPanel();
  }

  @Override
  protected void doOKAction() {
    task.setBuildFilePath(fileField.getText().trim());
    Object action = actionCombo.getEditor().getItem();
    task.setActionName(action == null ? "" : action.toString().trim());
    task.setExtraArguments(argumentsField.getText().trim());
    super.doOKAction();
  }

  private void refillActionCombo(@Nullable String selectedAction) {
    String previous = selectedAction != null ? selectedAction
                                             : StringUtil.notNullize((String)actionCombo.getEditor().getItem());
    DefaultComboBoxModel<String> model = new DefaultComboBoxModel<>();
    VirtualFile file = LocalFileSystem.getInstance().findFileByPath(fileField.getText().trim());
    if (file != null && file.isValid()) {
      // type detection may sniff file content - EDT has no implicit read access
      ReadAction.compute(() -> HaxeCompileCommands.availableActionNames(project, file))
        .forEach(model::addElement);
    }
    if (!StringUtil.isEmptyOrSpaces(previous) && model.getIndexOf(previous) < 0) {
      model.addElement(previous);
    }
    actionCombo.setModel(model);
    actionCombo.setSelectedItem(StringUtil.isEmptyOrSpaces(previous) ? null : previous);
  }
}
