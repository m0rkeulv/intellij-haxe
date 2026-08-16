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
  @DisplayName("test sessions add interp flash and node js")
  public void testSessionsAddInterpFlashAndNodeJs() {
    assertTrue(HaxeDebugSupport.supportsTestDebug(HaxeTarget.INTERP));
    assertTrue(HaxeDebugSupport.supportsTestDebug(HaxeTarget.HL));
    assertTrue(HaxeDebugSupport.supportsTestDebug(HaxeTarget.CPP));
    assertTrue(HaxeDebugSupport.supportsTestDebug(HaxeTarget.FLASH),
               "fdb hosts flash tests and the test console parses its relayed traces");
    assertTrue(HaxeDebugSupport.supportsTestDebug(HaxeTarget.JAVA_SCRIPT),
               "js tests run under node with the js-debug adapter attached");
    assertFalse(HaxeDebugSupport.supportsProgramDebug(HaxeTarget.INTERP), "programs have no interp lane");
  }

  @Test
  @DisplayName("null target is never debuggable")
  public void nullTargetIsNeverDebuggable() {
    assertFalse(HaxeDebugSupport.supportsProgramDebug(null));
    assertFalse(HaxeDebugSupport.supportsTestDebug(null));
  }
}
