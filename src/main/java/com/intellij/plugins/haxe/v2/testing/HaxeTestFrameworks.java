package com.intellij.plugins.haxe.v2.testing;

import java.util.List;

/**
 * The frameworks the plugin knows, in detection order: the first whose
 * haxelib a tests build declares wins, utest is the default. The run planner
 * dispatches compile arguments through this list and the test locator
 * dispatches result navigation through it.
 */
public final class HaxeTestFrameworks {

  public static final List<HaxeTestFramework> ALL =
    List.of(new MunitFramework(), new BuddyFramework(), new TinkFramework(), new UtestFramework());

  private HaxeTestFrameworks() {
  }
}
