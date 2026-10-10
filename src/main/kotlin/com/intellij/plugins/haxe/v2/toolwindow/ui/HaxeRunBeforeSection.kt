package com.intellij.plugins.haxe.v2.toolwindow.ui

import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.ListSeparator
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.plugins.haxe.HaxeBundle
import com.intellij.plugins.haxe.v2.buildtools.settings.HaxeCustomActionsStore.ActionReference
import com.intellij.ui.AnActionButton
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBList
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.BottomGap
import com.intellij.ui.dsl.builder.CollapsibleRow
import com.intellij.ui.dsl.builder.TopGap
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import java.util.function.Function
import java.util.function.Supplier

/**
 * The "Run before" part of the custom action dialog: actions that run first,
 * in order - of the same build file or of any other; a failure stops the rest
 * and the action itself. `candidates` offers, for the name the dialog
 * currently shows, the actions that can be added without a loop; `label`
 * names a reference in the list, `fileLabel` heads the popup's group of
 * another build file's actions.
 */
class HaxeRunBeforeSection(
  private val candidates: Function<String, List<ActionReference>>,
  private val label: Function<ActionReference, String>,
  private val fileLabel: Function<ActionReference, String?>,
  initial: List<ActionReference>,
) {
  private val model = CollectionListModel(initial)
  private val list = JBList(model)

  /** The edited action's name as the dialog currently shows it; the candidates leave it and its users out. */
  var currentName: Supplier<String> = Supplier { "" }

  val component: DialogPanel

  init {
    list.emptyText.text = HaxeBundle.message("haxe.custom.action.dialog.run.before.empty")
    list.cellRenderer = textListCellRenderer { reference -> reference?.let { label.apply(it) } ?: "" }
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

  private fun chooseAction(button: AnActionButton) {
    val offered = candidates.apply(currentName.get()).filter { it !in model.items }
    if (offered.isEmpty()) return
    JBPopupFactory.getInstance()
      .createListPopup(CandidateStep(offered))
      .show(button.preferredPopupPoint)
  }

  /** The candidates by action name, another build file's under a heading naming that file. */
  private inner class CandidateStep(private val offered: List<ActionReference>) :
    BaseListPopupStep<ActionReference>(HaxeBundle.message("haxe.custom.action.dialog.run.before.add"), offered) {

    override fun getTextFor(value: ActionReference): String = value.action()

    override fun getSeparatorAbove(value: ActionReference): ListSeparator? {
      val heading = fileLabel.apply(value) ?: return null
      val index = offered.indexOf(value)
      val previous = if (index > 0) fileLabel.apply(offered[index - 1]) else null
      return if (heading != previous) ListSeparator(heading) else null
    }

    override fun onChosen(selectedValue: ActionReference, finalChoice: Boolean): PopupStep<*>? {
      model.add(selectedValue)
      return FINAL_CHOICE
    }
  }
}
