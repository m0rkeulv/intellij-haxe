package com.intellij.plugins.haxe.runner.debugger.hxcpp.intellij;

import com.intellij.application.options.ModulesComboBox;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.options.SettingsEditor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.plugins.haxe.HaxeBundle;
import com.intellij.plugins.haxe.runner.debugger.HaxeRunConfigurationEditorUtil;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.UIUtil;
import javax.swing.JComponent;
import javax.swing.JPanel;
import org.jetbrains.annotations.NotNull;

/**
 * Settings UI for an HXCPP (IntelliJ debug server) run configuration: module,
 * compiled executable, working directory and program arguments. Deliberately
 * minimal — the debug connection is fully automatic (ephemeral port via env
 * vars), so there is nothing network-ish to configure.
 */
public class HxcppIntellijRunConfigurationEditor extends SettingsEditor<HxcppIntellijRunConfiguration> {
  private final Project project;
  private final ModulesComboBox moduleCombo = new ModulesComboBox();
  private final TextFieldWithBrowseButton executableField = new TextFieldWithBrowseButton();
  private final TextFieldWithBrowseButton workingDirectoryField = new TextFieldWithBrowseButton();
  private final JBTextField programArgumentsField = new JBTextField();
  private final JPanel panel;

  public HxcppIntellijRunConfigurationEditor(Project project) {
    this.project = project;
    HaxeRunConfigurationEditorUtil.browseInto(project, executableField,
                                              FileChooserDescriptorFactory.createSingleFileDescriptor());
    HaxeRunConfigurationEditorUtil.browseInto(project, workingDirectoryField,
                                              FileChooserDescriptorFactory.createSingleFolderDescriptor());
    JBLabel debugHint = new JBLabel(HaxeBundle.message("hxcpp.intellij.runner.debug.hint"));
    debugHint.setComponentStyle(UIUtil.ComponentStyle.SMALL);
    debugHint.setForeground(UIUtil.getContextHelpForeground());
    JBLabel workingDirectoryHint = new JBLabel(HaxeBundle.message("hxcpp.intellij.runner.editor.working.directory.hint"));
    workingDirectoryHint.setComponentStyle(UIUtil.ComponentStyle.SMALL);
    workingDirectoryHint.setForeground(UIUtil.getContextHelpForeground());
    panel = FormBuilder.createFormBuilder()
      .addLabeledComponent(HaxeBundle.message("hxcpp.intellij.runner.editor.module"), moduleCombo)
      .addLabeledComponent(HaxeBundle.message("hxcpp.intellij.runner.editor.executable"), executableField)
      .addLabeledComponent(HaxeBundle.message("hxcpp.intellij.runner.editor.working.directory"), workingDirectoryField)
      .addComponentToRightColumn(workingDirectoryHint)
      .addLabeledComponent(HaxeBundle.message("hxcpp.intellij.runner.editor.program.arguments"), programArgumentsField)
      .addComponent(debugHint)
      .addComponentFillVertically(new JPanel(), 0)
      .getPanel();
  }

  @Override
  protected void resetEditorFrom(@NotNull HxcppIntellijRunConfiguration configuration) {
    moduleCombo.fillModules(project);
    moduleCombo.setSelectedModule(configuration.getConfigurationModule().getModule());
    executableField.setText(FileUtil.toSystemDependentName(configuration.getExecutablePath()));
    workingDirectoryField.setText(FileUtil.toSystemDependentName(configuration.getWorkingDirectory()));
    programArgumentsField.setText(configuration.getProgramArguments());
  }

  @Override
  protected void applyEditorTo(@NotNull HxcppIntellijRunConfiguration configuration) {
    configuration.setModule(moduleCombo.getSelectedModule());
    configuration.setExecutablePath(FileUtil.toSystemIndependentName(executableField.getText().trim()));
    configuration.setWorkingDirectory(FileUtil.toSystemIndependentName(workingDirectoryField.getText().trim()));
    configuration.setProgramArguments(programArgumentsField.getText().trim());
  }

  @Override
  protected @NotNull JComponent createEditor() {
    return panel;
  }
}
