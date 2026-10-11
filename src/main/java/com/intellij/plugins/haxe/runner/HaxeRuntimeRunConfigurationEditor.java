package com.intellij.plugins.haxe.runner;

import com.intellij.application.options.ModulesComboBox;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.plugins.haxe.HaxeDebuggerBundle;
import com.intellij.plugins.haxe.runner.debugger.dap.ide.DapExecutableRunConfigurationEditorBase;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import org.jetbrains.annotations.NotNull;

import javax.swing.JComponent;
import javax.swing.JPanel;

/** Settings UI for a runtime configuration: the shared executable fields, with the runtime's own executable hint. */
public class HaxeRuntimeRunConfigurationEditor extends DapExecutableRunConfigurationEditorBase<HaxeRuntimeRunConfiguration> {

  private final ModulesComboBox moduleCombo = new ModulesComboBox();
  private final TextFieldWithBrowseButton executableField = new TextFieldWithBrowseButton();
  private final TextFieldWithBrowseButton workingDirectoryField = new TextFieldWithBrowseButton();
  private final JBTextField programArgumentsField = new JBTextField();
  private final JPanel panel;

  public HaxeRuntimeRunConfigurationEditor(@NotNull Project project, @NotNull String executableHint) {
    super(project);
    wireCommonChoosers();
    panel = FormBuilder.createFormBuilder()
      .addLabeledComponent(HaxeDebuggerBundle.message("runtime.runner.editor.module"), moduleCombo)
      .addLabeledComponent(HaxeDebuggerBundle.message("runtime.runner.editor.executable"), executableField)
      .addTooltip(executableHint)
      .addLabeledComponent(HaxeDebuggerBundle.message("runtime.runner.editor.working.directory"), workingDirectoryField)
      .addLabeledComponent(HaxeDebuggerBundle.message("runtime.runner.editor.program.arguments"), programArgumentsField)
      .getPanel();
  }

  @Override
  protected ModulesComboBox moduleCombo() {
    return moduleCombo;
  }

  @Override
  protected TextFieldWithBrowseButton executableField() {
    return executableField;
  }

  @Override
  protected TextFieldWithBrowseButton workingDirectoryField() {
    return workingDirectoryField;
  }

  @Override
  protected JBTextField programArgumentsField() {
    return programArgumentsField;
  }

  @Override
  protected void resetEditorFrom(@NotNull HaxeRuntimeRunConfiguration configuration) {
    resetCommon(configuration);
  }

  @Override
  protected void applyEditorTo(@NotNull HaxeRuntimeRunConfiguration configuration) {
    applyCommon(configuration);
  }

  @Override
  protected @NotNull JComponent createEditor() {
    return panel;
  }
}
