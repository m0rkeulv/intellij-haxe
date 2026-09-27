/*
 * Copyright 2000-2013 JetBrains s.r.o.
 * Copyright 2014-2014 AS3Boyan
 * Copyright 2014-2014 Elias Ku
 * Copyright 2017-2020 Eric Bishton
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.plugins.haxe.ide.formatter;

import com.intellij.formatting.Indent;
import com.intellij.lang.ASTNode;
import com.intellij.plugins.haxe.ide.formatter.settings.HaxeCodeStyleSettings;
import com.intellij.plugins.haxe.util.UsefulPsiTreeUtil;
import com.intellij.psi.PsiFile;
import com.intellij.psi.codeStyle.CommonCodeStyleSettings;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.tree.TokenSet;
import org.jetbrains.annotations.Nullable;

import static com.intellij.plugins.haxe.ide.formatter.HaxeFormatterNodes.*;
import static com.intellij.plugins.haxe.ide.formatter.HaxeFormatterTokenSets.*;
import static com.intellij.plugins.haxe.lang.lexer.HaxeDocTokenTypes.*;
import static com.intellij.plugins.haxe.lang.lexer.HaxeTokenTypeSets.*;
import static com.intellij.plugins.haxe.lang.lexer.HaxeTokenTypes.*;

/**
 * @author: Fedor.Korotkov
 */
public class HaxeIndentProcessor {
  private static final TokenSet ADDITIVE_CHAIN_LEVELS = TokenSet.create(ADDITIVE_EXPRESSION);

  private final CommonCodeStyleSettings settings;
  private final HaxeCodeStyleSettings haxeSettings;

  public HaxeIndentProcessor(CommonCodeStyleSettings settings, HaxeCodeStyleSettings haxeSettings) {
    this.settings = settings;
    this.haxeSettings = haxeSettings;
  }

  /** The rules in precedence order: the first one claiming the node decides. */
  public Indent getChildIndent(ASTNode node) {
    ASTNode parent = node.getTreeParent();
    if (parent == null || parent.getTreeParent() == null) return Indent.getNoneIndent();
    Site site = Site.of(node);

    Indent indent = inactiveBranchIndent(site);
    if (indent == null) indent = docCommentLineIndent(site);
    if (indent == null) indent = commentIndent(site);
    if (indent == null) indent = braceIndent(site);
    if (indent == null) indent = parenthesizedIndent(site);
    if (indent == null) indent = wrappedOperatorChainIndent(site);
    if (indent == null) indent = containerContentIndent(site);
    if (indent == null) indent = listItemIndent(site);
    if (indent == null) indent = statementBodyIndent(site);
    if (indent == null) indent = wrappedTailIndent(site);
    return indent != null ? indent : Indent.getNoneIndent();
  }

  /**
   * Inside an inactive branch: the block already sits at the right indent
   * (it is a comment-shaped sibling) and the chameleon wrapper layers are
   * transparent, so the content aligns with the directives and inner
   * elements use the normal rules relative to their own parents.
   */
  @Nullable
  private static Indent inactiveBranchIndent(Site site) {
    return INACTIVE_BRANCH_LAYERS.contains(site.parentType()) ? Indent.getNoneIndent() : null;
  }

  /** A line of a doc comment: its stars, its closer, or a body line. */
  @Nullable
  private static Indent docCommentLineIndent(Site site) {
    if (site.parentType() != DOC_COMMENT) return null;
    IElementType type = site.type();
    if (type == DOC_LEADING_ASTERISK) {
      // javadoc-style stars align under the /**'s first star
      return Indent.getSpaceIndent(1);
    }
    if (type == DOC_END) {
      // the starred style aligns its closer under the stars too; haxedoc
      // puts **/ back at the comment's own indent
      boolean starredStyle = site.parent().findChildByType(DOC_LEADING_ASTERISK) != null;
      return starredStyle ? Indent.getSpaceIndent(1) : Indent.getNoneIndent();
    }
    if (type == DOC_START) return Indent.getNoneIndent();
    // body lines sit one level inside the comment; author depth beyond the
    // common prefix rides inside the token text (markdown) and stays untouched
    return Indent.getNormalIndent();
  }

  /** A comment or directive: at its scope's level, except where preserved at the first column. */
  @Nullable
  private Indent commentIndent(Site site) {
    IElementType type = site.type();
    if (!COMMENTS.contains(type)) return null;
    // first-column preservation protects //-disabled code; a doc comment
    // belongs to its member and always follows its scope (as javadoc does)
    if (type != DOC_COMMENT && settings.KEEP_FIRST_COLUMN_COMMENT && isAtFirstColumn(site.node())) {
      return Indent.getAbsoluteNoneIndent();
    }
    // module-level comments sit at the file margin like their sibling declarations
    if (site.parentType() == MODULE) return Indent.getNoneIndent();
    if (site.parentType() == SWITCH_BLOCK) return switchBlockCommentIndent(site);
    return Indent.getNormalIndent();
  }

