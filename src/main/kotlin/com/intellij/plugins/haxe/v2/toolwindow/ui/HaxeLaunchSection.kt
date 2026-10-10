package com.intellij.plugins.haxe.v2.toolwindow.ui

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.plugins.haxe.HaxeBundle
import com.intellij.plugins.haxe.v2.buildtools.settings.HaxeCustomActionsStore.Launch
import com.intellij.plugins.haxe.v2.runconfig.HaxeLaunchKind
import com.intellij.plugins.haxe.v2.runconfig.HaxeLaunchKind.Parameter
import com.intellij.plugins.haxe.v2.runconfig.HaxeLaunchKind.Parameter.Shape
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.ui.layout.selectedValueMatches
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.EnumMap
import java.util.function.Function
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent

/**
 * The "Creates run configuration" section of the custom action dialog: the
 * kind the action launches its build's output through (none for a plain
 * action), the kind's parameters with the build file's defaults as empty text
 * (a blank field keeps the default live) and the debug-arguments flag. A
 * file-valued parameter browses with a save-style dialog, since the artifact
 * usually does not exist before the first build; a directory one with a
 * folder chooser.
 */
class HaxeLaunchSection(
  private val project: Project,
  private val defaults: Function<HaxeLaunchKind, Map<Parameter, String>>,
  initial: Launch?,
) {
  private val kindCombo = ComboBox(DefaultComboBoxModel(kindChoices()))
  private val fields = EnumMap<Parameter, JBTextField>(Parameter::class.java)
  private val editors = EnumMap<Parameter, JComponent>(Parameter::class.java)
  private val debugArguments = JBCheckBox(HaxeBundle.message("haxe.custom.action.dialog.launch.debug.arguments"), true)

  val component: DialogPanel

  init {
    kindCombo.renderer = textListCellRenderer { kind ->
      kind?.displayName() ?: HaxeBundle.message("haxe.custom.action.dialog.launch.none")
    }
    for (parameter in Parameter.values()) {
      val field = JBTextField()
      fields[parameter] = field
      editors[parameter] = editorFor(parameter, field)
    }
    component = panel {
      val group = collapsibleGroup(HaxeBundle.message("haxe.custom.action.dialog.launch.section")) {
        row(HaxeBundle.message("haxe.custom.action.dialog.launch.kind")) {
          cell(kindCombo)
        }
        for (parameter in Parameter.values()) {
          row(parameter.label()) {
            cell(editors.getValue(parameter)).align(AlignX.FILL)
          }.visibleIf(kindCombo.selectedValueMatches { it != null && parameter in it.parameters() })
        }
        row {
          cell(debugArguments)
        }.visibleIf(kindCombo.selectedValueMatches { it != null })
        row {
          comment(HaxeBundle.message("haxe.custom.action.dialog.launch.hint"))
        }
      }
      group.expanded = initial != null
    }
    if (initial != null) {
      kindCombo.selectedItem = HaxeLaunchKind.byId(initial.kindId())
      Parameter.fromKeys(initial.parameters()).forEach { (parameter, value) -> fields.getValue(parameter).text = value }
      debugArguments.isSelected = initial.injectDebugArguments()
    }
    kindCombo.addActionListener { refreshDefaults() }
    refreshDefaults()
  }

  /** The edited launch, or null for a plain action. Only typed values are kept: a blank field means the default. */
  fun launch(): Launch? {
    val kind = kindCombo.selectedItem as? HaxeLaunchKind ?: return null
    val typed = EnumMap<Parameter, String>(Parameter::class.java)
    for (parameter in kind.parameters()) {
      val text = fields.getValue(parameter).text.trim()
      if (text.isNotEmpty()) typed[parameter] = text
    }
    return Launch(kind.name, Parameter.toKeys(typed), debugArguments.isSelected)
  }

  private fun editorFor(parameter: Parameter, field: JBTextField): JComponent = when (parameter.shape()) {
    Shape.TEXT -> field
    Shape.DIRECTORY -> TextFieldWithBrowseButton(field).also { editor ->
      val descriptor = FileChooserDescriptorFactory.singleDir().withTitle(titleOf(parameter))
      editor.addBrowseFolderListener(project, descriptor)
    }
    Shape.FILE -> TextFieldWithBrowseButton(field).also { editor ->
      editor.addActionListener { browseFile(parameter, field) }
    }
  }

  /** A save-style dialog: the folder to navigate to and the name to enter, starting from the field's value or its default. */
  private fun browseFile(parameter: Parameter, field: JBTextField) {
    val descriptor = FileSaverDescriptor(titleOf(parameter), HaxeBundle.message("haxe.custom.action.dialog.launch.browse.file"))
    val start = pathOf(field.text.trim().ifEmpty { field.emptyText.text })
    val startDirectory = start?.parent?.takeIf { Files.isDirectory(it) }
    val chosen = FileChooserFactory.getInstance()
      .createSaveFileDialog(descriptor, project)
      .save(startDirectory, start?.fileName?.toString() ?: "")
    if (chosen != null) {
      field.text = chosen.file.path
    }
  }

  private fun refreshDefaults() {
    val kind = kindCombo.selectedItem as? HaxeLaunchKind
    val values = if (kind == null) emptyMap() else defaults.apply(kind)
    for ((parameter, field) in fields) {
      field.emptyText.text = values[parameter] ?: ""
    }
  }

  private companion object {
    /** "None" first, then every kind a custom action may declare. */
    fun kindChoices(): Array<HaxeLaunchKind?> = (listOf<HaxeLaunchKind?>(null) + HaxeLaunchKind.customizable()).toTypedArray()

    /** The field's label without its trailing colon, as a dialog title. */
    fun titleOf(parameter: Parameter): String = parameter.label().removeSuffix(":")

    fun pathOf(text: String): Path? = try {
      if (text.isEmpty()) null else Path.of(text)
    }
    catch (e: InvalidPathException) {
      null
    }
  }
}
