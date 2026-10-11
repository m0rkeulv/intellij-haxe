package com.intellij.plugins.haxe.v2.buildtools.info;

import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.plugins.haxe.HaxeCodeInsightFixtureTestCase;
import com.intellij.plugins.haxe.lang.psi.impl.HaxeInactiveBody;
import com.intellij.plugins.haxe.v2.buildtools.settings.HaxeActiveBuildFileStore;
import com.intellij.plugins.haxe.v2.buildtools.settings.HaxeResolveContextStore;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.PlatformTestUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The define set a file is lexed with follows its resolve context (its own build, or a pin), not the active build file. */
@DisplayName("Define context: per file")
public class HaxeFileDefineContextTest extends HaxeCodeInsightFixtureTestCase {
  private static final String WEB_HXML_SOURCE = """
    -cp src
    -main WebMain
    -js bin/web.js
    """;
  private static final String CPP_HXML_SOURCE = """
    -cp src
    -main CppMain
    -cpp bin/cpp
    """;
  /** Both branches declare the class; which one is the dead blob tells the contexts apart. */
  private static final String SYS_GATED_SOURCE = """
    #if sys
    class %1$s { static var sys = true; }
    #else
    class %1$s { static var sys = false; }
    #end
    """;

  @Override
  protected String getBasePath() {
    return "";
  }

  @Test
  @DisplayName("a file is lexed with the defines of its own build, not the active one")
  public void testAFileIsLexedWithTheDefinesOfItsOwnBuildNotTheActiveOne() {
    VirtualFile web = myFixture.addFileToProject("web.hxml", WEB_HXML_SOURCE).getVirtualFile();
    myFixture.addFileToProject("cpp.hxml", CPP_HXML_SOURCE);
    HaxeActiveBuildFileStore.getInstance(getProject()).setActiveFile(web.getPath());
    PsiFile cppMain = myFixture.addFileToProject("src/CppMain.hx", SYS_GATED_SOURCE.formatted("CppMain"));
    PsiFile webMain = myFixture.addFileToProject("src/WebMain.hx", SYS_GATED_SOURCE.formatted("WebMain"));

    assertTrue(sysBranchLive(cppMain), "CppMain is cpp.hxml's main: sys is defined there while web.hxml is active");
    assertFalse(sysBranchLive(webMain), "WebMain follows web.hxml, which has no sys");
  }

  @Test
  @DisplayName("a pin re-lexes the file under the pinned builds defines")
  public void testAPinReLexesTheFileUnderThePinnedBuildsDefines() {
    VirtualFile web = myFixture.addFileToProject("web.hxml", WEB_HXML_SOURCE).getVirtualFile();
    VirtualFile cpp = myFixture.addFileToProject("cpp.hxml", CPP_HXML_SOURCE).getVirtualFile();
    HaxeActiveBuildFileStore.getInstance(getProject()).setActiveFile(web.getPath());
    VirtualFile shared = myFixture.addFileToProject("src/Shared.hx", SYS_GATED_SOURCE.formatted("Shared")).getVirtualFile();
    myFixture.openFileInEditor(shared);
    assertFalse(sysBranchLive(psiFile(shared)), "a shared file follows the active build before a pin");

    HaxeResolveContextStore.getInstance(getProject()).pin(shared, cpp.getPath());
    HaxeDefineContextService.getInstance(getProject()).fileContextChanged(shared);

    PlatformTestUtil.waitWithEventsDispatching("pinned to cpp.hxml: the sys branch must come alive", () -> sysBranchLive(psiFile(shared)), 10);
  }

  private PsiFile psiFile(VirtualFile file) {
    PsiFile psiFile = PsiManager.getInstance(getProject()).findFile(file);
    assertNotNull(psiFile);
    return psiFile;
  }

  /** Whether the {@code sys = true} declaration sits in live code rather than in a dead-branch blob. */
  private static boolean sysBranchLive(PsiFile file) {
    PsiElement leaf = file.findElementAt(file.getText().indexOf("sys = true"));
    assertNotNull(leaf);
    return PsiTreeUtil.getParentOfType(leaf, HaxeInactiveBody.class) == null;
  }
}
