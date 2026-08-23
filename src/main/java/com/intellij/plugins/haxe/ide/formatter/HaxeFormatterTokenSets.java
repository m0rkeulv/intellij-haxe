package com.intellij.plugins.haxe.ide.formatter;

import com.intellij.psi.tree.TokenSet;

import static com.intellij.plugins.haxe.lang.lexer.HaxeTokenTypes.KUNTYPED;
import static com.intellij.plugins.haxe.lang.lexer.HaxeTokenTypes.PRPAREN;
import static com.intellij.plugins.haxe.lang.lexer.HaxeTokenTypes.TYPE_TAG;

/**
 * Token sets private to the formatter's rules; lexing/parsing groupings live
 * in HaxeTokenTypeSets.
 */
final class HaxeFormatterTokenSets {

  // the tokens a function header can end with: PRPAREN closes the parameter
  // list, TYPE_TAG a return type, KUNTYPED the untyped marker - shared by the
  // indent and spacing processors' body-on-next-line logic
  static final TokenSet FUNCTION_HEADER_END = TokenSet.create(PRPAREN, TYPE_TAG, KUNTYPED);

  private HaxeFormatterTokenSets() {
  }
}
