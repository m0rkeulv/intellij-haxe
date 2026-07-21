package com.intellij.plugins.haxe.runner.debugger.browser;

import com.intellij.application.options.ModulesComboBox;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.options.SettingsEditor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.plugins.haxe.HaxeDebuggerBundle;
import com.intellij.plugins.haxe.runner.debugger.HaxeRunConfigurationEditorUtil;
import com.intellij.plugins.haxe.runner.debugger.browser.BrowserRunConfiguration.BrowserFamily;
import com.intellij.ui.SimpleListCellRenderer;
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
 * field is live), optional browser/node executable overrides (unchecked =
 * the default installation / PATH), and the selected family's DAP adapter
 * status with an on-demand download button (the session would download it on
 * first use anyway; the button just does it ahead of time).
 */
public class BrowserRunConfigurationEditor extends SettingsEditor<BrowserRunConfiguration> {
  private final Project project;
  private final ModulesComboBox moduleCombo = new ModulesComboBox();
  private final ComboBox<BrowserFamily> familyCombo = new ComboBox<>(BrowserFamily.values());
  private final JBCheckBox serveContentCheckBox =
    new JBCheckBox(HaxeDebuggerBundle.message("browser.runner.editor.serve"));
  private final TextFieldWithBrowseButton contentRootField = new TextFieldWithBrowseButton();
  private final JBTextField urlField = new JBTextField();
  private final JBCheckBox overrideBrowserCheckBox =
    new JBCheckBox(HaxeDebuggerBundle.message("browser.runner.editor.override.browser"));
  private final TextFieldWithBrowseButton browserExecutableField = new TextFieldWithBrowseButton();
  private final JBCheckBox overrideNodeCheckBox =
    new JBCheckBox(HaxeDebuggerBundle.message("browser.runner.editor.override.node"));
  private final TextFieldWithBrowseButton nodePathField = new TextFieldWithBrowseButton();
  private final JBLabel adapterStatusLabel = new JBLabel();
  /** Icon-only link revealing the adapter store directory in the system file manager. */
  private final com.intellij.ui.components.ActionLink openStoreLink =
    new com.intellij.ui.components.ActionLink("", (java.awt.event.ActionListener)e -> revealAdapterDirectory());
  /** Text link fetching the pinned adapter ahead of the first session's on-demand download. */
  private final com.intellij.ui.components.ActionLink downloadLink =
    new com.intellij.ui.components.ActionLink(HaxeDebuggerBundle.message("browser.runner.adapter.download"),
                                              (java.awt.event.ActionListener)e -> downloadAdapter());
  private final JPanel panel;

