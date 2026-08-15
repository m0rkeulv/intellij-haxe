@file:JvmName("HaxeRuntimeSettingsControls")

package com.intellij.plugins.haxe.config.sdk.ui

import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.TextBrowseFolderListener
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.util.io.FileUtil
import com.intellij.plugins.haxe.HaxeBundle
import com.intellij.plugins.haxe.util.HaxeSdkUtilBase
import com.intellij.ui.components.fields.ExtendableTextField
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import java.util.Locale
import javax.swing.DefaultComboBoxModel
import org.jetbrains.annotations.Nls

/**
 * Controls shared by the runtime-configuring settings panels (the Haxe SDK's
 * additional data page and Build Tools | Haxe).
 */

fun executableField(@Nls chooserTitle: String): TextFieldWithBrowseButton {
  // an ExtendableTextField, so the field can show the inherited value as grayed empty text
  val field = TextFieldWithBrowseButton(ExtendableTextField())
  val descriptor = FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor().withTitle(chooserTitle)
  field.addBrowseFolderListener(TextBrowseFolderListener(descriptor))
  return field
}

/** Shows what an empty field inherits as grayed empty text; null clears it. */
fun setInheritedDefault(field: TextFieldWithBrowseButton, inherited: String?) {
  val textField = field.textField as? ExtendableTextField ?: return
  textField.emptyText.text = inherited?.let(FileUtil::toSystemDependentName) ?: ""
}

/** The executable a PATH lookup would pick, for display as an inherited default. */
fun pathDetectedExecutable(executableName: String): String? =
  PathEnvironmentVariableUtil.findInPath(HaxeSdkUtilBase.getExecutableName(executableName))?.absolutePath

/**
 * A Flex/AIR SDK selector: entries come from the IDE's SDK table, matched by
 * type name so callers never depend on the (optional) Flash plugin's classes.
 * The null first item means "not set" and renders as [emptyText]; without any
 * matching entry the combo is disabled with a tooltip explaining where the
 * entries come from.
 */
fun flexSdkCombo(@Nls emptyText: String): ComboBox<String?> {
  val combo = ComboBox<String?>()
  combo.renderer = textListCellRenderer(emptyText) { it }

  val model = DefaultComboBoxModel<String?>()
  model.addElement(null)
  for (sdk in ProjectJdkTable.getInstance().allJdks) {
    if (sdk.sdkType.name.lowercase(Locale.ROOT).contains("flex")) {
      model.addElement(sdk.name)
    }
  }
  combo.model = model
  if (model.size == 1) {
    combo.isEnabled = false
    combo.toolTipText = HaxeBundle.message("flex.sdk.none")
  }
  return combo
}

/** Selects the stored name, keeping a name whose SDK entry is gone visible instead of silently dropping it. */
fun selectFlexSdk(combo: ComboBox<String?>, name: String) {
  val value = name.ifEmpty { null }
  val model = combo.model as DefaultComboBoxModel<String?>
  if (value != null && model.getIndexOf(value) < 0) {
    model.addElement(value)
    combo.isEnabled = true
  }
  combo.selectedItem = value
}

fun selectedFlexSdk(combo: ComboBox<String?>): String = (combo.selectedItem as String?) ?: ""
