package com.intellij.plugins.haxe.v2.compiler.settings;

import com.intellij.plugins.haxe.v2.compiler.HaxeLanguageLevel;
import com.intellij.util.xmlb.XmlSerializer;
import org.jdom.Element;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Compiler: project settings")
public class HaxeCompilerProjectSettingsTest {

  @Test
  @DisplayName("default language level is latest when unset")
  public void defaultLanguageLevelIsLatestWhenUnset() {
    HaxeCompilerProjectSettings settings = new HaxeCompilerProjectSettings();
    assertEquals(HaxeLanguageLevel.latest(), settings.getDefaultLanguageLevel());
  }

  @Test
  @DisplayName("effective level falls back to default")
  public void effectiveLevelFallsBackToDefault() {
    HaxeCompilerProjectSettings settings = new HaxeCompilerProjectSettings();
    settings.setDefaultLanguageLevel(HaxeLanguageLevel.HAXE_4_2);
    settings.setModuleLanguageLevelOverride("app", HaxeLanguageLevel.HAXE_3_4);

    assertEquals(HaxeLanguageLevel.HAXE_3_4, settings.getEffectiveLanguageLevel("app"));
    assertEquals(HaxeLanguageLevel.HAXE_4_2, settings.getEffectiveLanguageLevel("lib"));
  }

  @Test
  @DisplayName("clearing an override restores default")
  public void clearingAnOverrideRestoresDefault() {
    HaxeCompilerProjectSettings settings = new HaxeCompilerProjectSettings();
    settings.setModuleLanguageLevelOverride("app", HaxeLanguageLevel.HAXE_4_0);
    settings.setModuleLanguageLevelOverride("app", null);

    assertNull(settings.getModuleLanguageLevelOverride("app"));
    assertEquals(settings.getDefaultLanguageLevel(), settings.getEffectiveLanguageLevel("app"));
  }

  @Test
  @DisplayName("overrides map replaces previous entries")
  public void overridesMapReplacesPreviousEntries() {
    HaxeCompilerProjectSettings settings = new HaxeCompilerProjectSettings();
    settings.setModuleLanguageLevelOverride("old", HaxeLanguageLevel.HAXE_4_0);
    settings.setModuleLanguageLevelOverrides(Map.of("app", HaxeLanguageLevel.HAXE_4_1));

    assertNull(settings.getModuleLanguageLevelOverride("old"));
    assertEquals(Map.of("app", HaxeLanguageLevel.HAXE_4_1), settings.getModuleLanguageLevelOverrides());
  }

  @Test
  @DisplayName("state survives xml serialization round trip")
  public void stateSurvivesXmlSerializationRoundTrip() {
    HaxeCompilerProjectSettings settings = new HaxeCompilerProjectSettings();
    settings.setDefaultLanguageLevel(HaxeLanguageLevel.HAXE_4_3);
    settings.setModuleLanguageLevelOverride("app", HaxeLanguageLevel.HAXE_5_0);
    settings.setModuleLanguageLevelOverride("legacy", HaxeLanguageLevel.HAXE_3_4);

    Element serialized = XmlSerializer.serialize(settings.getState());
    HaxeCompilerProjectSettings.State deserialized =
      XmlSerializer.deserialize(serialized, HaxeCompilerProjectSettings.State.class);

    HaxeCompilerProjectSettings reloaded = new HaxeCompilerProjectSettings();
    reloaded.loadState(deserialized);

    assertEquals(HaxeLanguageLevel.HAXE_4_3, reloaded.getDefaultLanguageLevel());
    assertEquals(HaxeLanguageLevel.HAXE_5_0, reloaded.getModuleLanguageLevelOverride("app"));
    assertEquals(HaxeLanguageLevel.HAXE_3_4, reloaded.getModuleLanguageLevelOverride("legacy"));
  }

  @Test
  @DisplayName("unknown stored versions are ignored gracefully")
  public void unknownStoredVersionsAreIgnoredGracefully() {
    HaxeCompilerProjectSettings.State state = new HaxeCompilerProjectSettings.State();
    state.defaultLanguageLevel = "garbage";
    state.moduleLanguageLevels.put("app", "also-garbage");

    HaxeCompilerProjectSettings settings = new HaxeCompilerProjectSettings();
    settings.loadState(state);

    assertEquals(HaxeLanguageLevel.latest(), settings.getDefaultLanguageLevel());
    assertNull(settings.getModuleLanguageLevelOverride("app"));
    assertTrue(settings.getModuleLanguageLevelOverrides().isEmpty());
  }
}
