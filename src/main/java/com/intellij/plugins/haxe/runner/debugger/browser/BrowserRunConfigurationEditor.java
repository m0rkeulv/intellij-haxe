package com.intellij.plugins.haxe.runner.debugger.browser;

import com.intellij.application.options.ModulesComboBox;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.options.SettingsEditor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.plugins.haxe.HaxeDebuggerBundle;
import com.intellij.plugins.haxe.runner.debugger.HaxeRunConfigurationEditorUtil;
import com.intellij.plugins.haxe.runner.debugger.browser.BrowserRunConfiguration.BrowserFamily;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.UIUtil;
import javax.swing.JComponent;
import javax.swing.JPanel;
import org.jetbrains.annotations.NotNull;

/**
 * Settings UI for the browser debug configuration: module, browser family
 * (Firefox / Chromium), the serve-vs-url content mode (checkbox toggles which
 * field is live), and the optional browser/node executable overrides.
 */
public class BrowserRunConfigurationEditor extends SettingsEditor<BrowserRunConfiguration> {
  private final Project project;
  private final ModulesComboBox moduleCombo = new ModulesComboBox();
  private final ComboBox<BrowserFamily> familyCombo = new ComboBox<>(BrowserFamily.values());
  private final JBCheckBox serveContentCheckBox =
    new JBCheckBox(HaxeDebuggerBundle.message("browser.runner.editor.serve"));
  private final TextFieldWithBrowseButton contentRootField = new TextFieldWithBrowseButton();
  private final JBTextField urlField = new JBTextField();
  private final TextFieldWithBrowseButton browserExecutableField = new TextFieldWithBrowseButton();
  private final TextFieldWithBrowseButton nodePathField = new TextFieldWithBrowseButton();
  private final JPanel panel;

  public BrowserRunConfigurationEditor(Project project) {
    this.project = project;
    HaxeRunConfigurationEditorUtil.browseInto(project, contentRootField,
                                              FileChooserDescriptorFactory.createSingleFolderDescriptor());
    HaxeRunConfigurationEditorUtil.browseInto(project, browserExecutableField,
                                              FileChooserDescriptorFactory.createSingleFileDescriptor());
    HaxeRunConfigurationEditorUtil.browseInto(project, nodePathField,
                                              FileChooserDescriptorFactory.createSingleFileDescriptor());
    serveContentCheckBox.addActionListener(e -> updateContentModeEnablement());

    JBLabel hint = new JBLabel(HaxeDebuggerBundle.message("browser.runner.editor.hint"));
    hint.setComponentStyle(UIUtil.ComponentStyle.SMALL);
    hint.setForeground(UIUtil.getContextHelpForeground());
    panel = FormBuilder.createFormBuilder()
      .addLabeledComponent(HaxeDebuggerBundle.message("browser.runner.editor.module"), moduleCombo)
      .addLabeledComponent(HaxeDebuggerBundle.message("browser.runner.editor.family"), familyCombo)
      .addComponent(serveContentCheckBox)
      .addLabeledComponent(HaxeDebuggerBundle.message("browser.runner.editor.content.root"), contentRootField)
      .addLabeledComponent(HaxeDebuggerBundle.message("browser.runner.editor.url"), urlField)
      .addLabeledComponent(HaxeDebuggerBundle.message("browser.runner.editor.browser.executable"), browserExecutableField)
      .addLabeledComponent(HaxeDebuggerBundle.message("browser.runner.editor.node"), nodePathField)
      .addComponent(hint)
      .addComponentFillVertically(new JPanel(), 0)
      .getPanel();
  }

  private void updateContentModeEnablement() {
    boolean serve = serveContentCheckBox.isSelected();
    contentRootField.setEnabled(serve);
    urlField.setEnabled(!serve);
  }

  @Override
  protected void resetEditorFrom(@NotNull BrowserRunConfiguration configuration) {
    moduleCombo.fillModules(project);
    moduleCombo.setSelectedModule(configuration.getConfigurationModule().getModule());
    familyCombo.setSelectedItem(configuration.getBrowserFamily());
    serveContentCheckBox.setSelected(configuration.isServeContent());
    contentRootField.setText(FileUtil.toSystemDependentName(configuration.getContentRoot()));
    urlField.setText(configuration.getUrl());
    browserExecutableField.setText(FileUtil.toSystemDependentName(configuration.getBrowserExecutablePath()));
    nodePathField.setText(FileUtil.toSystemDependentName(configuration.getNodePath()));
    updateContentModeEnablement();
  }

  @Override
  protected void applyEditorTo(@NotNull BrowserRunConfiguration configuration) {
    configuration.setModule(moduleCombo.getSelectedModule());
    configuration.setBrowserFamily((BrowserFamily)familyCombo.getSelectedItem());
    configuration.setServeContent(serveContentCheckBox.isSelected());
    configuration.setContentRoot(FileUtil.toSystemIndependentName(contentRootField.getText().trim()));
    configuration.setUrl(urlField.getText().trim());
    configuration.setBrowserExecutablePath(FileUtil.toSystemIndependentName(browserExecutableField.getText().trim()));
    configuration.setNodePath(FileUtil.toSystemIndependentName(nodePathField.getText().trim()));
  }

  @Override
  protected @NotNull JComponent createEditor() {
    return panel;
  }
}
