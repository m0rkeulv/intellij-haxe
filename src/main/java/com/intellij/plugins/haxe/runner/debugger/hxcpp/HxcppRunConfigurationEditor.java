package com.intellij.plugins.haxe.runner.debugger.hxcpp;

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
 * Settings UI for an HXCPP run configuration: module, compiled executable,
 * working directory, program arguments, and the debug host/port (prefilled
 * with the protocol defaults; only relevant when the build overrides
 * HXCPP_DEBUG_HOST/HXCPP_DEBUG_PORT).
 */
public class HxcppRunConfigurationEditor extends SettingsEditor<HxcppRunConfiguration> {
  private final Project project;
  private final ModulesComboBox moduleCombo = new ModulesComboBox();
  private final TextFieldWithBrowseButton executableField = new TextFieldWithBrowseButton();
  private final TextFieldWithBrowseButton workingDirectoryField = new TextFieldWithBrowseButton();
  private final JBTextField programArgumentsField = new JBTextField();
  private final JBTextField debugHostField = new JBTextField(HxcppRunConfiguration.DEFAULT_DEBUG_HOST);
  private final JBTextField debugPortField = new JBTextField(Integer.toString(HxcppRunConfiguration.DEFAULT_DEBUG_PORT));
  private final JPanel panel;

  public HxcppRunConfigurationEditor(Project project) {
    this.project = project;
    HaxeRunConfigurationEditorUtil.browseInto(project, executableField,
                                              FileChooserDescriptorFactory.createSingleFileDescriptor());
    HaxeRunConfigurationEditorUtil.browseInto(project, workingDirectoryField,
                                              FileChooserDescriptorFactory.createSingleFolderDescriptor());
    JBLabel debugHint = new JBLabel(HaxeBundle.message("hxcpp.runner.debug.hint"));
    debugHint.setComponentStyle(UIUtil.ComponentStyle.SMALL);
    debugHint.setForeground(UIUtil.getContextHelpForeground());
    panel = FormBuilder.createFormBuilder()
      .addLabeledComponent(HaxeBundle.message("hxcpp.runner.editor.module"), moduleCombo)
      .addLabeledComponent(HaxeBundle.message("hxcpp.runner.editor.executable"), executableField)
      .addLabeledComponent(HaxeBundle.message("hxcpp.runner.editor.working.directory"), workingDirectoryField)
      .addLabeledComponent(HaxeBundle.message("hxcpp.runner.editor.program.arguments"), programArgumentsField)
      .addLabeledComponent(HaxeBundle.message("hxcpp.runner.editor.debug.host"), debugHostField)
      .addLabeledComponent(HaxeBundle.message("hxcpp.runner.editor.debug.port"), debugPortField)
      .addComponent(debugHint)
      .addComponentFillVertically(new JPanel(), 0)
      .getPanel();
  }

  @Override
  protected void resetEditorFrom(@NotNull HxcppRunConfiguration configuration) {
    moduleCombo.fillModules(project);
    moduleCombo.setSelectedModule(configuration.getConfigurationModule().getModule());
    executableField.setText(FileUtil.toSystemDependentName(configuration.getExecutablePath()));
    workingDirectoryField.setText(FileUtil.toSystemDependentName(configuration.getWorkingDirectory()));
    programArgumentsField.setText(configuration.getProgramArguments());
    debugHostField.setText(configuration.getDebugHost());
    debugPortField.setText(configuration.getDebugPort());
  }

  @Override
  protected void applyEditorTo(@NotNull HxcppRunConfiguration configuration) {
    configuration.setModule(moduleCombo.getSelectedModule());
    configuration.setExecutablePath(FileUtil.toSystemIndependentName(executableField.getText().trim()));
    configuration.setWorkingDirectory(FileUtil.toSystemIndependentName(workingDirectoryField.getText().trim()));
    configuration.setProgramArguments(programArgumentsField.getText().trim());
    configuration.setDebugHost(debugHostField.getText());
    configuration.setDebugPort(debugPortField.getText());
  }

  @Override
  protected @NotNull JComponent createEditor() {
    return panel;
  }
}
