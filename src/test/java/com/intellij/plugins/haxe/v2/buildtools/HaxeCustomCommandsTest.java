package com.intellij.plugins.haxe.v2.buildtools;

import com.intellij.plugins.haxe.HaxeCodeInsightFixtureTestCase;
import com.intellij.plugins.haxe.v2.buildtools.settings.HaxeBuildToolSettings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("Build tools: custom command variables")
public class HaxeCustomCommandsTest extends HaxeCodeInsightFixtureTestCase {

  @Override
  protected String getBasePath() {
    return "/testing/runner/";
  }

  @Test
  @DisplayName("commands without the server port variable pass through unchanged")
  public void testCommandsWithoutTheServerPortVariablePassThroughUnchanged() {
    List<String> command = List.of("haxe", "build.hxml");

    assertEquals(command, HaxeCustomCommands.expandServerPort(getProject(), "module", command));
  }

  @Test
  @DisplayName("server port variable stays literal while the server feature is off")
  public void testServerPortVariableStaysLiteralWhileTheServerFeatureIsOff() {
    HaxeBuildToolSettings.getInstance(getProject()).setCompilationServerEnabled(false);

    // the unapplied variable must stay visible instead of silently vanishing
    List<String> command = List.of("mytool", "--connect", "${serverPort}");
    assertEquals(command, HaxeCustomCommands.expandServerPort(getProject(), "module", command));
  }
}
