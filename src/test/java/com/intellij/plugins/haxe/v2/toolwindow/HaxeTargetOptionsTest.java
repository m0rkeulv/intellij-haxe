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
  @DisplayName("default target is html5")
  public void defaultTargetIsHtml5() {
    assertEquals("HTML5", HaxeTargetOptions.defaultChoice(HaxeBuildFileType.OPENFL).id());
    assertEquals("HTML5", HaxeTargetOptions.defaultChoice(HaxeBuildFileType.NMML).id());
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
    boolean nmeHasCppia = HaxeTargetOptions.choicesFor(HaxeBuildFileType.NMML).stream()
      .anyMatch(choice -> choice.id().equals("CPPIA"));
    boolean openflHasCppia = HaxeTargetOptions.choicesFor(HaxeBuildFileType.OPENFL).stream()
      .anyMatch(choice -> choice.id().equals("CPPIA"));
    assertFalse(nmeHasCppia);
    assertTrue(openflHasCppia);
  }
}
