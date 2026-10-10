package com.intellij.plugins.haxe.v2.toolwindow.ui

import com.intellij.execution.RunManager
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
 * The launch part of the custom action dialog, two collapsed groups of which
 * at most one is in use: "Creates run configuration" (the Haxe kind the
 * action launches its build's output through, the kind's parameters with the
 * build file's defaults as empty text, where a blank field keeps the default
 * live) and "Runs run configuration" (an existing configuration of any type;
 * the action's command becomes its build step). Choosing in one group resets
 * the other. A file-valued parameter browses with a save-style dialog, since
 * the artifact usually does not exist before the first build; a directory one
 * with a folder chooser.
 */
class HaxeLaunchSection(
  private val project: Project,
  private val defaults: Function<HaxeLaunchKind, Map<Parameter, String>>,
  initial: Launch?,
) {
  /** An entry of the existing-configuration combo; {@code typeName} is null when the stored name matches no configuration. */
  private data class ConfigurationChoice(val name: String, val typeName: String?)

  private val kindCombo = ComboBox(DefaultComboBoxModel(kindChoices()))
  private val fields = EnumMap<Parameter, JBTextField>(Parameter::class.java)
  private val editors = EnumMap<Parameter, JComponent>(Parameter::class.java)
  private val createDebugArguments = JBCheckBox(HaxeBundle.message("haxe.custom.action.dialog.launch.debug.arguments"), true)
  private val configurationCombo = ComboBox(DefaultComboBoxModel(configurationChoices(initial)))
  private val existingDebugArguments = JBCheckBox(HaxeBundle.message("haxe.custom.action.dialog.launch.debug.arguments"), true)

  val component: DialogPanel

  init {
    kindCombo.renderer = textListCellRenderer { kind ->
      kind?.displayName() ?: HaxeBundle.message("haxe.custom.action.dialog.launch.none")
    }
    configurationCombo.renderer = textListCellRenderer { choice -> choiceText(choice) }
    for (parameter in Parameter.values()) {
      val field = JBTextField()
      fields[parameter] = field
      editors[parameter] = editorFor(parameter, field)
    }
    component = panel {
      val createGroup = collapsibleGroup(HaxeBundle.message("haxe.custom.action.dialog.launch.section")) {
        row(HaxeBundle.message("haxe.custom.action.dialog.launch.kind")) {
          cell(kindCombo)
        }
        for (parameter in Parameter.values()) {
          row(parameter.label()) {
            cell(editors.getValue(parameter)).align(AlignX.FILL)
          }.visibleIf(kindCombo.selectedValueMatches { it != null && parameter in it.parameters() })
        }
        row {
          cell(createDebugArguments)
        }.visibleIf(kindCombo.selectedValueMatches { it != null })
        row {
          comment(HaxeBundle.message("haxe.custom.action.dialog.launch.hint"))
        }
      }
      val existingGroup = collapsibleGroup(HaxeBundle.message("haxe.custom.action.dialog.existing.section")) {
        row(HaxeBundle.message("haxe.custom.action.dialog.existing.configuration")) {
          cell(configurationCombo)
        }
        row {
          cell(existingDebugArguments)
        }.visibleIf(configurationCombo.selectedValueMatches { it != null })
        row {
          comment(HaxeBundle.message("haxe.custom.action.dialog.existing.hint"))
        }
      }
      createGroup.expanded = initial is Launch.Create
      existingGroup.expanded = initial is Launch.Existing
    }
    when (initial) {
      is Launch.Create -> {
        kindCombo.selectedItem = HaxeLaunchKind.byId(initial.kindId())
        Parameter.fromKeys(initial.parameters()).forEach { (parameter, value) -> fields.getValue(parameter).text = value }
        createDebugArguments.isSelected = initial.injectDebugArguments()
      }
      is Launch.Existing -> {
        configurationCombo.selectedItem = choiceNamed(initial.configurationName())
        existingDebugArguments.isSelected = initial.injectDebugArguments()
      }
      null -> { }
    }
    // either/or: picking in one group clears the other
    kindCombo.addActionListener {
      if (kindCombo.selectedItem != null) configurationCombo.selectedItem = null
      refreshDefaults()
    }
    configurationCombo.addActionListener {
      if (configurationCombo.selectedItem != null) kindCombo.selectedItem = null
    }
    refreshDefaults()
  }

  /** The edited launch, or null for a plain action. Only typed parameter values are kept: a blank field means the default. */
  fun launch(): Launch? {
    val configuration = configurationCombo.selectedItem as? ConfigurationChoice
    if (configuration != null) {
      return Launch.Existing(configuration.name, existingDebugArguments.isSelected)
    }
    val kind = kindCombo.selectedItem as? HaxeLaunchKind ?: return null
    val typed = EnumMap<Parameter, String>(Parameter::class.java)
    for (parameter in kind.parameters()) {
      val text = fields.getValue(parameter).text.trim()
      if (text.isNotEmpty()) typed[parameter] = text
    }
    return Launch.Create(kind.name, Parameter.toKeys(typed), createDebugArguments.isSelected)
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

  /** "None" first, then every registered configuration; a stored name no configuration carries is listed as not found. */
  private fun configurationChoices(initial: Launch?): Array<ConfigurationChoice?> {
    val registered = RunManager.getInstance(project).allSettings
      .map { ConfigurationChoice(it.name, it.type.displayName) }
      .sortedBy { it.name.lowercase() }
    val choices = mutableListOf<ConfigurationChoice?>(null)
    if (initial is Launch.Existing && registered.none { it.name == initial.configurationName() }) {
      choices.add(ConfigurationChoice(initial.configurationName(), null))
    }
    choices.addAll(registered)
    return choices.toTypedArray()
  }

  private fun choiceNamed(name: String): ConfigurationChoice? {
    val model = configurationCombo.model
    return (0 until model.size).map { model.getElementAt(it) }.firstOrNull { it?.name == name }
  }

  private fun choiceText(choice: ConfigurationChoice?): String = when {
    choice == null -> HaxeBundle.message("haxe.custom.action.dialog.existing.none")
    choice.typeName == null -> HaxeBundle.message("haxe.custom.action.dialog.existing.missing", choice.name)
    else -> "${choice.name} (${choice.typeName})"
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
