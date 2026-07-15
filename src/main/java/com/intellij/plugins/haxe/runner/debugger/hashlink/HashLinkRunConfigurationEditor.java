package com.intellij.plugins.haxe.runner.debugger.hashlink;

import com.intellij.application.options.ModulesComboBox;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.options.SettingsEditor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.plugins.haxe.HaxeBundle;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.util.ui.FormBuilder;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import javax.swing.JComponent;
import javax.swing.JPanel;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Settings UI for a HashLink run configuration: module, compiled HashLink
 * binary (auto-detected from the build when empty; .dat accepted so a
 * Lime/OpenFL distribution's {@code hlboot.dat} can be debugged in place),
 * working directory, and an optional custom HashLink executable that
 * overrides the SDK/environment-resolved one.
 */
public class HashLinkRunConfigurationEditor extends SettingsEditor<HashLinkRunConfiguration> {
  private final Project project;
  private final ModulesComboBox moduleCombo = new ModulesComboBox();
  private final TextFieldWithBrowseButton hlFileField = new TextFieldWithBrowseButton();
  private final TextFieldWithBrowseButton workingDirectoryField = new TextFieldWithBrowseButton();
  private final JBCheckBox useCustomHlBinaryCheckbox =
    new JBCheckBox(HaxeBundle.message("hashlink.runner.editor.use.custom.hl"));
  private final TextFieldWithBrowseButton customHlBinaryField = new TextFieldWithBrowseButton();
  private final JPanel panel;

  public HashLinkRunConfigurationEditor(Project project) {
    this.project = project;
    // an extension filter (not withFileFilter) so the NATIVE file dialog gets a
    // real "*.hl;*.dat" dropdown entry — a Condition-based filter is invisible to it
    browseInto(hlFileField, FileChooserDescriptorFactory.createSingleFileDescriptor()
      .withExtensionFilter(HaxeBundle.message("hashlink.runner.editor.file.filter"), "hl", "dat"));
    browseInto(workingDirectoryField, FileChooserDescriptorFactory.createSingleFolderDescriptor());
    browseInto(customHlBinaryField, FileChooserDescriptorFactory.createSingleFileDescriptor());
    customHlBinaryField.setEnabled(false);
    useCustomHlBinaryCheckbox.addItemListener(e -> customHlBinaryField.setEnabled(useCustomHlBinaryCheckbox.isSelected()));
    panel = FormBuilder.createFormBuilder()
      .addLabeledComponent(HaxeBundle.message("hashlink.runner.editor.module"), moduleCombo)
      .addLabeledComponent(HaxeBundle.message("hashlink.runner.editor.hl.file"), hlFileField)
      .addLabeledComponent(HaxeBundle.message("hashlink.runner.editor.working.directory"), workingDirectoryField)
      .addLabeledComponent(useCustomHlBinaryCheckbox, customHlBinaryField)
      .addComponentFillVertically(new JPanel(), 0)
      .getPanel();
  }

  private void browseInto(TextFieldWithBrowseButton field, FileChooserDescriptor descriptor) {
    field.addActionListener(e -> {
      VirtualFile file = FileChooser.chooseFile(descriptor, project, currentSelection(field));
      if (file != null) {
        field.setText(FileUtil.toSystemDependentName(file.getPath()));
      }
    });
  }

  // The chooser opens at the field's current path (or its nearest existing
  // ancestor) instead of the default location. Null (empty/unresolvable text,
  // e.g. a module-relative path) falls back to the chooser's own default.
  private static @Nullable VirtualFile currentSelection(TextFieldWithBrowseButton field) {
    String text = field.getText().trim();
    if (text.isEmpty()) {
      return null;
    }
    try {
      Path path = Path.of(text);
      if (!path.isAbsolute()) {
        return null;
      }
      while (path != null && !Files.exists(path)) {
        path = path.getParent();
      }
      return path != null ? LocalFileSystem.getInstance().findFileByNioFile(path) : null;
    } catch (InvalidPathException e) {
      return null;
    }
  }

  @Override
  protected void resetEditorFrom(@NotNull HashLinkRunConfiguration configuration) {
    moduleCombo.fillModules(project);
    moduleCombo.setSelectedModule(configuration.getConfigurationModule().getModule());
    hlFileField.setText(FileUtil.toSystemDependentName(configuration.getHlFilePath()));
    workingDirectoryField.setText(FileUtil.toSystemDependentName(configuration.getWorkingDirectory()));
    useCustomHlBinaryCheckbox.setSelected(configuration.isUseCustomHlBinary());
    customHlBinaryField.setText(FileUtil.toSystemDependentName(configuration.getCustomHlBinaryPath()));
    customHlBinaryField.setEnabled(configuration.isUseCustomHlBinary());
  }

  @Override
  protected void applyEditorTo(@NotNull HashLinkRunConfiguration configuration) {
    configuration.setModule(moduleCombo.getSelectedModule());
    configuration.setHlFilePath(FileUtil.toSystemIndependentName(hlFileField.getText().trim()));
    configuration.setWorkingDirectory(FileUtil.toSystemIndependentName(workingDirectoryField.getText().trim()));
    configuration.setUseCustomHlBinary(useCustomHlBinaryCheckbox.isSelected());
    configuration.setCustomHlBinaryPath(FileUtil.toSystemIndependentName(customHlBinaryField.getText().trim()));
  }

  @Override
  protected @NotNull JComponent createEditor() {
    return panel;
  }
}
