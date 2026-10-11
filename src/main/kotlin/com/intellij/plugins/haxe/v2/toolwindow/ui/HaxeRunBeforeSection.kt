package com.intellij.plugins.haxe.v2.toolwindow.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.ListSeparator
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.plugins.haxe.HaxeBundle
import com.intellij.plugins.haxe.v2.buildtools.HaxeActionReferences.Location
import com.intellij.plugins.haxe.v2.buildtools.settings.HaxeCustomActionsStore.ActionReference
import com.intellij.plugins.haxe.v2.toolwindow.tree.HaxeActionIcons
import com.intellij.ui.AnActionButton
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBList
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.BottomGap
import com.intellij.ui.dsl.builder.CollapsibleRow
import com.intellij.ui.dsl.builder.TopGap
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.listCellRenderer.listCellRenderer
import com.intellij.openapi.ui.Messages
import java.util.function.BiFunction
import java.util.function.Function
import java.util.function.Supplier
import javax.swing.Icon

/**
 * The "Run before" part of the custom action dialog: actions that run first,
 * in order - of the same build file or of any other; a failure stops the rest
 * and the action itself. `candidates` offers, for the name the dialog
 * currently shows, every action but the edited one; `refusal` says why a
 * pick cannot run first (it would loop), shown instead of adding it; `label`
 * names a reference in the list, `missing` marks one naming nothing any
 * more, `location` finds another build file's module and path. The + popup
 * lists the file's own actions, then one entry per module that opens its
 * build files, each opening its actions.
 */
class HaxeRunBeforeSection(
  private val candidates: Function<String, List<ActionReference>>,
  private val label: Function<ActionReference, String>,
  private val missing: Function<ActionReference, Boolean>,
  private val location: Function<String, Location?>,
  private val refusal: BiFunction<String, ActionReference, String?>,
  initial: List<ActionReference>,
) {
  private val model = CollectionListModel(initial)
  private val list = JBList(model)

  /** The edited action's name as the dialog currently shows it; the candidates leave it and its users out. */
  var currentName: Supplier<String> = Supplier { "" }

  val component: DialogPanel

  init {
    list.emptyText.text = HaxeBundle.message("haxe.custom.action.dialog.run.before.empty")
    list.cellRenderer = listCellRenderer {
      if (missing.apply(value)) {
        icon(AllIcons.General.Error)
        text(HaxeBundle.message("haxe.action.reference.missing", label.apply(value)))
      }
      else {
        icon(HaxeActionIcons.forAction(value.action()))
        text(label.apply(value))
      }
    }
    val decorated = ToolbarDecorator.createDecorator(list)
      .setAddAction { button -> chooseAction(button) }
      .createPanel()
    lateinit var group: CollapsibleRow
    component = panel {
      group = collapsibleGroup(HaxeBundle.message("haxe.custom.action.dialog.run.before.section")) {
        row {
          cell(decorated).align(Align.FILL)
        }.topGap(TopGap.SMALL)
        row {
          comment(HaxeBundle.message("haxe.custom.action.dialog.run.before.hint"))
        }.bottomGap(BottomGap.SMALL)
      }
      group.topGap(TopGap.NONE).bottomGap(BottomGap.NONE)
    }
    // set once the panel exists: a list in use opens
    group.expanded = initial.isNotEmpty()
  }

  /** The actions to run first, in order. */
  fun actions(): List<ActionReference> = model.items.toList()

  private fun add(reference: ActionReference) {
    val message = refusal.apply(currentName.get(), reference)
    if (message == null) {
      model.add(reference)
    }
    else {
      Messages.showWarningDialog(list, message, HaxeBundle.message("haxe.custom.action.dialog.run.before.add"))
    }
  }

  private fun chooseAction(button: AnActionButton) {
    val offered = candidates.apply(currentName.get()).filter { it !in model.items }
    if (offered.isEmpty()) return
    val step = ChoiceStep(HaxeBundle.message("haxe.custom.action.dialog.run.before.add"), choices(offered))
    JBPopupFactory.getInstance()
      .createListPopup(step)
      .show(button.preferredPopupPoint)
  }

  /** The own file's actions, then a group per module of the other build files, in the order offered. */
  private fun choices(offered: List<ActionReference>): List<Choice> {
    val located = offered.associateWith { reference -> reference.buildFile()?.let { location.apply(it) } }
    val (own, others) = offered.partition { located[it] == null }
    val modules = others.groupBy { located.getValue(it)!!.module() }
      .map { (module, references) -> GroupChoice(module, AllIcons.Nodes.Module, fileChoices(references, located)) }
    return own.map(::ActionChoice) + modules
  }

  /** A group per build file of one module, holding its actions. */
  private fun fileChoices(references: List<ActionReference>, located: Map<ActionReference, Location?>): List<Choice> =
    references.groupBy { located.getValue(it)!! }
      .map { (location, actions) -> GroupChoice(location.path(), fileIcon(location), actions.map(::ActionChoice)) }

  private fun fileIcon(location: Location): Icon =
    location.type()?.icon ?: AllIcons.FileTypes.Any_type

  /** A row of the + popup: an action to add, or a module or build file that opens the next level. */
  private sealed interface Choice {
    val text: String
  }

  private data class ActionChoice(val reference: ActionReference) : Choice {
    override val text: String get() = reference.action()
  }

  private data class GroupChoice(override val text: String, val icon: Icon, val children: List<Choice>) : Choice

  private inner class ChoiceStep(title: String?, private val choices: List<Choice>) : BaseListPopupStep<Choice>(title, choices) {
    override fun getTextFor(value: Choice): String = value.text

    override fun getIconFor(value: Choice): Icon = when (value) {
      is GroupChoice -> value.icon
      is ActionChoice -> HaxeActionIcons.forAction(value.text)
    }

    override fun hasSubstep(selectedValue: Choice): Boolean = selectedValue is GroupChoice

    override fun isSpeedSearchEnabled(): Boolean = true

    // a line between the own file's actions and the first module
    override fun getSeparatorAbove(value: Choice): ListSeparator? {
      val firstGroup = value is GroupChoice && choices.first() is ActionChoice && choices.first { it is GroupChoice } === value
      return if (firstGroup) ListSeparator() else null
    }

    override fun onChosen(selectedValue: Choice, finalChoice: Boolean): PopupStep<*>? = when (selectedValue) {
      is GroupChoice -> ChoiceStep(null, selectedValue.children)
      is ActionChoice -> doFinalStep { add(selectedValue.reference) }
    }
  }
}
