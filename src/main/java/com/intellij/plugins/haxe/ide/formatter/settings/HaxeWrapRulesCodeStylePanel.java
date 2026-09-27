package com.intellij.plugins.haxe.ide.formatter.settings;

import com.intellij.plugins.haxe.HaxeCodeStyleBundle;
import com.intellij.psi.codeStyle.CodeStyleSettings;
import com.intellij.ui.TitledSeparator;
import com.intellij.ui.components.fields.IntegerField;
import com.intellij.util.ui.FormBuilder;
import org.intellij.lang.annotations.Language;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JPanel;
import java.util.List;
import java.util.function.ObjIntConsumer;
import java.util.function.ToIntFunction;

/**
 * The Wrap Rules tab of the Haxe code style: the numeric thresholds of the
 * operator chain and multi-var rules (hxformat's wrapping.opBoolChain,
 * opAddSubChain and multiVar). They are integers, which the Wrapping tab's
 * option table cannot host - it renders booleans and choices only.
 */
public class HaxeWrapRulesCodeStylePanel extends HaxeOptionsPreviewPanelBase {

  private static final int MAX_COLUMNS = 999;

  /** One integer option: its field and the setting it edits. */
  private record Option(IntegerField field, ToIntFunction<HaxeCodeStyleSettings> get, ObjIntConsumer<HaxeCodeStyleSettings> set) {
    Option(ToIntFunction<HaxeCodeStyleSettings> get, ObjIntConsumer<HaxeCodeStyleSettings> set) {
      this(new IntegerField(null, 0, MAX_COLUMNS), get, set);
    }
  }

  private final Option boolLineLength = new Option(h -> h.BOOL_CHAIN_SPLIT_LINE_LENGTH, (h, v) -> h.BOOL_CHAIN_SPLIT_LINE_LENGTH = v);
  private final Option boolItemLength = new Option(h -> h.BOOL_CHAIN_SPLIT_ITEM_LENGTH, (h, v) -> h.BOOL_CHAIN_SPLIT_ITEM_LENGTH = v);
  private final Option boolItemCount = new Option(h -> h.BOOL_CHAIN_SPLIT_ITEM_COUNT, (h, v) -> h.BOOL_CHAIN_SPLIT_ITEM_COUNT = v);
  private final Option boolTotalLength = new Option(h -> h.BOOL_CHAIN_SPLIT_TOTAL_LENGTH, (h, v) -> h.BOOL_CHAIN_SPLIT_TOTAL_LENGTH = v);
  private final Option addLineLength = new Option(h -> h.ADD_CHAIN_SPLIT_LINE_LENGTH, (h, v) -> h.ADD_CHAIN_SPLIT_LINE_LENGTH = v);
  private final Option addItemLength = new Option(h -> h.ADD_CHAIN_SPLIT_ITEM_LENGTH, (h, v) -> h.ADD_CHAIN_SPLIT_ITEM_LENGTH = v);
  private final Option addItemCount = new Option(h -> h.ADD_CHAIN_SPLIT_ITEM_COUNT, (h, v) -> h.ADD_CHAIN_SPLIT_ITEM_COUNT = v);
  private final Option addTotalLength = new Option(h -> h.ADD_CHAIN_SPLIT_TOTAL_LENGTH, (h, v) -> h.ADD_CHAIN_SPLIT_TOTAL_LENGTH = v);
  private final Option multiVarSplitWidth = new Option(h -> h.MULTI_VAR_SPLIT_WIDTH, (h, v) -> h.MULTI_VAR_SPLIT_WIDTH = v);
  private final Option multiVarFillItem = new Option(h -> h.MULTI_VAR_FILL_ITEM_LENGTH, (h, v) -> h.MULTI_VAR_FILL_ITEM_LENGTH = v);
  private final List<Option> options = List.of(
    boolLineLength, boolItemLength, boolItemCount, boolTotalLength,
    addLineLength, addItemLength, addItemCount, addTotalLength,
    multiVarSplitWidth, multiVarFillItem);

  protected HaxeWrapRulesCodeStylePanel(CodeStyleSettings settings) {
    super(settings);
    JPanel form = FormBuilder.createFormBuilder()
      .addComponent(new TitledSeparator(HaxeCodeStyleBundle.message("haxe.codestyle.wraprules.bool.title")))
      .addLabeledComponent(HaxeCodeStyleBundle.message("haxe.codestyle.wraprules.chain.line.length"), boolLineLength.field())
      .addLabeledComponent(HaxeCodeStyleBundle.message("haxe.codestyle.wraprules.chain.item.length"), boolItemLength.field())
      .addLabeledComponent(HaxeCodeStyleBundle.message("haxe.codestyle.wraprules.chain.item.count"), boolItemCount.field())
      .addLabeledComponent(HaxeCodeStyleBundle.message("haxe.codestyle.wraprules.chain.total.length"), boolTotalLength.field())
      .addComponent(new TitledSeparator(HaxeCodeStyleBundle.message("haxe.codestyle.wraprules.add.title")))
      .addLabeledComponent(HaxeCodeStyleBundle.message("haxe.codestyle.wraprules.chain.line.length"), addLineLength.field())
      .addLabeledComponent(HaxeCodeStyleBundle.message("haxe.codestyle.wraprules.chain.item.length"), addItemLength.field())
      .addLabeledComponent(HaxeCodeStyleBundle.message("haxe.codestyle.wraprules.chain.item.count"), addItemCount.field())
      .addLabeledComponent(HaxeCodeStyleBundle.message("haxe.codestyle.wraprules.chain.total.length"), addTotalLength.field())
      .addComponent(new TitledSeparator(HaxeCodeStyleBundle.message("haxe.codestyle.wraprules.multi.var.title")))
      .addLabeledComponent(HaxeCodeStyleBundle.message("haxe.codestyle.wraprules.multi.var.split.width"), multiVarSplitWidth.field())
      .addLabeledComponent(HaxeCodeStyleBundle.message("haxe.codestyle.wraprules.multi.var.fill.item"), multiVarFillItem.field())
      .getPanel();
    initPanel(form);
    options.forEach(option -> watch(option.field()));
  }

  @Override
  protected String getTabTitle() {
    return HaxeCodeStyleBundle.message("haxe.codestyle.wraprules.tab.title");
  }

  @Override
  public void apply(@NotNull CodeStyleSettings settings) {
    HaxeCodeStyleSettings haxe = haxeSettings(settings);
    options.forEach(option -> option.set().accept(haxe, option.field().getValue()));
  }

  @Override
  public boolean isModified(CodeStyleSettings settings) {
    HaxeCodeStyleSettings haxe = haxeSettings(settings);
    return options.stream().anyMatch(option -> option.get().applyAsInt(haxe) != option.field().getValue());
  }

  @Override
  protected void resetImpl(@NotNull CodeStyleSettings settings) {
    HaxeCodeStyleSettings haxe = haxeSettings(settings);
    options.forEach(option -> option.field().setValue(option.get().applyAsInt(haxe)));
  }

  @Override
  protected @Nullable String getPreviewText() {
    return WRAP_RULES_CODE_SAMPLE;
  }

  @Language("Haxe")
  private static final String WRAP_RULES_CODE_SAMPLE = """
    class Main {
         static function main() {
              var first = 1, second = 2, third = 3, fourth = 4, fifth = 5, sixth = 6, seventh = 7;
              var ready = first > second && second > third && third > fourth && fourth > fifth && fifth > sixth;
              var total = first + second + third + fourth + fifth + sixth + seventh + first + second;
         }
    }
    """;
}