  public BrowserRunConfigurationEditor(Project project) {
    this.project = project;
    HaxeRunConfigurationEditorUtil.browseInto(project, contentRootField,
                                              FileChooserDescriptorFactory.createSingleFolderDescriptor());
    HaxeRunConfigurationEditorUtil.browseInto(project, browserExecutableField,
                                              FileChooserDescriptorFactory.createSingleFileDescriptor());
    HaxeRunConfigurationEditorUtil.browseInto(project, nodePathField,
                                              FileChooserDescriptorFactory.createSingleFileDescriptor());
    // enum names are SHOUTY; render them as ordinary names
    familyCombo.setRenderer(SimpleListCellRenderer.create(
      "", value -> value == BrowserFamily.FIREFOX ? "Firefox" : "Chromium"));
    serveContentCheckBox.addActionListener(e -> updateContentModeEnablement());
    overrideBrowserCheckBox.addActionListener(e -> updateOverrideEnablement());
    overrideNodeCheckBox.addActionListener(e -> updateOverrideEnablement());
    familyCombo.addActionListener(e -> updateAdapterStatus());
    // secondary information, styled like the platform's context help text
    adapterStatusLabel.setForeground(UIUtil.getContextHelpForeground());
    openStoreLink.setIcon(com.intellij.icons.AllIcons.General.OpenDisk);
    openStoreLink.setToolTipText(com.intellij.ide.actions.RevealFileAction.getActionName());

    JPanel adapterRow = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 0));
    adapterRow.add(adapterStatusLabel);
    adapterRow.add(downloadLink);
    adapterRow.add(openStoreLink);

    urlField.setColumns(25); // preferred width from columns, not content
    JComponent hint = HaxeRunConfigurationEditorUtil.hint(HaxeDebuggerBundle.message("browser.runner.editor.hint"));
    panel = FormBuilder.createFormBuilder()
      .addLabeledComponent(HaxeDebuggerBundle.message("browser.runner.editor.module"), moduleCombo)
      .addLabeledComponent(HaxeDebuggerBundle.message("browser.runner.editor.family"), familyCombo)
      .addComponent(adapterRow)
      .addComponent(serveContentCheckBox)
      .addLabeledComponent(HaxeDebuggerBundle.message("browser.runner.editor.content.root"), contentRootField)
      .addLabeledComponent(HaxeDebuggerBundle.message("browser.runner.editor.url"), urlField)
      .addLabeledComponent(overrideBrowserCheckBox, browserExecutableField)
      .addLabeledComponent(overrideNodeCheckBox, nodePathField)
      .addComponent(hint)
      .addComponentFillVertically(new JPanel(), 0)
      .getPanel();
  }

  private void updateContentModeEnablement() {
    boolean serve = serveContentCheckBox.isSelected();
    contentRootField.setEnabled(serve);
    urlField.setEnabled(!serve);
  }

  private void updateOverrideEnablement() {
    browserExecutableField.setEnabled(overrideBrowserCheckBox.isSelected());
    nodePathField.setEnabled(overrideNodeCheckBox.isSelected());
  }

  // --- the selected family's DAP adapter: downloaded state + version ---

  private AdapterPin selectedPin() {
    return familyCombo.getSelectedItem() == BrowserFamily.CHROMIUM ? AdapterPin.JS_DEBUG : AdapterPin.FIREFOX;
  }

  private String selectedAdapterName() {
    return HaxeDebuggerBundle.message(familyCombo.getSelectedItem() == BrowserFamily.CHROMIUM
                                      ? "browser.runner.adapter.name.chromium"
                                      : "browser.runner.adapter.name.firefox");
  }

  private void updateAdapterStatus() {
    AdapterPin pin = selectedPin();
    String name = selectedAdapterName() + " " + pin.version();
    boolean installed = new AdapterStore(BrowserDebugBackend.adapterStoreRoot()).isInstalled(pin);
    adapterStatusLabel.setText(installed
                               ? HaxeDebuggerBundle.message("browser.runner.adapter.downloaded", name)
                               : name + " —");
    openStoreLink.setVisible(installed);
    downloadLink.setVisible(!installed);
    downloadLink.setEnabled(true);
  }

  private void revealAdapterDirectory() {
    AdapterPin pin = selectedPin();
    com.intellij.ide.actions.RevealFileAction.openDirectory(
      BrowserDebugBackend.adapterStoreRoot().resolve(pin.id()).resolve(pin.version()).toFile());
  }

  private void downloadAdapter() {
    AdapterPin pin = selectedPin();
    downloadLink.setVisible(false);
    adapterStatusLabel.setText(HaxeDebuggerBundle.message(
      "browser.runner.adapter.downloading", selectedAdapterName() + " " + pin.version()));
    ApplicationManager.getApplication().executeOnPooledThread(() -> {
      String failure;
      try {
        new AdapterStore(BrowserDebugBackend.adapterStoreRoot()).resolveEntry(pin, null);
        failure = null;
      } catch (Exception e) {
        failure = e.getMessage();
      }
      String failureText = failure;
      // the editor lives in a MODAL dialog: without its modality state the
      // runnable would only run after the dialog closes and the status text
      // would never appear to update
      ApplicationManager.getApplication().invokeLater(() -> {
        if (failureText != null) {
          adapterStatusLabel.setText(HaxeDebuggerBundle.message("browser.runner.adapter.failed", failureText));
          downloadLink.setVisible(true);
        } else {
          updateAdapterStatus();
        }
      }, com.intellij.openapi.application.ModalityState.stateForComponent(adapterStatusLabel));
    });
  }

  @Override
  protected void resetEditorFrom(@NotNull BrowserRunConfiguration configuration) {
    moduleCombo.fillModules(project);
    moduleCombo.setSelectedModule(configuration.getConfigurationModule().getModule());
    familyCombo.setSelectedItem(configuration.getBrowserFamily());
    serveContentCheckBox.setSelected(configuration.isServeContent());
    contentRootField.setText(FileUtil.toSystemDependentName(configuration.getContentRoot()));
    urlField.setText(configuration.getUrl());
    overrideBrowserCheckBox.setSelected(!configuration.getBrowserExecutablePath().isBlank());
    browserExecutableField.setText(FileUtil.toSystemDependentName(configuration.getBrowserExecutablePath()));
    overrideNodeCheckBox.setSelected(!configuration.getNodePath().isBlank());
    nodePathField.setText(FileUtil.toSystemDependentName(configuration.getNodePath()));
    updateContentModeEnablement();
    updateOverrideEnablement();
    updateAdapterStatus();
  }

  @Override
  protected void applyEditorTo(@NotNull BrowserRunConfiguration configuration) {
    configuration.setModule(moduleCombo.getSelectedModule());
    configuration.setBrowserFamily((BrowserFamily)familyCombo.getSelectedItem());
    configuration.setServeContent(serveContentCheckBox.isSelected());
    configuration.setContentRoot(FileUtil.toSystemIndependentName(contentRootField.getText().trim()));
    configuration.setUrl(urlField.getText().trim());
    // an unchecked override means "use the default", regardless of field text
    configuration.setBrowserExecutablePath(overrideBrowserCheckBox.isSelected()
                                           ? FileUtil.toSystemIndependentName(browserExecutableField.getText().trim())
                                           : "");
    configuration.setNodePath(overrideNodeCheckBox.isSelected()
                              ? FileUtil.toSystemIndependentName(nodePathField.getText().trim())
                              : "");
  }

  @Override
  protected @NotNull JComponent createEditor() {
    return panel;
  }
}
