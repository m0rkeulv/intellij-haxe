package com.intellij.plugins.haxe.v2.buildtools.settings;

import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.plugins.haxe.HaxeLightFixtureTestCase;
import com.intellij.plugins.haxe.v2.buildtools.settings.HaxeCustomActionsStore.ActionReference;
import com.intellij.plugins.haxe.v2.buildtools.settings.HaxeCustomActionsStore.CustomAction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Build tools: custom actions move tracker")
public class HaxeCustomActionsMoveTrackerTest extends HaxeLightFixtureTestCase {

  @Override
  protected String getBasePath() {
    return "";
  }

  @Override
  protected void tearDown() throws Exception {
    try {
      // the store is a service of the shared light project
      HaxeCustomActionsStore.getInstance(getProject()).loadState(new HaxeCustomActionsStore.State());
    }
    finally {
      super.tearDown();
    }
  }

  @Test
  @DisplayName("renaming and moving a build file carries its actions and the references to them")
  public void testRenamingAndMovingABuildFileCarriesItsActionsAndTheReferencesToThem() throws IOException {
    VirtualFile library = myFixture.addFileToProject("lib/build.hxml", "-main Lib").getVirtualFile();
    VirtualFile root = myFixture.addFileToProject("build.hxml", "-main Main").getVirtualFile();
    VirtualFile target = myFixture.getTempDirFixture().findOrCreateDir("libs");
    HaxeCustomActionsStore store = HaxeCustomActionsStore.getInstance(getProject());
    store.addAction(library.getPath(), new CustomAction("prepare", "cmd-prepare", ""));
    ActionReference prepare = new ActionReference(library.getPath(), "prepare");
    store.addAction(root.getPath(), new CustomAction("release", "cmd-release", "", null, List.of(prepare)));

    WriteAction.runAndWait(() -> library.rename(this, "main.hxml"));
    assertEquals(List.of("prepare"), actionNames(store, library.getPath()));
    assertEquals(List.of(new ActionReference(library.getPath(), "prepare")), store.runBeforeChain(root.getPath(), "release"));

    WriteAction.runAndWait(() -> library.getParent().move(this, target));
    assertTrue(library.getPath().contains("/libs/lib/"), library.getPath());
    assertEquals(List.of("prepare"), actionNames(store, library.getPath()));
    assertEquals(List.of(new ActionReference(library.getPath(), "prepare")), store.runBeforeChain(root.getPath(), "release"));
  }

  private static List<String> actionNames(HaxeCustomActionsStore store, String buildFile) {
    return store.getActions(buildFile).stream()
      .map(CustomAction::name)
      .toList();
  }
}
