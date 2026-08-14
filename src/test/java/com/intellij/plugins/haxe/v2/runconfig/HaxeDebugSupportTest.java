package com.intellij.plugins.haxe.v2.runconfig;

import com.intellij.plugins.haxe.config.HaxeTarget;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The one shared answer to "does a debugger lane exist" - program and test actions both gate on it. */
@DisplayName("Run config: debug support")
public class HaxeDebugSupportTest {

  @Test
  @DisplayName("program sessions debug hl cpp js and flash")
  public void programSessionsDebugHlCppJsAndFlash() {
    assertTrue(HaxeDebugSupport.supportsProgramDebug(HaxeTarget.HL));
    assertTrue(HaxeDebugSupport.supportsProgramDebug(HaxeTarget.CPP));
    assertTrue(HaxeDebugSupport.supportsProgramDebug(HaxeTarget.JAVA_SCRIPT));
    assertTrue(HaxeDebugSupport.supportsProgramDebug(HaxeTarget.FLASH));
  }

  @Test
  @DisplayName("neko has no debugger in either session kind")
  public void nekoHasNoDebuggerInEitherSessionKind() {
    assertFalse(HaxeDebugSupport.supportsProgramDebug(HaxeTarget.NEKO));
    assertFalse(HaxeDebugSupport.supportsTestDebug(HaxeTarget.NEKO));
  }

  @Test
  @DisplayName("test sessions add interp and defer js and flash")
  public void testSessionsAddInterpAndDeferJsAndFlash() {
    assertTrue(HaxeDebugSupport.supportsTestDebug(HaxeTarget.INTERP));
    assertTrue(HaxeDebugSupport.supportsTestDebug(HaxeTarget.HL));
    assertTrue(HaxeDebugSupport.supportsTestDebug(HaxeTarget.CPP));
    assertFalse(HaxeDebugSupport.supportsProgramDebug(HaxeTarget.INTERP), "programs have no interp lane");
    assertFalse(HaxeDebugSupport.supportsTestDebug(HaxeTarget.JAVA_SCRIPT),
                "js tests route output through the debug connection - deferred");
    assertFalse(HaxeDebugSupport.supportsTestDebug(HaxeTarget.FLASH));
  }

  @Test
  @DisplayName("null target is never debuggable")
  public void nullTargetIsNeverDebuggable() {
    assertFalse(HaxeDebugSupport.supportsProgramDebug(null));
    assertFalse(HaxeDebugSupport.supportsTestDebug(null));
  }
}
