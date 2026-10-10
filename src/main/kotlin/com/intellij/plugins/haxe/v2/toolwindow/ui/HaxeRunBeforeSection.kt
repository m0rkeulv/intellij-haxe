package com.intellij.plugins.haxe.v2.toolwindow.ui

import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.plugins.haxe.HaxeBundle
import com.intellij.ui.AnActionButton
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBList
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.BottomGap
import com.intellij.ui.dsl.builder.CollapsibleRow
import com.intellij.ui.dsl.builder.TopGap
import com.intellij.ui.dsl.builder.panel
import java.util.function.Function
import java.util.function.Supplier

/**
 * The "Run before" part of the custom action dialog: other actions of the
 * same build file that run first, in order; a failure stops the rest and the
 * action itself. `candidates` offers, for the name the dialog currently
 * shows, the actions that can be added without a loop.
 */
class HaxeRunBeforeSection(private val candidates: Function<String, List<String>>, initial: List<String>) {
  private val model = CollectionListModel(initial)
  private val list = JBList(model)

  /** The edited action's name as the dialog currently shows it; the candidates leave it and its users out. */
  var currentName: Supplier<String> = Supplier { "" }

  val component: DialogPanel

  init {
    list.emptyText.text = HaxeBundle.message("haxe.custom.action.dialog.run.before.empty")
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
  fun actions(): List<String> = model.items.toList()

  private fun chooseAction(button: AnActionButton) {
    val offered = candidates.apply(currentName.get()).filter { it !in model.items }
    if (offered.isEmpty()) return
    JBPopupFactory.getInstance()
      .createPopupChooserBuilder(offered)
      .setTitle(HaxeBundle.message("haxe.custom.action.dialog.run.before.add"))
      .setItemChosenCallback { model.add(it) }
      .createPopup()
      .show(button.preferredPopupPoint)
  }
}
