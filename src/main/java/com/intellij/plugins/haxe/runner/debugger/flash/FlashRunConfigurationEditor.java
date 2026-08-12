package com.intellij.plugins.haxe.runner.debugger.flash;

import com.intellij.application.options.ModulesComboBox;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.options.SettingsEditor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.ProjectJdkTable;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.plugins.haxe.HaxeDebuggerBundle;
import com.intellij.ui.dsl.listCellRenderer.BuilderKt;
import com.intellij.util.ui.FormBuilder;
import java.util.Locale;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JComponent;
import org.jetbrains.annotations.NotNull;

/**
 * Editor for {@link FlashRunConfiguration}: module, the swf to launch, the
 * Flex SDK driving the debugger and the player executable used by plain Run.
 */
public final class FlashRunConfigurationEditor extends SettingsEditor<FlashRunConfiguration> {

  private final ModulesComboBox moduleCombo = new ModulesComboBox();
  private final TextFieldWithBrowseButton swfField = new TextFieldWithBrowseButton();
  private final ComboBox<String> flexSdkCombo = new ComboBox<>();
  private final TextFieldWithBrowseButton playerField = new TextFieldWithBrowseButton();

  public FlashRunConfigurationEditor(@NotNull Project project) {
    FileChooserDescriptor swfDescriptor = FileChooserDescriptorFactory.singleFile()
      .withTitle(HaxeDebuggerBundle.message("flash.runner.editor.swf.chooser"))
      .withFileFilter(file -> "swf".equalsIgnoreCase(StringUtil.notNullize(file.getExtension())));
    swfField.addBrowseFolderListener(project, swfDescriptor);
    playerField.addBrowseFolderListener(project, FileChooserDescriptorFactory.singleFile()
      .withTitle(HaxeDebuggerBundle.message("flash.runner.editor.player.chooser")));

    flexSdkCombo.setRenderer(BuilderKt.textListCellRenderer("", name -> name));
    flexSdkCombo.setEditable(true);
  }

  @Override
  protected void resetEditorFrom(@NotNull FlashRunConfiguration configuration) {
    moduleCombo.fillModules(configuration.getProject());
    moduleCombo.setSelectedModule(configuration.getConfigurationModule().getModule());
    swfField.setText(configuration.getSwfFilePath());
    playerField.setText(configuration.getFlashPlayerPath());

    DefaultComboBoxModel<String> sdkModel = new DefaultComboBoxModel<>();
    for (Sdk sdk : ProjectJdkTable.getInstance().getAllJdks()) {
      // the flex SDK type comes from the optional Flash/Flex plugin - match by
      // type name instead of a compile-time class reference
      if (sdk.getSdkType().getName().toLowerCase(Locale.ROOT).contains("flex")) {
        sdkModel.addElement(sdk.getName());
      }
    }
    String configured = configuration.getFlexSdkName();
    if (!configured.isEmpty() && sdkModel.getIndexOf(configured) < 0) {
      sdkModel.addElement(configured);
    }
    flexSdkCombo.setModel(sdkModel);
    flexSdkCombo.setSelectedItem(configured.isEmpty() ? null : configured);
  }

  @Override
  protected void applyEditorTo(@NotNull FlashRunConfiguration configuration) {
    configuration.setModule(moduleCombo.getSelectedModule());
    configuration.setSwfFilePath(swfField.getText().trim());
    Object sdk = flexSdkCombo.getEditor().getItem();
    configuration.setFlexSdkName(sdk == null ? "" : sdk.toString().trim());
    configuration.setFlashPlayerPath(playerField.getText().trim());
  }

  @Override
  protected @NotNull JComponent createEditor() {
    return FormBuilder.createFormBuilder()
      .addLabeledComponent(HaxeDebuggerBundle.message("flash.runner.editor.module"), moduleCombo)
      .addLabeledComponent(HaxeDebuggerBundle.message("flash.runner.editor.swf"), swfField)
      .addLabeledComponent(HaxeDebuggerBundle.message("flash.runner.editor.flex.sdk"), flexSdkCombo)
      .addLabeledComponent(HaxeDebuggerBundle.message("flash.runner.editor.player"), playerField)
      .getPanel();
  }
}
