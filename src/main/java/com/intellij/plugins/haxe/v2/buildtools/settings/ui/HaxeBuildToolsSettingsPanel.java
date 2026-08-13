package com.intellij.plugins.haxe.v2.buildtools.settings.ui;

import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.TextBrowseFolderListener;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.plugins.haxe.HaxeBundle;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Swing panel for Settings | Build Tools | Haxe: SDK selection and paths to the
 * Neko, HashLink and haxelib executables. Free of project service access; the
 * configurable supplies the available SDK names and reads the edited values back.
 */
public final class HaxeBuildToolsSettingsPanel {

  private final ComboBox<String> sdkCombo = new ComboBox<>();
  private final TextFieldWithBrowseButton nekoField = createExecutableField("haxe.build.tools.neko.chooser.title");
  private final TextFieldWithBrowseButton hashlinkField = createExecutableField("haxe.build.tools.hashlink.chooser.title");
  private final TextFieldWithBrowseButton haxelibField = createExecutableField("haxe.build.tools.haxelib.chooser.title");
  private final JBCheckBox serverEnabledCheckBox = new JBCheckBox(HaxeBundle.message("haxe.build.tools.server.enabled"));
  private final JBTextField serverPortField = new JBTextField();
  private final JBTextField serverArgumentsField = new JBTextField();
  private final JBCheckBox liveTestReportingCheckBox =
    new JBCheckBox(HaxeBundle.message("haxe.build.tools.live.test.reporting"));
  private final JPanel mainPanel;

  private final Set<String> knownSdkNames = new LinkedHashSet<>();

  public HaxeBuildToolsSettingsPanel() {
    sdkCombo.setRenderer(HaxeSdkComboRenderer.sdkComboRenderer(knownSdkNames));

    serverEnabledCheckBox.addActionListener(e -> updateServerFieldsEnabled());

    mainPanel = FormBuilder.createFormBuilder()
      .addLabeledComponent(HaxeBundle.message("haxe.build.tools.sdk"), sdkCombo)
      .addSeparator(8)
      .addLabeledComponent(HaxeBundle.message("haxe.build.tools.haxelib.path"), haxelibField)
      .addLabeledComponent(HaxeBundle.message("haxe.build.tools.neko.path"), nekoField)
      .addLabeledComponent(HaxeBundle.message("haxe.build.tools.hashlink.path"), hashlinkField)
      .addTooltip(HaxeBundle.message("haxe.build.tools.path.hint"))
      .addSeparator(8)
      .addComponent(serverEnabledCheckBox)
      .addLabeledComponent(HaxeBundle.message("haxe.build.tools.server.port"), serverPortField)
      .addLabeledComponent(HaxeBundle.message("haxe.build.tools.server.arguments"), serverArgumentsField)
      .addTooltip(HaxeBundle.message("haxe.build.tools.server.hint"))
      .addSeparator(8)
      .addComponent(liveTestReportingCheckBox)
      .addTooltip(HaxeBundle.message("haxe.build.tools.live.test.reporting.hint"))
      .getPanel();
  }

  private void updateServerFieldsEnabled() {
    serverPortField.setEnabled(serverEnabledCheckBox.isSelected());
    serverArgumentsField.setEnabled(serverEnabledCheckBox.isSelected());
  }

  @NotNull
  public JComponent getComponent() {
    return mainPanel;
  }

  public void resetServerFields(boolean enabled, int port, @NotNull String arguments) {
    serverEnabledCheckBox.setSelected(enabled);
    serverPortField.setText(port > 0 ? String.valueOf(port) : "");
    serverArgumentsField.setText(arguments);
    updateServerFieldsEnabled();
  }

  public void resetLiveTestReporting(boolean enabled) {
    liveTestReportingCheckBox.setSelected(enabled);
  }

  public boolean isLiveTestReporting() {
    return liveTestReportingCheckBox.isSelected();
  }

  public boolean isServerEnabled() {
    return serverEnabledCheckBox.isSelected();
  }

  /** 0 when blank or unparsable (= pick a free port automatically). */
  public int getServerPort() {
    try {
      return Math.max(Integer.parseInt(serverPortField.getText().trim()), 0);
    }
    catch (NumberFormatException e) {
      return 0;
    }
  }

  @NotNull
  public String getServerArguments() {
    return serverArgumentsField.getText().trim();
  }

  public void reset(@NotNull Set<String> availableSdkNames,
                    @Nullable String selectedSdkName,
                    @NotNull String haxelibPath,
                    @NotNull String nekoPath,
                    @NotNull String hashlinkPath) {
    knownSdkNames.clear();
    knownSdkNames.addAll(availableSdkNames);

    DefaultComboBoxModel<String> model = new DefaultComboBoxModel<>();
    model.addElement(null);
    availableSdkNames.forEach(model::addElement);
    // Keep a stored-but-missing SDK selectable so applying without touching it does not silently drop it.
    if (selectedSdkName != null && !availableSdkNames.contains(selectedSdkName)) {
      model.addElement(selectedSdkName);
    }
    sdkCombo.setModel(model);
    sdkCombo.setSelectedItem(selectedSdkName);

    haxelibField.setText(haxelibPath);
    nekoField.setText(nekoPath);
    hashlinkField.setText(hashlinkPath);
  }

  @Nullable
  public String getSelectedSdkName() {
    return (String)sdkCombo.getSelectedItem();
  }

  @NotNull
  public String getHaxelibPath() {
    return haxelibField.getText().trim();
  }

  @NotNull
  public String getNekoPath() {
    return nekoField.getText().trim();
  }

  @NotNull
  public String getHashlinkPath() {
    return hashlinkField.getText().trim();
  }

  @NotNull
  private static TextFieldWithBrowseButton createExecutableField(@NotNull String titleKey) {
    TextFieldWithBrowseButton field = new TextFieldWithBrowseButton();
    FileChooserDescriptor descriptor = FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor()
      .withTitle(HaxeBundle.message(titleKey));
    field.addBrowseFolderListener(new TextBrowseFolderListener(descriptor));
    return field;
  }
}