  /**
   * A comment hanging directly off the switch block: after a case WITHOUT
   * statements it reads as that case's BODY (a lone "// TODO" body, two
   * steps in - continuation depth matches at the standard 2x ratio); after
   * a case with a body, or at the block's start, it reads as a heading for
   * the NEXT case and sits at case level.
   */
  private static Indent switchBlockCommentIndent(Site site) {
    IElementType prevSiblingType = site.prevSiblingType();
    boolean emptyCaseBody = (prevSiblingType == SWITCH_CASE || prevSiblingType == DEFAULT_CASE)
                            && caseBodyIsEmpty(site.prevSibling());
    // a directive past a case body's last statement lands here too (the
    // parser closes the body before it); one closing a region OPENED in
    // that body still aligns with the body, like its #if does
    boolean bodyLevel = emptyCaseBody || closesRegionOpenedInCaseBody(site.node());
    return bodyLevel ? Indent.getContinuationIndent() : Indent.getNormalIndent();
  }

  /** A '{' or '}': the shifted brace styles step it in, the others keep it at its owner's level. */
  @Nullable
  private Indent braceIndent(Site site) {
    if (site.type() != PLCURLY && site.type() != PRCURLY) return null;
    int braceStyle = FUNCTION_LIKE_OWNERS.contains(site.superParentType()) ? settings.METHOD_BRACE_STYLE : settings.BRACE_STYLE;
    return switch (braceStyle) {
      case CommonCodeStyleSettings.NEXT_LINE_SHIFTED, CommonCodeStyleSettings.NEXT_LINE_SHIFTED2 -> Indent.getNormalIndent();
      default -> Indent.getNoneIndent();
    };
  }

  /** Inside parentheses: the parens stay at their owner's level, the content steps in. */
  @Nullable
  private static Indent parenthesizedIndent(Site site) {
    if (site.parentType() != PARENTHESIZED_EXPRESSION) return null;
    boolean paren = site.type() == PLPAREN || site.type() == PRPAREN;
    return paren ? Indent.getNoneIndent() : Indent.getNormalIndent();
  }

  /** A wrapped line of a bool or additive chain, when INDENT_WRAPPED_OPERATOR_CHAINS is on. */
  @Nullable
  private Indent wrappedOperatorChainIndent(Site site) {
    if (!haxeSettings.INDENT_WRAPPED_OPERATOR_CHAINS) return null;
    IElementType parentType = site.parentType();
    // a bool chain's wrapped lines continue ONE step from the line the
    // chain starts on. One step per level cannot stack: left-nested levels
    // of one chain all START on the chain's first line, so every operator
    // line lands exactly one step in; a parenthesized inner chain starts on
    // its opener's (wrapped) line and steps once from there
    if (parentType == LOGIC_AND_EXPRESSION || parentType == LOGIC_OR_EXPRESSION) return Indent.getNormalIndent();
    // an additive chain's wrapped lines stay at the surrounding wrap step
    // (haxe-formatter never stacks them): a chain STARTING a line anchors
    // there and takes no step; a mid-line chain's continuation anchors past
    // it (the call or statement line) and needs the one step back
    if (parentType == ADDITIVE_EXPRESSION) {
      // a mid-line chain that is a list's LAST item already rides the item
      // step (the engine carries it into the closing line), so only there
      // the extra step must not be added again
      boolean levelAlready = additiveChainBeginsItsLine(site.parent()) || additiveChainClosesItsList(site.parent());
      return levelAlready ? Indent.getNoneIndent() : Indent.getNormalIndent();
    }
    return null;
  }

  /** Content of a block, type body, literal or switch: one step in, except directly under the file. */
  @Nullable
  private static Indent containerContentIndent(Site site) {
    if (!indentsChildren(site.parentType(), site.type())) return null;
    return site.node().getPsi().getParent() instanceof PsiFile ? Indent.getNoneIndent() : Indent.getNormalIndent();
  }

  /** The parent indents its children: an indented container, or a bracket literal for all but its brackets. */
  private static boolean indentsChildren(IElementType parentType, IElementType childType) {
    if (INDENTED_CONTAINERS.contains(parentType)) return true;
    return BRACKET_LITERALS.contains(parentType) && childType != PLBRACK && childType != PRBRACK;
  }

