package com.intellij.plugins.haxe.lang;

import com.intellij.plugins.haxe.HaxeLightFixtureTestCase;
import com.intellij.plugins.haxe.ide.formatter.settings.HaxeCodeStyleSettings;
import com.intellij.plugins.haxe.ide.formatter.settings.HaxeLanguageCodeStyleSettingsProvider;
import com.intellij.psi.codeStyle.CodeStyleSettingsCustomizable;
import com.intellij.psi.codeStyle.CustomCodeStyleSettings;
import com.intellij.psi.codeStyle.LanguageCodeStyleSettingsProvider.SettingsType;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The custom code style options the settings UI shows: each names a real
 * setting with a resolved title, and every custom setting is reachable in
 * the UI - the few that dedicated tabs own are listed here.
 */
@DisplayName("Code style: options")
public class HaxeCodeStyleOptionsTest extends HaxeLightFixtureTestCase {
  // settings the Imports, Conditional Compilation and hxformat tabs own
  private static final Set<String> DEDICATED_TAB_SETTINGS = Set.of(
    "USE_PROJECT_HXFORMAT", "FORMAT_INACTIVE_BRANCHES", "ALIGN_INACTIVE_CONDITIONAL_BRANCHES",
    "BLANK_LINES_BETWEEN_IMPORT_GROUPS", "IMPORT_GROUP_PACKAGE_DEPTH", "KEEP_BLANK_LINES_BETWEEN_IMPORTS");

  private static final List<SettingsType> TABS = List.of(
    SettingsType.SPACING_SETTINGS, SettingsType.BLANK_LINES_SETTINGS, SettingsType.WRAPPING_AND_BRACES_SETTINGS,
    SettingsType.INDENT_SETTINGS);

  @Override
  protected String getBasePath() {
    return "/formatter/comparison/";
  }

  @Test
  @DisplayName("custom options name settings and resolve their titles")
  public void testCustomOptionsNameSettingsAndResolveTheirTitles() {
    RecordingCustomizable recorder = new RecordingCustomizable();
    HaxeLanguageCodeStyleSettingsProvider provider = new HaxeLanguageCodeStyleSettingsProvider();
    for (SettingsType tab : TABS) {
      provider.customizeSettings(recorder, tab);
    }

    List<String> problems = new ArrayList<>();
    for (CustomOption option : recorder.options) {
      if (option.settingsClass != HaxeCodeStyleSettings.class) problems.add(option.field + ": not a Haxe setting");
      else if (settingField(option.field) == null) problems.add(option.field + ": no such setting");
      if (option.title.isBlank() || option.title.startsWith("!")) problems.add(option.field + ": unresolved title " + option.title);
      if (option.group != null && option.group.startsWith("!")) problems.add(option.field + ": unresolved group " + option.group);
    }
    assertTrue(problems.isEmpty(), String.join("\n", problems));
  }

  @Test
  @DisplayName("every custom setting is exposed")
  public void testEveryCustomSettingIsExposed() {
    RecordingCustomizable recorder = new RecordingCustomizable();
    HaxeLanguageCodeStyleSettingsProvider provider = new HaxeLanguageCodeStyleSettingsProvider();
    for (SettingsType tab : TABS) {
      provider.customizeSettings(recorder, tab);
    }

    Set<String> exposed = new TreeSet<>(DEDICATED_TAB_SETTINGS);
    recorder.options.forEach(option -> exposed.add(option.field));
    Set<String> settings = new TreeSet<>();
    for (Field field : HaxeCodeStyleSettings.class.getFields()) {
      boolean setting = !Modifier.isStatic(field.getModifiers()) && field.getDeclaringClass() == HaxeCodeStyleSettings.class;
      if (setting) settings.add(field.getName());
    }
    assertEquals(settings, exposed, "custom settings without a UI option");
  }

  private static Field settingField(String name) {
    try {
      return HaxeCodeStyleSettings.class.getField(name);
    }
    catch (NoSuchFieldException e) {
      return null;
    }
  }

  private record CustomOption(Class<? extends CustomCodeStyleSettings> settingsClass, String field, String title, String group) {
  }

  /** Collects the custom options a provider shows; the standard-option calls are ignored. */
  private static final class RecordingCustomizable implements CodeStyleSettingsCustomizable {
    final List<CustomOption> options = new ArrayList<>();

    @Override
    public void showAllStandardOptions() {
    }

    @Override
    public void showStandardOptions(String @NotNull ... optionNames) {
    }

    @Override
    public void showCustomOption(@NotNull Class<? extends CustomCodeStyleSettings> settingsClass, @NotNull String fieldName,
                                 @NotNull String title, String groupName, Object... options) {
      this.options.add(new CustomOption(settingsClass, fieldName, title, groupName));
    }

    @Override
    public void showCustomOption(@NotNull Class<? extends CustomCodeStyleSettings> settingsClass, @NotNull String fieldName,
                                 @NotNull String title, String groupName, @NotNull OptionAnchor anchor, String anchorFieldName,
                                 Object... options) {
      this.options.add(new CustomOption(settingsClass, fieldName, title, groupName));
    }

    @Override
    public void renameStandardOption(@NotNull String fieldName, @NotNull String newTitle) {
    }
  }
}
