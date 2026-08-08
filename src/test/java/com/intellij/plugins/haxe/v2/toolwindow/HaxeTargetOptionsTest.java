package com.intellij.plugins.haxe.v2.toolwindow;

import com.intellij.plugins.haxe.v2.toolwindow.tree.HaxeBuildFileType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Tool window: target options")
public class HaxeTargetOptionsTest {

  @Test
  @DisplayName("hxml target is not selectable")
  public void hxmlTargetIsNotSelectable() {
    assertFalse(HaxeTargetOptions.isTargetSelectable(HaxeBuildFileType.HXML));
    assertTrue(HaxeTargetOptions.isTargetSelectable(HaxeBuildFileType.OPENFL));
    assertTrue(HaxeTargetOptions.isTargetSelectable(HaxeBuildFileType.LIME));
    assertTrue(HaxeTargetOptions.isTargetSelectable(HaxeBuildFileType.NMML));
    assertTrue(HaxeTargetOptions.isTargetSelectable(HaxeBuildFileType.HXP_PROJECT));
  }

  @Test
  @DisplayName("default target is html5 for openfl and host desktop for nme")
  public void defaultTargetIsHtml5ForOpenflAndHostDesktopForNme() {
    assertEquals("HTML5", HaxeTargetOptions.defaultChoice(HaxeBuildFileType.OPENFL).id());
    // nme's html5 needs an Emscripten runtime stock installs lack; the tool's own default is cpp
    assertEquals("CPP", HaxeTargetOptions.defaultChoice(HaxeBuildFileType.NMML).id());
  }

  @Test
  @DisplayName("display name falls back to default for stale ids")
  public void displayNameFallsBackToDefaultForStaleIds() {
    String fallback = HaxeTargetOptions.displayNameFor(HaxeBuildFileType.OPENFL, "NO_SUCH_TARGET");
    assertEquals(HaxeTargetOptions.defaultChoice(HaxeBuildFileType.OPENFL).displayName(), fallback);
    assertEquals(fallback, HaxeTargetOptions.displayNameFor(HaxeBuildFileType.OPENFL, null));
  }

  @Test
  @DisplayName("nmml offers nme targets and openfl offers openfl targets")
  public void nmmlOffersNmeTargetsAndOpenflOffersOpenflTargets() {
    // HL is lime-only; the plain CPP host-desktop word is nme-only
    boolean nmeHasHl = HaxeTargetOptions.choicesFor(HaxeBuildFileType.NMML).stream()
      .anyMatch(choice -> choice.id().equals("HL"));
    boolean openflHasHl = HaxeTargetOptions.choicesFor(HaxeBuildFileType.OPENFL).stream()
      .anyMatch(choice -> choice.id().equals("HL"));
    assertFalse(nmeHasHl);
    assertTrue(openflHasHl);

    boolean nmeHasCpp = HaxeTargetOptions.choicesFor(HaxeBuildFileType.NMML).stream()
      .anyMatch(choice -> choice.id().equals("CPP"));
    boolean openflHasCpp = HaxeTargetOptions.choicesFor(HaxeBuildFileType.OPENFL).stream()
      .anyMatch(choice -> choice.id().equals("CPP"));
    assertTrue(nmeHasCpp);
    assertFalse(openflHasCpp);
  }
}
