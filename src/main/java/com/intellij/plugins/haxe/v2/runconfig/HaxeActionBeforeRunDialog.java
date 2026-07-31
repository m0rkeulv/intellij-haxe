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
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.ui.dsl.listCellRenderer.BuilderKt;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import java.util.List;

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
  private final JBCheckBox injectDebugCheckBox =
    new JBCheckBox(HaxeDebuggerBundle.message("haxe.before.run.dialog.inject.debug"));
  private final JBLabel injectDebugPreview = new JBLabel("", UIUtil.ComponentStyle.SMALL, UIUtil.FontColor.BRIGHTER);

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
        refreshDebugPreview();
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
    injectDebugCheckBox.setSelected(task.isInjectDebugArguments());
    refreshDebugPreview();
    init();
  }

  @Override
  protected @Nullable JComponent createCenterPanel() {
    return FormBuilder.createFormBuilder()
      .addLabeledComponent(HaxeDebuggerBundle.message("haxe.before.run.dialog.build.file"), fileField)
      .addLabeledComponent(HaxeDebuggerBundle.message("haxe.before.run.dialog.action"), actionCombo)
      .addLabeledComponent(HaxeDebuggerBundle.message("haxe.before.run.dialog.arguments"), argumentsField)
      .addComponentToRightColumn(injectDebugCheckBox)
      .addComponentToRightColumn(injectDebugPreview)
      .getPanel();
  }

  @Override
  protected void doOKAction() {
    task.setBuildFilePath(fileField.getText().trim());
    Object action = actionCombo.getEditor().getItem();
    task.setActionName(action == null ? "" : action.toString().trim());
    task.setExtraArguments(argumentsField.getText().trim());
    task.setInjectDebugArguments(injectDebugCheckBox.isSelected());
    super.doOKAction();
  }

  /** Shows the exact arguments a Debug launch would append for the chosen file's target. */
  private void refreshDebugPreview() {
    String path = fileField.getText().trim();
    // additions derive from the file's type and selected target - content sniffing needs a read action
    List<String> additions = path.isEmpty() ? null
      : ReadAction.computeBlocking(() -> HaxeActionBeforeRunTaskProvider.debugAdditions(project, path));
    if (additions == null || additions.isEmpty()) {
      injectDebugPreview.setText(HaxeDebuggerBundle.message("haxe.before.run.dialog.inject.debug.none"));
      injectDebugCheckBox.setEnabled(false);
    }
    else {
      injectDebugPreview.setText(
        HaxeDebuggerBundle.message("haxe.before.run.dialog.inject.debug.preview", String.join(" ", additions)));
      injectDebugCheckBox.setEnabled(true);
    }
  }

  private void refillActionCombo(@Nullable String selectedAction) {
    String previous = selectedAction != null ? selectedAction
                                             : StringUtil.notNullize((String)actionCombo.getEditor().getItem());
    DefaultComboBoxModel<String> model = new DefaultComboBoxModel<>();
    VirtualFile file = LocalFileSystem.getInstance().findFileByPath(fileField.getText().trim());
    if (file != null && file.isValid()) {
      // type detection may sniff file content - EDT has no implicit read access
      ReadAction.computeBlocking(() -> HaxeCompileCommands.availableActionNames(project, file))
        .forEach(model::addElement);
    }
    if (!StringUtil.isEmptyOrSpaces(previous) && model.getIndexOf(previous) < 0) {
      model.addElement(previous);
    }
    actionCombo.setModel(model);
    actionCombo.setSelectedItem(StringUtil.isEmptyOrSpaces(previous) ? null : previous);
  }
}