  /** A wrapped item of a parameter/argument list, a multi-var declarator or a {@code new} argument. */
  @Nullable
  private Indent listItemIndent(Site site) {
    IElementType type = site.type();
    IElementType parentType = site.parentType();
    // a parameter/argument list carries no indent of its own: its ITEMS do
    // (below), so a chopped-down list (the break right after the paren) and
    // a mid-list wrap land at the same depth instead of stacking
    boolean listOwner = FUNCTION_LIKE_OWNERS.contains(parentType) || parentType == CALL_EXPRESSION;
    if (listOwner && ARGUMENT_LISTS.contains(type)) return Indent.getNoneIndent();
    // an array literal's list is indented as a block of its own
    // (containerContentIndent); its items sit at the list's level
    if (ARGUMENT_LISTS.contains(parentType) && site.superParentType() != ARRAY_LITERAL) return argumentListItemIndent(site);
    // a multi-var declarator wrapped onto its own line continues one step in
    // from the "var" line (the first declarator shares that line)
    if (type == LOCAL_VAR_DECLARATION && parentType == LOCAL_VAR_DECLARATION_LIST) return Indent.getNormalIndent();
    // `new T(a, b)` keeps its arguments as direct children (no list node);
    // an argument follows the paren or a comma
    boolean afterListOpener = site.prevSiblingType() == PLPAREN || site.prevSiblingType() == OCOMMA;
    if (parentType == NEW_EXPRESSION && afterListOpener && type != PRPAREN) return Indent.getNormalIndent();
    return null;
  }

  /**
   * A wrapped list item continues in from the line that opened the list:
   * call arguments and enum constructor parameters one step, a function
   * signature's parameters two - the signature stands off from the body
   * that follows at one - unless there is no such body (a bodiless
   * declaration, an empty {}), which leaves them at one.
   */
  private static Indent argumentListItemIndent(Site site) {
    if (LIST_PUNCTUATION.contains(site.type())) return Indent.getNoneIndent();
    boolean signature = site.parentType() == PARAMETER_LIST && FUNCTION_LIKE_OWNERS.contains(site.superParentType());
    return signature && hasStatementBody(site.superParent()) ? Indent.getContinuationIndent() : Indent.getNormalIndent();
  }

  /** A statement's non-block body on its own line, and a value block under next-line braces. */
  @Nullable
  private static Indent statementBodyIndent(Site site) {
    IElementType type = site.type();
    IElementType parentType = site.parentType();
    IElementType prevSiblingType = site.prevSiblingType();
    // a named function's non-block body on its own line indents one step;
    // the header's own trailing parts also follow a header end and stay unindented
    boolean functionBody = FUNCTION_LIKE_OWNERS.contains(parentType)
                           && FUNCTION_HEADER_END.contains(prevSiblingType)
                           && !FUNCTION_HEADER_TRAILERS.contains(type);
    if (functionBody) return Indent.getNormalIndent();
    if (parentType == FOR_STATEMENT && prevSiblingType == PRPAREN && type != BLOCK_STATEMENT) return Indent.getNormalIndent();
    boolean tryBody = parentType == TRY_STATEMENT && prevSiblingType == KTRY && type != BLOCK_STATEMENT && type != CATCH_STATEMENT;
    if (tryBody) return Indent.getNormalIndent();
    if (parentType == CATCH_STATEMENT && prevSiblingType == PRPAREN && type != BLOCK_STATEMENT) return Indent.getNormalIndent();
    boolean loopBody = type == DO_WHILE_BODY && site.firstChildType() != BLOCK_STATEMENT;
    if (parentType == WHILE_STATEMENT && prevSiblingType == PRPAREN && loopBody) return Indent.getNormalIndent();
    if (parentType == DO_WHILE_STATEMENT && prevSiblingType == KDO && loopBody) return Indent.getNormalIndent();
    if (parentType == RETURN_STATEMENT && prevSiblingType == KRETURN && type != BLOCK_STATEMENT) return Indent.getNormalIndent();
    // a block used as a VALUE steps in from its declaration once the brace
    // style puts its { on the next line (haxe-formatter indents such a brace
    // one level; the contents then step from it). On the = line the step is
    // moot: a block's own indent counts only when it starts a line.
    if (type == VALUE_INIT_BLOCK && (parentType == VAR_INIT || parentType == ASSIGN_EXPRESSION)) return Indent.getNormalIndent();
    // IF_STATEMENT statement components
    boolean guardedBody = (parentType == GUARDED_STATEMENT || parentType == ELSE_STATEMENT)
                          && type != BLOCK_STATEMENT && type != KELSE && type != IF_STATEMENT;
    if (guardedBody) return Indent.getNormalIndent();
    return null;
  }

