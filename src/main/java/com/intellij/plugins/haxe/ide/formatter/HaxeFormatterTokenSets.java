package com.intellij.plugins.haxe.ide.formatter;

import com.intellij.psi.tree.TokenSet;

import static com.intellij.plugins.haxe.lang.lexer.HaxeTokenTypeSets.FUNCTION_DEFINITION;
import static com.intellij.plugins.haxe.lang.lexer.HaxeTokenTypeSets.PPBODY;
import static com.intellij.plugins.haxe.lang.lexer.HaxeTokenTypes.*;

/**
 * Token sets private to the formatter's rules; lexing/parsing groupings live
 * in HaxeTokenTypeSets.
 */
public final class HaxeFormatterTokenSets {

  // the tokens a function header can end with: PRPAREN closes the parameter
  // list, TYPE_TAG a return type, KUNTYPED the untyped marker - shared by the
  // indent and spacing processors' body-on-next-line logic
  static final TokenSet FUNCTION_HEADER_END = TokenSet.create(PRPAREN, TYPE_TAG, KUNTYPED);

  // the header's own trailing parts, which also follow a header end: only
  // what comes after the LAST of them is the body
  static final TokenSet FUNCTION_HEADER_TRAILERS = TokenSet.orSet(FUNCTION_HEADER_END, TokenSet.create(OSEMI, BLOCK_STATEMENT));

  // every node owning a parameter list and a body: the parser's function
  // kinds plus the module-level function, which FUNCTION_DEFINITION lacks
  public static final TokenSet FUNCTION_LIKE_OWNERS = TokenSet.orSet(FUNCTION_DEFINITION, TokenSet.create(MODULE_METHOD_DECLARATION));

  // the list nodes whose wrapped items indent from the line that opened them
  public static final TokenSet ARGUMENT_LISTS = TokenSet.create(PARAMETER_LIST, EXPRESSION_LIST, CALL_EXPRESSION_LIST);

  // a list's own punctuation, which never indents like an item
  static final TokenSet LIST_PUNCTUATION = TokenSet.create(PLPAREN, PRPAREN, OCOMMA);

  // the literals whose items sit one step inside their brackets
  static final TokenSet BRACKET_LITERALS = TokenSet.create(ARRAY_LITERAL, MAP_LITERAL);

  // the nodes whose children indent one step from them. NOT the map entry
  // types: indenting an entry's children indents the entry's own first
  // token again when the literal wraps one-per-line
  static final TokenSet INDENTED_CONTAINERS = TokenSet.create(
    BLOCK_STATEMENT, CLASS_BODY, ABSTRACT_BODY, ANONYMOUS_TYPE_BODY, OBJECT_LITERAL, XML_LITERAL_EXPRESSION, XML_MARKUP_ELEMENT,
    MAP_LOOP_INITIALIZER_EXPRESSION, EXTERN_CLASS_DECLARATION_BODY, ENUM_BODY, INTERFACE_BODY, SWITCH_BLOCK, SWITCH_CASE_BLOCK);

  // an inactive branch's chameleon and its wrapper lists: transparent layers
  // whose content aligns with the directives
  static final TokenSet INACTIVE_BRANCH_LAYERS = TokenSet.create(PPBODY, INACTIVE_MEMBER_LIST, INACTIVE_STATEMENT_LIST, INACTIVE_MODULE_LIST);

  // an opening bracket hugs an inline #if/#end that follows it
  static final TokenSet OPENING_BRACKETS = TokenSet.create(PLPAREN, PLBRACK, PLCURLY);

  // the tokens that hug an inline #end before them (haxe-formatter marks no
  // space after #end before these; their own policies keep them snug)
  static final TokenSet HUGS_CLOSING_DIRECTIVE = TokenSet.create(OCOMMA, OSEMI, PRPAREN, PRBRACK, PRCURLY, ODOT);

  private HaxeFormatterTokenSets() {
  }
}
