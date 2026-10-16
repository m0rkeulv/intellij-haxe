package com.intellij.plugins.haxe.v2.display;

import com.intellij.find.findUsages.FindUsagesHandler;
import com.intellij.plugins.haxe.HaxeLightFixtureTestCase;
import com.intellij.plugins.haxe.ide.HaxeFindUsagesFactoryProxy;
import com.intellij.plugins.haxe.ide.HaxeFindUsagesHandler;
import com.intellij.plugins.haxe.ide.HaxeGotoDeclarationProxy;
import com.intellij.plugins.haxe.lang.psi.HaxeMethodDeclaration;
import com.intellij.plugins.haxe.v2.compiler.settings.HaxeCompilerSettings;
import com.intellij.psi.PsiElement;
import com.intellij.psi.util.PsiTreeUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Compiler services: ide features routing")
public class HaxeCompilerIdeFeaturesRoutingTest extends HaxeLightFixtureTestCase {

  private static final String MAIN_HX_SOURCE = """
    class Main {
    	static function main() {
    		hel<caret>per();
    	}
    	static function helper() {}
    }
    """;

  @Override
  protected String getBasePath() {
    return "";
  }

  @Test
  @DisplayName("find usages stays static while the option is off")
  public void testFindUsagesStaysStaticWhileTheOptionIsOff() {
    myFixture.configureByText("Main.hx", MAIN_HX_SOURCE);

    FindUsagesHandler handler = new HaxeFindUsagesFactoryProxy(getProject()).createFindUsagesHandler(helperDeclaration(), false);

    assertInstanceOf(HaxeFindUsagesHandler.class, handler);
  }

  @Test
  @DisplayName("find usages gets no handler without a compilation server and reports it")
  public void testFindUsagesGetsNoHandlerWithoutACompilationServerAndReportsIt() {
    // no build file in the light project: the file has no compilation server context
    HaxeCompilerSettings.getInstance(getProject()).setCompilerIdeFeaturesEnabled(true);
    myFixture.configureByText("Main.hx", MAIN_HX_SOURCE);

    FindUsagesHandler handler = new HaxeFindUsagesFactoryProxy(getProject()).createFindUsagesHandler(helperDeclaration(), false);

    assertSame(FindUsagesHandler.NULL_HANDLER, handler, "no static search may fill in for the compiler");
    HaxeCompilerNavigationService service = HaxeCompilerNavigationService.getInstance(getProject());
    assertTrue(service.unavailabilityNotifiedForTests(), "the missing server must be reported");
  }

  @Test
  @DisplayName("find usages stays static while its feature is off under the master switch")
  public void testFindUsagesStaysStaticWhileItsFeatureIsOffUnderTheMasterSwitch() {
    HaxeCompilerSettings settings = HaxeCompilerSettings.getInstance(getProject());
    settings.setCompilerIdeFeaturesEnabled(true);
    settings.setCompilerFindUsagesEnabled(false);
    myFixture.configureByText("Main.hx", MAIN_HX_SOURCE);

    FindUsagesHandler handler = new HaxeFindUsagesFactoryProxy(getProject()).createFindUsagesHandler(helperDeclaration(), false);

    assertInstanceOf(HaxeFindUsagesHandler.class, handler);
  }

  @Test
  @DisplayName("go to declaration stays static while its feature is off under the master switch")
  public void testGoToDeclarationStaysStaticWhileItsFeatureIsOffUnderTheMasterSwitch() {
    HaxeCompilerSettings settings = HaxeCompilerSettings.getInstance(getProject());
    settings.setCompilerIdeFeaturesEnabled(true);
    settings.setCompilerNavigationEnabled(false);
    myFixture.configureByText("Main.hx", MAIN_HX_SOURCE);
    int offset = myFixture.getCaretOffset();
    PsiElement atCaret = myFixture.getFile().findElementAt(offset);

    new HaxeGotoDeclarationProxy().getGotoDeclarationTargets(atCaret, offset, myFixture.getEditor());

    // the static handler answers nothing here; the absent notification proves the compiler handler was not asked
    assertFalse(HaxeCompilerNavigationService.getInstance(getProject()).unavailabilityNotifiedForTests(),
                "the compiler handler was not asked");
  }

  @Test
  @DisplayName("identifier highlighting stays static while the option is on")
  public void testIdentifierHighlightingStaysStaticWhileTheOptionIsOn() {
    HaxeCompilerSettings.getInstance(getProject()).setCompilerIdeFeaturesEnabled(true);
    myFixture.configureByText("Main.hx", MAIN_HX_SOURCE);

    FindUsagesHandler handler = new HaxeFindUsagesFactoryProxy(getProject()).createFindUsagesHandler(helperDeclaration(), true);

    assertInstanceOf(HaxeFindUsagesHandler.class, handler);
  }

  @Test
  @DisplayName("go to declaration leaves the platform to resolve without a compilation server")
  public void testGoToDeclarationLeavesThePlatformToResolveWithoutACompilationServer() {
    HaxeCompilerSettings.getInstance(getProject()).setCompilerIdeFeaturesEnabled(true);
    myFixture.configureByText("Main.hx", MAIN_HX_SOURCE);
    int offset = myFixture.getCaretOffset();
    PsiElement atCaret = myFixture.getFile().findElementAt(offset);

    PsiElement[] targets = new HaxeGotoDeclarationProxy().getGotoDeclarationTargets(atCaret, offset, myFixture.getEditor());

    // the static handler answers nothing here either; the notification proves the compiler handler was asked
    assertNull(targets, "the compiler handler answers nothing without a server");
    HaxeCompilerNavigationService service = HaxeCompilerNavigationService.getInstance(getProject());
    assertTrue(service.unavailabilityNotifiedForTests(), "the missing server must be reported");
  }

  private PsiElement helperDeclaration() {
    return PsiTreeUtil.findChildrenOfType(myFixture.getFile(), HaxeMethodDeclaration.class).stream()
      .filter(method -> "helper".equals(method.getName()))
      .findFirst()
      .orElseThrow();
  }
}