  /** A wrapped continuation of a declaration or expression: type hint, chain link, inherit clause or ternary part. */
  @Nullable
  private static Indent wrappedTailIndent(Site site) {
    IElementType type = site.type();
    IElementType parentType = site.parentType();
    // an anonymous type opened on the line after its type hint's colon
    // (next-line braces) sits one step in; on the hint's line the step is moot
    if (parentType == TYPE_TAG && type == TYPE_OR_ANONYMOUS) return Indent.getNormalIndent();
    // a wrapped chain link (.map(...) on its own line) indents ONE step from
    // the chain's base line - continuation indent would be a declaration-style
    // double step
    if (type != CALL_EXPRESSION && isChainLink(site.parent())) return Indent.getNormalIndent();
    // a wrapped extends/implements clause continues the declaration header
    if (parentType == INHERIT_LIST) return Indent.getContinuationIndent();
    // wrapped ternary parts (branches, or the signs leading them) continue
    // the condition's line
    if (parentType == TERNARY_EXPRESSION && site.prevSibling() != null) return Indent.getContinuationIndent();
    return null;
  }

  /** The CHAIN ROOT is the last item of its expression list - nothing but the closing paren follows. */
  private static boolean additiveChainClosesItsList(ASTNode additive) {
    ASTNode root = outermostOfKind(additive, ADDITIVE_CHAIN_LEVELS);
    ASTNode parent = root.getTreeParent();
    if (parent == null || !ARGUMENT_LISTS.contains(parent.getElementType())) return false;
    for (ASTNode next = root.getTreeNext(); next != null; next = next.getTreeNext()) {
      if (!WHITESPACES.contains(next.getElementType())) return false;
    }
    return true;
  }

  /** The CHAIN ROOT (outermost additive level) sits at its line's start - only whitespace before it. */
  private static boolean additiveChainBeginsItsLine(ASTNode additive) {
    return beginsItsLine(outermostOfKind(additive, ADDITIVE_CHAIN_LEVELS));
  }

  /** A #else/#elseif/#end at switch-block level whose #if sits inside a case body. */
  private static boolean closesRegionOpenedInCaseBody(ASTNode directive) {
    IElementType type = directive.getElementType();
    if (type != PPEND && type != PPELSE && type != PPELSEIF) return false;
    ASTNode opener = regionOpener(directive).directive();
    return opener != null && opener.getTreeParent() != directive.getTreeParent();
  }

  /** The case carries no body statements - a comment following it then reads as its body. */
  private static boolean caseBodyIsEmpty(ASTNode switchCase) {
    ASTNode block = switchCase.findChildByType(SWITCH_CASE_BLOCK);
    if (block == null) return true;
    for (ASTNode child = block.getFirstChildNode(); child != null; child = child.getTreeNext()) {
      IElementType type = child.getElementType();
      if (!WHITESPACES.contains(type) && !COMMENTS.contains(type)) return false;
    }
    return true;
  }

  /** The node starts at column 0 of its line. */
  private static boolean isAtFirstColumn(ASTNode node) {
    CharSequence text = fileText(node);
    return text != null && HaxeIndentText.lineStartOffset(text, node.getStartOffset()) == node.getStartOffset();
  }

  /** Only whitespace precedes the node on its line. */
  private static boolean beginsItsLine(ASTNode node) {
    CharSequence text = fileText(node);
    if (text == null) return false;
    int offset = node.getStartOffset();
    int indentEnd = HaxeIndentText.lineStartOffset(text, offset) + HaxeIndentText.lineIndentAt(text, offset).length();
    return indentEnd == offset;
  }

  /** The text of the node's file, null for a node outside any file. */
  @Nullable
  private static CharSequence fileText(ASTNode node) {
    PsiFile file = node.getPsi().getContainingFile();
    return file == null ? null : file.getViewProvider().getContents();
  }

  /** The node under judgment with the neighbours the rules consult; built once the node is known to sit below the file's root. */
  private record Site(ASTNode node, @Nullable ASTNode prevSibling, ASTNode parent, ASTNode superParent) {

    static Site of(ASTNode node) {
      ASTNode prevSibling = UsefulPsiTreeUtil.getPrevSiblingSkipWhiteSpacesAndComments(node);
      ASTNode parent = node.getTreeParent();
      return new Site(node, prevSibling, parent, parent.getTreeParent());
    }

    IElementType type() {
      return node.getElementType();
    }

    @Nullable
    IElementType prevSiblingType() {
      return prevSibling == null ? null : prevSibling.getElementType();
    }

    IElementType parentType() {
      return parent.getElementType();
    }

    IElementType superParentType() {
      return superParent.getElementType();
    }

    @Nullable
    IElementType firstChildType() {
      ASTNode firstChild = node.getFirstChildNode();
      return firstChild == null ? null : firstChild.getElementType();
    }
  }
}
