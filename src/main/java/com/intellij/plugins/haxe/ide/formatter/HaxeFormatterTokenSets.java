package com.intellij.plugins.haxe.ide.formatter;

import com.intellij.psi.tree.TokenSet;

import static com.intellij.plugins.haxe.lang.lexer.HaxeTokenTypeSets.FUNCTION_DEFINITION;
import static com.intellij.plugins.haxe.lang.lexer.HaxeTokenTypes.*;

/**
 * Token sets private to the formatter's rules; lexing/parsing groupings live
 * in HaxeTokenTypeSets.
 */
final class HaxeFormatterTokenSets {

  // the tokens a function header can end with: PRPAREN closes the parameter
  // list, TYPE_TAG a return type, KUNTYPED the untyped marker - shared by the
  // indent and spacing processors' body-on-next-line logic
  static final TokenSet FUNCTION_HEADER_END = TokenSet.create(PRPAREN, TYPE_TAG, KUNTYPED);

  // every node owning a parameter list and a body: the parser's function
  // kinds plus the module-level function, which FUNCTION_DEFINITION lacks
  static final TokenSet FUNCTION_LIKE_OWNERS = TokenSet.orSet(FUNCTION_DEFINITION, TokenSet.create(MODULE_METHOD_DECLARATION));

  // the list nodes whose wrapped items indent from the line that opened them
  static final TokenSet ARGUMENT_LISTS = TokenSet.create(PARAMETER_LIST, EXPRESSION_LIST, CALL_EXPRESSION_LIST);

  // a list's own punctuation, which never indents like an item
  static final TokenSet LIST_PUNCTUATION = TokenSet.create(PLPAREN, PRPAREN, OCOMMA);

  private HaxeFormatterTokenSets() {
  }
}
