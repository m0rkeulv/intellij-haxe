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

  // one chain per rule branch, each named for the rule it answers to
  @Language("Haxe")
  public static final String WRAP_RULES_CODE_SAMPLE = """
    class Main {
        static function main() {
            // multi-var: short declarators fill the line, long ones split one per line
            var short1 = 1, short2 = 2, short3 = 3, short4 = 4, short5 = 5, short6 = 6, short7 = 7, short8 = 8;
            var longDeclaratorOne = 640, longDeclaratorTwo = 480, longDeclaratorThree = 32, longDeclaratorFour = 60;

            // boolean chains: up to three operands stay; many operands split by count or total
            var fewOperands = short1 > short2 && short2 > short3;
            var manyOperands = short1 > short2 && short2 > short3 && short3 > short4 && short4 > short5 && short5 > short6 && short6 > short7;

            // a long line splits one operand per line when an operand is long, else fills
            var longOperand = isLongOperandOnALongLine(longDeclaratorOne, longDeclaratorTwo) && fewOperands && manyOperands;
            var longLine = isLongOperandOnALongLine(longDeclaratorOne, longDeclaratorTwo) && isLongOperandOnALongLine(longDeclaratorThree, longDeclaratorFour) && fewOperands;

            // additive chains follow the same rules with their own thresholds
            var fewTerms = short1 + short2 + short3;
            var manyTerms = short1 + short2 + short3 + short4 + short5 + short6 + short7 + short8 + longDeclaratorOne;
            var longTerm = longTermOnALongLine(longDeclaratorOne, longDeclaratorTwo) + fewTerms + manyTerms;
            var longLineOfTerms = longTermOnALongLine(longDeclaratorOne, longDeclaratorTwo) + longTermOnALongLine(longDeclaratorThree, longDeclaratorFour) + fewTerms;
        }

        static function isLongOperandOnALongLine(width:Int, height:Int):Bool {
            return width > height;
        }

        static function longTermOnALongLine(width:Int, height:Int):Int {
            return width + height;
        }
    }
    """;
}
