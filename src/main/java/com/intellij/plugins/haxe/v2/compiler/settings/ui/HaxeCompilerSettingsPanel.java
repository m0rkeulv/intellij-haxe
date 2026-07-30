package com.intellij.plugins.haxe.v2.compiler.settings.ui;

import com.intellij.openapi.ui.ComboBox;
import com.intellij.plugins.haxe.HaxeBundle;
import com.intellij.plugins.haxe.v2.compiler.HaxeLanguageLevel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.dsl.listCellRenderer.BuilderKt;
import com.intellij.ui.table.TableView;
import com.intellij.util.ui.ColumnInfo;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.ListTableModel;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableCellEditor;
import javax.swing.table.TableCellRenderer;
import java.awt.*;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Swing panel for the Haxe compiler settings page: a project default language level
 * and a per-module override table. Holds no reference to project services so it can
 * be exercised in tests; the configurable feeds it data and reads it back.
 */
public final class HaxeCompilerSettingsPanel {

  /** One table row: a module and its (optional) language level override. */
  static final class ModuleLevelRow {
    final String moduleName;
    @Nullable HaxeLanguageLevel override;

    ModuleLevelRow(@NotNull String moduleName, @Nullable HaxeLanguageLevel override) {
      this.moduleName = moduleName;
      this.override = override;
    }
  }

  /** Combo item wrapper so "project default" does not need a null entry in the combo model. */
  private record LevelChoice(@Nullable HaxeLanguageLevel level) {
    static final LevelChoice PROJECT_DEFAULT = new LevelChoice(null);

    static LevelChoice of(@Nullable HaxeLanguageLevel level) {
      return level == null ? PROJECT_DEFAULT : new LevelChoice(level);
    }
  }

  private final ComboBox<HaxeLanguageLevel> defaultLevelCombo = new ComboBox<>(HaxeLanguageLevel.values());
  private final JCheckBox compilerDiagnosticsCheckBox =
    new JCheckBox(HaxeBundle.message("haxe.compiler.diagnostics.checkbox"));
  private final ListTableModel<ModuleLevelRow> tableModel = new ListTableModel<>(new ModuleColumn(), new LevelColumn());
  private final TableView<ModuleLevelRow> table = new TableView<>(tableModel);
  private final JPanel mainPanel;

  public HaxeCompilerSettingsPanel() {
    defaultLevelCombo.setRenderer(BuilderKt.textListCellRenderer("", HaxeLanguageLevel::getPresentableText));
    // "Project default (x.y)" cells display the selected default, keep them in sync.
    defaultLevelCombo.addActionListener(e -> table.repaint());

    compilerDiagnosticsCheckBox.setToolTipText(HaxeBundle.message("haxe.compiler.diagnostics.tooltip"));

    table.setShowGrid(false);
    table.setRowHeight(defaultLevelCombo.getPreferredSize().height);

    mainPanel = FormBuilder.createFormBuilder()
      .addLabeledComponent(HaxeBundle.message("haxe.compiler.default.language.level"), defaultLevelCombo)
      .addComponent(compilerDiagnosticsCheckBox)
      .addComponentFillVertically(new JBScrollPane(table), 8)
      .getPanel();
  }

  public boolean isCompilerDiagnosticsEnabled() {
    return compilerDiagnosticsCheckBox.isSelected();
  }

  public void setCompilerDiagnosticsEnabled(boolean enabled) {
    compilerDiagnosticsCheckBox.setSelected(enabled);
  }

  @NotNull
  public JComponent getComponent() {
    return mainPanel;
  }

  public void reset(@NotNull HaxeLanguageLevel defaultLevel,
                    @NotNull Map<String, HaxeLanguageLevel> overrides,
                    @NotNull List<String> moduleNames) {
    defaultLevelCombo.setSelectedItem(defaultLevel);
    List<ModuleLevelRow> rows = moduleNames.stream()
      .map(name -> new ModuleLevelRow(name, overrides.get(name)))
      .toList();
    tableModel.setItems(rows);
  }

  @NotNull
  public HaxeLanguageLevel getDefaultLanguageLevel() {
    HaxeLanguageLevel selected = defaultLevelCombo.getItem();
    return selected != null ? selected : HaxeLanguageLevel.latest();
  }

  /** Only modules with an explicit override are returned. */
  @NotNull
  public Map<String, HaxeLanguageLevel> getModuleOverrides() {
    stopTableEditing();
    Map<String, HaxeLanguageLevel> result = new LinkedHashMap<>();
    for (ModuleLevelRow row : tableModel.getItems()) {
      if (row.override != null) {
        result.put(row.moduleName, row.override);
      }
    }
    return result;
  }

  private void stopTableEditing() {
    TableCellEditor editor = table.getCellEditor();
    if (editor != null) {
      editor.stopCellEditing();
    }
  }

  private static final class ModuleColumn extends ColumnInfo<ModuleLevelRow, String> {
    ModuleColumn() {
      super(HaxeBundle.message("haxe.compiler.module.column"));
    }

    @Override
    public String valueOf(ModuleLevelRow row) {
      return row.moduleName;
    }
  }

  private final class LevelColumn extends ColumnInfo<ModuleLevelRow, HaxeLanguageLevel> {
    LevelColumn() {
      super(HaxeBundle.message("haxe.compiler.language.level.column"));
    }

    @Override
    public @Nullable HaxeLanguageLevel valueOf(ModuleLevelRow row) {
      return row.override;
    }

    @Override
    public void setValue(ModuleLevelRow row, @Nullable HaxeLanguageLevel value) {
      row.override = value;
    }

    @Override
    public boolean isCellEditable(ModuleLevelRow row) {
      return true;
    }

    @Override
    public TableCellRenderer getRenderer(ModuleLevelRow row) {
      return new DefaultTableCellRenderer() {
        @Override
        protected void setValue(Object value) {
          setText(levelText((HaxeLanguageLevel)value));
        }
      };
    }

    @Override
    public TableCellEditor getEditor(ModuleLevelRow row) {
      return new LevelCellEditor();
    }
  }

  private final class LevelCellEditor extends DefaultCellEditor {
    LevelCellEditor() {
      super(createLevelChoiceCombo());
    }

    @Override
    public Component getTableCellEditorComponent(JTable table, Object value, boolean isSelected, int row, int column) {
      @SuppressWarnings("unchecked")
      JComboBox<LevelChoice> combo = (JComboBox<LevelChoice>)editorComponent;
      combo.setSelectedItem(LevelChoice.of((HaxeLanguageLevel)value));
      return combo;
    }

    @Override
    public Object getCellEditorValue() {
      LevelChoice choice = (LevelChoice)((JComboBox<?>)editorComponent).getSelectedItem();
      return choice == null ? null : choice.level();
    }
  }

  @NotNull
  private ComboBox<LevelChoice> createLevelChoiceCombo() {
    DefaultComboBoxModel<LevelChoice> model = new DefaultComboBoxModel<>();
    model.addElement(LevelChoice.PROJECT_DEFAULT);
    for (HaxeLanguageLevel level : HaxeLanguageLevel.values()) {
      model.addElement(LevelChoice.of(level));
    }
    ComboBox<LevelChoice> combo = new ComboBox<>(model);
    combo.setRenderer(BuilderKt.textListCellRenderer("", choice -> levelText(choice.level())));
    return combo;
  }

  @NotNull
  private String levelText(@Nullable HaxeLanguageLevel level) {
    if (level != null) {
      return level.getPresentableText();
    }
    return HaxeBundle.message("haxe.compiler.project.default.level", getDefaultLanguageLevel().getPresentableText());
  }
}
