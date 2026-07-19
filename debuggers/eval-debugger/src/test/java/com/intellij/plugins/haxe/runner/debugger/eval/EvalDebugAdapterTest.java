package com.intellij.plugins.haxe.runner.debugger.eval;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * Unit tests for the pure translation helpers of {@link EvalDebugAdapter}.
 */
public class EvalDebugAdapterTest {

  @Test
  public void stripsOneTrailingSemicolon() {
    // the eval VM's expression parser rejects a trailing ';' (Unexpected ;)
    assertEquals("this.member = 1", EvalDebugAdapter.stripTrailingSemicolons("this.member = 1;"));
    assertEquals("member", EvalDebugAdapter.stripTrailingSemicolons("member;"));
  }

  @Test
  public void stripsSemicolonsWithSurroundingWhitespace() {
    assertEquals("x + y", EvalDebugAdapter.stripTrailingSemicolons("  x + y ; "));
    assertEquals("a", EvalDebugAdapter.stripTrailingSemicolons("a ; ;"));
  }

  @Test
  public void leavesCleanExpressionsUntouched() {
    assertEquals("this.member = 1", EvalDebugAdapter.stripTrailingSemicolons("this.member = 1"));
    assertEquals("arr[0]", EvalDebugAdapter.stripTrailingSemicolons("arr[0]"));
  }

  @Test
  public void doesNotTouchInteriorSemicolons() {
    // no valid single expression has one, but if present it is the VM's to reject
    assertEquals("f(a; b)", EvalDebugAdapter.stripTrailingSemicolons("f(a; b);"));
  }

  @Test
  public void nullPassesThrough() {
    assertNull(EvalDebugAdapter.stripTrailingSemicolons(null));
  }
}
