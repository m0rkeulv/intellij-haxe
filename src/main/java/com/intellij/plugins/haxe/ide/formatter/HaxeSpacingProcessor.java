/*
 * Copyright 2000-2013 JetBrains s.r.o.
 * Copyright 2014-2014 AS3Boyan
 * Copyright 2014-2014 Elias Ku
 * Copyright 2017-2020 Eric Bishton
 * Copyright 2017-2017 Ilya Malanin
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

import com.intellij.formatting.Block;
import com.intellij.formatting.Spacing;
import com.intellij.lang.ASTNode;
import com.intellij.openapi.util.TextRange;
import com.intellij.plugins.haxe.ide.formatter.HaxeFormatterNodes.RegionEnd;
import com.intellij.plugins.haxe.ide.formatter.settings.HaxeCodeStyleSettings;
import com.intellij.plugins.haxe.ide.formatter.wrapping.*;
import com.intellij.plugins.haxe.ide.formatter.wrapping.HaxeLiteralItemRules.Decision;
import com.intellij.plugins.haxe.ide.formatter.wrapping.HaxeOperatorChainRules.Kind;
import com.intellij.plugins.haxe.lang.psi.HaxeNamedComponent;
import com.intellij.plugins.haxe.lang.psi.HaxeTypeTag;
import com.intellij.plugins.haxe.metadata.lexer.HaxeMetadataTokenTypes;
import com.intellij.plugins.haxe.metadata.util.HaxeMetadataUtils;
import com.intellij.plugins.haxe.util.UsefulPsiTreeUtil;
import com.intellij.psi.PsiElement;
import com.intellij.psi.codeStyle.CommonCodeStyleSettings;
import com.intellij.psi.formatter.FormatterUtil;
import com.intellij.psi.formatter.common.AbstractBlock;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.tree.TokenSet;
import com.intellij.psi.util.PsiTreeUtil;
import lombok.CustomLog;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.List;
import java.util.function.BiFunction;

import static com.intellij.plugins.haxe.ide.formatter.HaxeFormatterNodes.isEmptyBlock;
import static com.intellij.plugins.haxe.ide.formatter.HaxeFormatterNodes.nextLineBraces;
import static com.intellij.plugins.haxe.ide.formatter.HaxeFormatterNodes.regionCloser;
import static com.intellij.plugins.haxe.ide.formatter.HaxeFormatterNodes.regionOpener;
import static com.intellij.plugins.haxe.ide.formatter.HaxeFormatterTokenSets.*;
import static com.intellij.plugins.haxe.lang.lexer.HaxeTokenTypeSets.*;
import static com.intellij.plugins.haxe.lang.lexer.HaxeTokenTypes.*;

/**
 * The spacing between two adjacent child blocks of one node. The rules are
 * consulted phase by phase in precedence order: the first rule that answers
 * a pair decides it, so a rule's position IS part of its meaning.
 *
 * @author: Fedor.Korotkov
 */
@CustomLog
public class HaxeSpacingProcessor {
  // a spacing bound the engine never reaches (it adds one to keepBlankLines, so MAX_VALUE would overflow)
  private static final int UNBOUNDED = 9999;
  // the functions whose non-block body may move to its own line: every
  // function-like owner but the anonymous literal
  private static final TokenSet NAMED_FUNCTIONS = TokenSet.andNot(FUNCTION_LIKE_OWNERS, TokenSet.create(FUNCTION_LITERAL));
  // the rule phases in precedence order; each returns null to fall through
  private static final List<BiFunction<HaxeSpacingProcessor, Pair, Spacing>> PHASES = List.of(
    HaxeSpacingProcessor::fileSectionSpacing,
    HaxeSpacingProcessor::typeDeclarationSpacing,
    // before the brace rules: an object literal's braces follow its item decision
    HaxeSpacingProcessor::literalItemSpacing,
    HaxeSpacingProcessor::typeBodyBraceSpacing,
    HaxeSpacingProcessor::caseBodySpacing,
    HaxeSpacingProcessor::stackedCommentSpacing,
    HaxeSpacingProcessor::memberSpacing,
    HaxeSpacingProcessor::bodyPlacementSpacing,
    HaxeSpacingProcessor::bracketSpacing,
    HaxeSpacingProcessor::parenBeforeSpacing,
    HaxeSpacingProcessor::braceBeforeSpacing,
    HaxeSpacingProcessor::parenWithinSpacing,
    HaxeSpacingProcessor::literalAndValueBlockSpacing,
    HaxeSpacingProcessor::assignmentSpacing,
    HaxeSpacingProcessor::operatorSpacing,
    HaxeSpacingProcessor::keywordSpacing,
    HaxeSpacingProcessor::listSpacing,
    HaxeSpacingProcessor::colonAndMiscSpacing);

  private final ASTNode node;
  private final CommonCodeStyleSettings common;
  private final HaxeCodeStyleSettings haxe;
  private final IElementType elementType;
  @Nullable private final IElementType parentType;
  // whether a pair keeps a written line break: the KEEP_LINE_BREAKS setting
  // (off during the platform's second reformat, which drops custom breaks),
  // or a construct whose written layout stands
  private final boolean keepLineBreaks;
  // the node sits in a type position (an anonymous structure, a type hint),
  // whose multi-line bodies keep their written shape
  private final boolean inTypePosition;
  // a typedef body under next-line braces lists one field per line, its
  // braces on their own lines (rightCurly=both); other type bodies keep
  // their written shape
  private final boolean typedefLines;

  public HaxeSpacingProcessor(ASTNode node, CommonCodeStyleSettings common, HaxeCodeStyleSettings haxe) {
    this.node = node;
    this.common = common;
    this.haxe = haxe;
    elementType = node.getElementType();
    ASTNode parent = node.getTreeParent();
    parentType = parent == null ? null : parent.getElementType();
    keepLineBreaks = common.KEEP_LINE_BREAKS || keepsWrittenLayout(elementType);
    inTypePosition = parentType == ANONYMOUS_TYPE || PsiTreeUtil.getParentOfType(node.getPsi(), HaxeTypeTag.class) != null;
    typedefLines = typedefBodyExpands(node, common);
  }

  /**
   * The two adjacent child blocks being spaced and the facts every rule
   * reads off them. Metadata before a member is camouflaged as that
   * member's type; memberNode2 carries the REAL member behind the
   * camouflage for the rules that inspect the declaration (field grouping).
   */
  private record Pair(Block child1, Block child2, ASTNode node1, ASTNode node2, IElementType type1, IElementType type2,
                      @Nullable IElementType typeType1, @Nullable IElementType typeType2, ASTNode memberNode2) {

    static Pair of(AbstractBlock child1, AbstractBlock child2) {
      ASTNode node1 = child1.getNode();
      ASTNode node2 = child2.getNode();
      ASTNode member2 = declaredMember(node2);
      return new Pair(child1, child2, node1, node2, node1.getElementType(), member2.getElementType(),
                      firstChildType(node1), firstChildType(node2), member2);
    }

    /** The member metadata decorates; any other node is its own member. */
    private static ASTNode declaredMember(ASTNode node) {
      if (node.getElementType() != EMBEDDED_META) return node;
      PsiElement element = HaxeMetadataUtils.getAssociatedElement(node.getPsi());
      return element == null ? node : element.getNode();
    }

    @Nullable
    private static IElementType firstChildType(ASTNode node) {
      ASTNode first = node.getFirstChildNode();
      return first == null ? null : first.getElementType();
    }
  }

  @Nullable
  public Spacing getSpacing(Block child1, Block child2) {
    Spacing spacing = spacingBetween(child1, child2);
    if (log.isDebugEnabled()) {
      log.debug(composeSpacingData(child1, child2, spacing));
    }
    return spacing;
  }

  @Nullable
  private Spacing spacingBetween(Block child1, Block child2) {
    if (!(child1 instanceof AbstractBlock block1) || !(child2 instanceof AbstractBlock block2)) return null;
    // inside a doc comment only line-leading indentation is managed: line
    // breaks and blank lines are markdown content (paragraphs) and are all kept
    if (elementType == DOC_COMMENT) return Spacing.createSpacing(0, UNBOUNDED, 0, true, UNBOUNDED);

    Pair pair = Pair.of(block1, block2);
    if (log.isTraceEnabled()) {
      log.trace(composeSpacingBlockData(pair));
    }
    for (BiFunction<HaxeSpacingProcessor, Pair, Spacing> phase : PHASES) {
      Spacing spacing = phase.apply(this, pair);
      if (spacing != null) return spacing;
    }
    return Spacing.createSpacing(0, 1, 0, true, common.KEEP_BLANK_LINES_IN_CODE);
  }

  /** The file's top: license header, package, the import/using section and the directives around it. */
  @Nullable
  private Spacing fileSectionSpacing(Pair pair) {
    // a block comment OPENING the file is a license header - it keeps a
    // minimum gap to whatever follows (doc comments attach to their member
    // and are not headers)
    boolean fileHeaderComment = pair.type1() == MML_COMMENT && pair.node1().getTreePrev() == null && node.getTreeParent() == null;
    if (fileHeaderComment && haxe.MINIMUM_BLANK_LINES_AFTER_FILE_HEADER > 0) {
      return blankLines(haxe.MINIMUM_BLANK_LINES_AFTER_FILE_HEADER, true, common.KEEP_BLANK_LINES_IN_CODE);
    }
    if (pair.type1() == PACKAGE_STATEMENT) return blankLines(common.BLANK_LINES_AFTER_PACKAGE, true, common.KEEP_BLANK_LINES_IN_CODE);
    Spacing importPair = importPairSpacing(pair);
    if (importPair != null) return importPair;
    Spacing directive = importSectionDirectiveSpacing(pair);
    if (directive != null) return directive;
    Spacing inlineDirective = inlineDirectiveSpacing(pair);
    if (inlineDirective != null) return inlineDirective;
    Spacing sectionEnd = importSectionEndSpacing(pair);
    if (sectionEnd != null) return sectionEnd;
    return elementType == IMPORT_WILDCARD ? spaceIf(false) : null;
  }

  /**
   * Grouping on: imports from different package groups get an exact gap,
   * same-group imports stay snug. Grouping off: the keep cap applies. Either
   * way the section-end rules own the blank after the section.
   */
  @Nullable
  private Spacing importPairSpacing(Pair pair) {
    boolean imports = pair.type1() == IMPORT_STATEMENT && pair.type2() == IMPORT_STATEMENT;
    boolean usings = pair.type1() == USING_STATEMENT && pair.type2() == USING_STATEMENT;
    if (imports && haxe.BLANK_LINES_BETWEEN_IMPORT_GROUPS > 0) {
      boolean sameGroup = importGroupKey(pair.node1()).equals(importGroupKey(pair.node2()));
      int blanks = sameGroup ? 0 : haxe.BLANK_LINES_BETWEEN_IMPORT_GROUPS;
      return blankLines(blanks, false, blanks);
    }
    if (imports || usings) return blankLines(0, true, haxe.KEEP_BLANK_LINES_BETWEEN_IMPORTS);
    return null;
  }

  /**
   * Blank-line handling for pairs touching a conditional-compilation token
   * near the import section: a directive whose conditional wraps imports is
   * covered by the section's keep cap (haxe-formatter's markImports), and
   * the closing #end of such a conditional takes over the section-end gap.
   * Null when the pair is not part of an import section (an inline #if in
   * an expression, a conditional around a type declaration).
   */
  @Nullable
  private Spacing importSectionDirectiveSpacing(Pair pair) {
    boolean cc1 = CONDITIONALLY_NOT_COMPILED.contains(pair.type1());
    boolean cc2 = CONDITIONALLY_NOT_COMPILED.contains(pair.type2());
    if (!cc1 && !cc2) return null;
    Spacing sectionClose = importSectionCloseSpacing(pair, cc2);
    if (sectionClose != null) return sectionClose;
    if (isImportOrUsing(pair.type1()) && cc2) {
      // an OPENING directive joins the section only when its conditional
      // holds imports; #else/#elseif/#end already belong to the section
      return continuesImportSection(pair.node2(), pair.type2()) ? importSectionKeepSpacing() : null;
    }
    if (cc1 && isImportOrUsing(pair.type2())) return importSectionKeepSpacing();
    if (cc1 && cc2) {
      boolean inSection = isImportSectionEdge(realNeighborType(pair.node1(), false))
                          || isImportOrUsing(realNeighborType(pair.node2(), true));
      return inSection ? importSectionKeepSpacing() : null;
    }
    return null;
  }

  /** The closing #end of the LAST import conditional carries the section-end gap - unless more section content follows it. */
  @Nullable
  private Spacing importSectionCloseSpacing(Pair pair, boolean cc2) {
    if (pair.type1() != PPEND || ONLY_COMMENTS.contains(pair.type2())) return null;
    IElementType lastSectionStatement = realNeighborType(pair.node1(), false);
    boolean sectionCloses = isImportOrUsing(lastSectionStatement)
                            && !isImportOrUsing(pair.type2())
                            && !(cc2 && continuesImportSection(pair.node2(), pair.type2()));
    if (!sectionCloses) return null;
    int blanks = lastSectionStatement == USING_STATEMENT ? haxe.MINIMUM_BLANK_LINES_AFTER_USING : common.BLANK_LINES_AFTER_IMPORTS;
    return blankLines(blanks, true, common.KEEP_BLANK_LINES_IN_CODE);
  }

  /**
   * Spacing for a pair whose edge token belongs to a conditional-compilation
   * region written on ONE line (haxe-formatter's inline sharp; the rule is
   * fixed, hxformat.json has no key for it): the pair stays on the line with
   * a space after #if/#elseif and after its condition, around #else, before
   * #end unless an opening bracket precedes it, and after #end unless a
   * closing bracket, comma, semicolon or dot follows. Inside the condition
   * the written spacing stays. Null for a multi-line region, a pair with no
   * directive at its edge, or a { body (the brace style places that).
   */
  @Nullable
  private Spacing inlineDirectiveSpacing(Pair pair) {
    if (pair.type2() == BLOCK_STATEMENT) return null;
    ASTNode edge1 = lastCodeLeaf(pair.node1());
    ASTNode edge2 = firstCodeLeaf(pair.node2());
    if (edge1 == null || edge2 == null) return null;
    IElementType edgeType1 = edge1.getElementType();
    IElementType edgeType2 = edge2.getElementType();
    boolean directive1 = CONDITIONALLY_NOT_COMPILED.contains(edgeType1);
    boolean directive2 = CONDITIONALLY_NOT_COMPILED.contains(edgeType2);
    if (!directive1 && !directive2) return null;
    if (!isInlineConditional(directive1 ? edge1 : edge2)) return null;
    // the lexer keeps a condition's own blanks as PPEXPRESSION tokens, which
    // the engine cannot rewrite (an in-place replacement is reported as growth
    // and skews every later edit): the empty gaps beside one stay empty
    if (isBlankConditionToken(edge1) || isBlankConditionToken(edge2)) return glued();
    int spaces = inlineDirectiveSpaces(edgeType1, edgeType2);
    if (spaces < 0) return Spacing.createSpacing(0, 1, 0, false, 0);
    return Spacing.createSpacing(spaces, spaces, 0, false, 0);
  }

  /** A comment inside the import section belongs to the import BELOW it - the section-end blank must not push it away from its import. */
  @Nullable
  private Spacing importSectionEndSpacing(Pair pair) {
    if (ONLY_COMMENTS.contains(pair.type2())) return null;
    if (pair.type1() == IMPORT_STATEMENT && pair.type2() != IMPORT_STATEMENT) {
      return blankLines(common.BLANK_LINES_AFTER_IMPORTS, true, common.KEEP_BLANK_LINES_IN_CODE);
    }
    if (pair.type1() == USING_STATEMENT && pair.type2() != USING_STATEMENT) {
      return blankLines(haxe.MINIMUM_BLANK_LINES_AFTER_USING, true, common.KEEP_BLANK_LINES_IN_CODE);
    }
    return null;
  }

  /** Type declarations: the class body's brace and the blank lines between and after types. */
  @Nullable
  private Spacing typeDeclarationSpacing(Pair pair) {
    IElementType type1 = pair.type1();
    IElementType type2 = pair.type2();
    if (isClassDeclaration(elementType) && isClassBodyType(type2)) {
      return braceSpacing(common.SPACE_BEFORE_CLASS_LBRACE, common.BRACE_STYLE, pair);
    }
    // adjacent ONE-LINE type declarations keep their own blank-line cap
    // (0 = snug); a multi-line neighbour follows the around-class rules
    boolean singleLineTypePair = isTypeDeclaration(type1) && isTypeDeclaration(type2)
                                 && !pair.node1().textContains('\n') && !pair.node2().textContains('\n');
    if (singleLineTypePair) return blankLines(0, true, haxe.KEEP_BLANK_LINES_BETWEEN_SINGLE_LINE_TYPES);
    // the gap between two types holds whatever introduces the next one - a
    // comment, metadata, a #if - and closes a region between them (#end):
    // at least the around-class count, at most the between-types cap; a
    // type followed by anything else only keeps its written gap
    if (betweenTypeDeclarations(pair.node1(), pair.node2())) {
      return blankLines(common.BLANK_LINES_AROUND_CLASS, true, haxe.KEEP_BLANK_LINES_BETWEEN_TYPES);
    }
    if (isTypeDeclaration(type1)) {
      return Spacing.createSpacing(0, 0, common.BLANK_LINES_AROUND_CLASS, true, common.KEEP_BLANK_LINES_IN_CODE);
    }
    // the end of a class body (after its right brace)
    if (isClassBodyType(type1)) return blankLines(0, false, common.KEEP_BLANK_LINES_IN_CODE);
    return null;
  }

  /** Inside a type body: the structure-extension line, the gaps at a body's braces, a typedef's one-field-per-line commas. */
  @Nullable
  private Spacing typeBodyBraceSpacing(Pair pair) {
    // a structure extension hugs a one-line body ({ > Base, ... }) and takes
    // its own line in a multi-line one; must precede the class-body { rule,
    // whose type-position arm keeps the pair as written (the OFF behavior)
    boolean structureExtension = elementType == ANONYMOUS_TYPE_BODY && pair.type1() == PLCURLY && pair.type2() == TYPE_EXTENDS_LIST;
    if (haxe.STRUCTURE_EXTENSION_ON_OWN_LINE && structureExtension) {
      return Spacing.createDependentLFSpacing(1, 1, node.getTextRange(), keepLineBreaks, common.KEEP_BLANK_LINES_IN_CODE);
    }
    Spacing afterBrace = afterOpeningBraceSpacing(pair);
    if (afterBrace != null) return afterBrace;
    Spacing beforeBrace = beforeClosingBraceSpacing(pair);
    if (beforeBrace != null) return beforeBrace;
    boolean typedefFieldComma = typedefLines && elementType == ANONYMOUS_TYPE_FIELD_LIST && pair.type1() == OCOMMA;
    return typedefFieldComma ? lineBreak() : null;
  }

  /**
   * After a body's '{' (type2 == PRCURLY is the EMPTY body - the before-}
   * rule keeps its caret line, which smart enter and live templates rely
   * on). A class body takes the exact after-header count (kept blanks would
   * defeat "0 after the header") unless it sits in a type position; a blank
   * hugging a plain block's brace has its own keep cap
   * (emptyLines.afterLeftCurly) and the pair otherwise behaves like the
   * fallback rule.
   */
  @Nullable
  private Spacing afterOpeningBraceSpacing(Pair pair) {
    if (pair.type1() != PLCURLY || pair.type2() == PRCURLY || !isFirstChild(pair.node1())) return null;
    if (!isClassBodyType(elementType)) return keepCappedBlanks(haxe.KEEP_BLANK_LINES_AFTER_LBRACE);
    int lineFeeds = inTypePosition && !typedefLines ? 0 : 1 + common.BLANK_LINES_AFTER_CLASS_HEADER;
    return Spacing.createSpacing(0, 0, lineFeeds, keepLineBreaks, common.BLANK_LINES_AFTER_CLASS_HEADER);
  }

  /**
   * Before a body's '}' (type1 == PLCURLY is the EMPTY body - kept as
   * written: {} stays inline, a caret line stays for smart enter). A class
   * body takes the exact before-end count unless it sits in a type
   * position; a plain block's blank has its own keep cap
   * (emptyLines.beforeRightCurly).
   */
  @Nullable
  private Spacing beforeClosingBraceSpacing(Pair pair) {
    if (pair.type2() != PRCURLY || pair.type1() == PLCURLY || !isLastChild(pair.node2())) return null;
    if (!isClassBodyType(elementType)) return keepCappedBlanks(common.KEEP_BLANK_LINES_BEFORE_RBRACE);
    int lineFeeds = inTypePosition && !typedefLines ? 0 : 1 + common.BLANK_LINES_BEFORE_CLASS_END;
    return Spacing.createSpacing(0, 0, lineFeeds, keepLineBreaks, common.KEEP_BLANK_LINES_BEFORE_RBRACE);
  }

  /**
   * A case's body under sameLine.caseBody - or expressionCase in a switch
   * used as a VALUE: Next breaks an inline body onto its own line, Same
   * joins it, Keep leaves the written line but always spaces the colon
   * (caseColonPolicy=onlyAfter); the case-colon blank cap
   * (emptyLines.beforeBlocks) applies, blanks BETWEEN cases keep the
   * in-code cap.
   */
  @Nullable
  private Spacing caseBodySpacing(Pair pair) {
    boolean caseBody = (elementType == SWITCH_CASE || elementType == DEFAULT_CASE) && pair.type2() == SWITCH_CASE_BLOCK;
    if (!caseBody) return null;
    int setting = isExpressionSwitchCase(node) ? haxe.VALUE_CASE_BODY_PLACEMENT : haxe.CASE_BODY_PLACEMENT;
    int placement = resolvedBodyPlacement(setting);
    if (placement == HaxeCodeStyleSettings.BODY_PLACEMENT_NEXT_LINE) return blankLines(0, false, haxe.KEEP_BLANK_LINES_AFTER_CASE_COLON);
    if (placement == HaxeCodeStyleSettings.BODY_PLACEMENT_SAME_LINE) return joined();
    return Spacing.createSpacing(1, 1, 0, true, haxe.KEEP_BLANK_LINES_AFTER_CASE_COLON);
  }

  /** Two block comments stacked on their own lines: emptyLines.betweenMultilineComments caps the blanks between them. */
  @Nullable
  private Spacing stackedCommentSpacing(Pair pair) {
    boolean stacked = pair.type1() == MML_COMMENT && pair.type2() == MML_COMMENT;
    return stacked ? keepCappedBlanks(haxe.KEEP_BLANK_LINES_BETWEEN_MULTILINE_COMMENTS) : null;
  }

  /** Blank lines between a type's members, a comment above a member resolved to that member. */
  @Nullable
  private Spacing memberSpacing(Pair pair) {
    Spacing documented = memberThenDocSpacing(pair);
    if (documented != null) return documented;
    Spacing commented = fieldThenCommentSpacing(pair);
    if (commented != null) return commented;
    Spacing members = memberGap(pair);
    if (members != null) return members;
    if (pair.type1() == DOC_COMMENT) return blankLines(0, false, common.KEEP_BLANK_LINES_IN_CODE);
    // a plain (MML/MSL) comment directly above a member stays directly
    // above it: one line break, written blanks within the in-code cap
    boolean commentThenMember = ONLY_COMMENTS.contains(pair.type1()) && isMemberDeclaration(pair.type2());
    return commentThenMember ? blankLines(0, true, common.KEEP_BLANK_LINES_IN_CODE) : null;
  }

  /** A blank line before a member belongs BEFORE its doc comment - the pair resolves as if the comment were the member's first line. */
  @Nullable
  private Spacing memberThenDocSpacing(Pair pair) {
    if (pair.type2() != DOC_COMMENT || !isMemberDeclaration(pair.type1())) return null;
    ASTNode documented = followingMember(pair.node2());
    IElementType documentedType = documented == null ? null : documented.getElementType();
    if (isMethodOrConstructorDeclaration(documentedType)) return methodGap();
    if (!isFieldDeclaration(documentedType)) return null;
    // a FIELD's doc comment also stands off from the previous field
    // (beforeDocCommentEmptyLines)
    int neighborGap = isMethodOrConstructorDeclaration(pair.type1())
                      ? common.BLANK_LINES_AROUND_METHOD
                      : fieldDocGap(pair.node1(), documented);
    return fieldGap(neighborGap);
  }

  /**
   * A plain comment travels with the field BELOW it too - a field-group or
   * documented-field blank must land before the comment, not between
   * comment and field. Without one the pair keeps its written shape.
   */
  @Nullable
  private Spacing fieldThenCommentSpacing(Pair pair) {
    if (!ONLY_COMMENTS.contains(pair.type2()) || !isFieldDeclaration(pair.type1())) return null;
    ASTNode commented = followingMember(pair.node2());
    if (commented == null || !isFieldDeclaration(commented.getElementType())) return null;
    int blanks = fieldExtraGap(pair.node1(), commented);
    return blanks > 0 ? blankLines(blanks, keepLineBreaks, common.KEEP_BLANK_LINES_IN_DECLARATIONS) : null;
  }

  /** The gap between two members: the method gap when either is a method, the field gap between two fields, null otherwise. */
  @Nullable
  private Spacing memberGap(Pair pair) {
    if (isFieldDeclaration(pair.type1()) && isFieldDeclaration(pair.type2())) {
      return fieldGap(fieldExtraGap(pair.node1(), pair.memberNode2()));
    }
    boolean members = isMemberDeclaration(pair.type1()) && isMemberDeclaration(pair.type2());
    return members ? methodGap() : null;
  }

  private Spacing methodGap() {
    return blankLines(common.BLANK_LINES_AROUND_METHOD, keepLineBreaks, common.KEEP_BLANK_LINES_IN_DECLARATIONS);
  }

  /** The around-field count, raised to whatever extra the pair demands (a group boundary, a documented field). */
  private Spacing fieldGap(int extraGap) {
    int blanks = Math.max(common.BLANK_LINES_AROUND_FIELD, extraGap);
    return blankLines(blanks, keepLineBreaks, common.KEEP_BLANK_LINES_IN_DECLARATIONS);
  }

  /** Where a statement's body goes: an empty {} collapses, a non-block body follows the sameLine policies, a named function's expression body its own. */
  @Nullable
  private Spacing bodyPlacementSpacing(Pair pair) {
    // an EMPTY body's braces collapse to {} when the matching keep-in-one-line
    // option allows it (class bodies excluded - smart enter owns their caret line)
    boolean emptyBody = pair.type1() == PLCURLY && pair.type2() == PRCURLY && !isClassBodyType(elementType);
    if (emptyBody && emptyBodyStaysInline(parentType)) return glued();
    Spacing valueExpression = valueExpressionSpacing(pair);
    if (valueExpression != null) return valueExpression;
    Spacing nonBlockBody = nonBlockBodySpacing(pair);
    if (nonBlockBody != null) return nonBlockBody;
    // a NAMED function's non-block body (function f() return x;) moves to
    // its own line; anonymous/arrow function bodies always stay inline. The
    // header's own trailing parts also follow a header end - only what
    // comes after the LAST of them is the body
    boolean expressionBody = NAMED_FUNCTIONS.contains(elementType)
                             && FUNCTION_HEADER_END.contains(pair.type1())
                             && !FUNCTION_HEADER_TRAILERS.contains(pair.type2());
    return haxe.FUNCTION_EXPRESSION_BODY_ON_NEXT_LINE && expressionBody ? lineBreak() : null;
  }

  /**
   * haxe-formatter's sameLine.*Body policies for a NON-BLOCK body: Next
   * forces it onto its own line, Same joins it onto the header's line, Keep
   * leaves it as written; block bodies follow the brace rules instead. A
   * for/while inside a literal is a COMPREHENSION, not a control statement
   * - its body always stays on the line.
   */
  @Nullable
  private Spacing nonBlockBodySpacing(Pair pair) {
    if (isComprehension(node)) return null;
    int placement = nonBlockBodyPlacement(pair);
    if (placement == HaxeCodeStyleSettings.BODY_PLACEMENT_NEXT_LINE) return lineBreak();
    if (placement == HaxeCodeStyleSettings.BODY_PLACEMENT_SAME_LINE) return joined();
    return null;
  }

  /**
   * wrapping.arrayWrap / mapWrap / objectLiteral on a literal's items
   * (HaxeLiteralItemRules): one per line starts every item and the closing
   * bracket on its own line, the leading-break fill only the first item and
   * the bracket (the items then re-pack under their wrap, written breaks
   * gone), keep joins every written break - an object's braces hug their
   * fields, as the tool prints them. Comments keep their own spacing; null
   * leaves the pair to the later phases.
   */
  @Nullable
  private Spacing literalItemSpacing(Pair pair) {
    ASTNode literal = literalOf();
    if (literal == null || COMMENTS.contains(pair.type1()) || COMMENTS.contains(pair.type2())) return null;
    Decision decision = HaxeLiteralItemRules.decide(literal, common, haxe);
    if (decision == Decision.NONE) return null;
    // a comma stays with the item before it under every decision
    if (pair.type2() == OCOMMA) return forcedGap(common.SPACE_BEFORE_COMMA);
    boolean bracket = pair.type1() == PLBRACK || pair.type2() == PRBRACK;
    boolean brace = pair.type1() == PLCURLY || pair.type2() == PRCURLY;
    return switch (decision) {
      case ONE_PER_LINE -> lineBreak();
      case FILL_AFTER_LEADING_BREAK -> bracket || brace ? lineBreak() : forcedGap(common.SPACE_AFTER_COMMA);
      case KEEP -> bracket ? forcedGap(common.SPACE_WITHIN_BRACKETS) : forcedGap(!brace && common.SPACE_AFTER_COMMA);
      case NONE -> null;
    };
  }

  /** The array, map or object literal whose brackets or items the pair sits between; null elsewhere. */
  @Nullable
  private ASTNode literalOf() {
    if (elementType == ARRAY_LITERAL || elementType == MAP_LITERAL || elementType == OBJECT_LITERAL) return node;
    ASTNode parent = node.getTreeParent();
    IElementType parentType = parent == null ? null : parent.getElementType();
    boolean arrayItems = elementType == EXPRESSION_LIST && parentType == ARRAY_LITERAL;
    boolean mapItems = elementType == MAP_INITIALIZER_EXPRESSION_LIST && parentType == MAP_LITERAL;
    return arrayItems || mapItems ? parent : null;
  }

  private static Spacing forcedGap(boolean space) {
    return space ? joined() : glued();
  }

  /** Square brackets, string interpolation braces and type-parameter angle brackets. */
  @Nullable
  private Spacing bracketSpacing(Pair pair) {
    IElementType type1 = pair.type1();
    IElementType type2 = pair.type2();
    // bracketConfig NoSpace: an access target keeps its '[' snug
    if (elementType == ARRAY_ACCESS_EXPRESSION && type2 == PLBRACK) return spaceIf(false);
    boolean bracketed = elementType == ARRAY_ACCESS_EXPRESSION || BRACKET_LITERALS.contains(elementType);
    if (bracketed && (type1 == PLBRACK || type2 == PRBRACK)) return spaceIf(common.SPACE_WITHIN_BRACKETS);
    // inside a string's ${ } interpolation braces; the embedded expression
    // itself formats under the normal rules
    boolean interpolationBrace = elementType == LONG_TEMPLATE_ENTRY && (type1 == LONG_TEMPLATE_ENTRY_START || type2 == LONG_TEMPLATE_ENTRY_END);
    if (interpolationBrace) return spaceIf(haxe.SPACE_WITHIN_STRING_INTERPOLATION);
    // type parameter/argument angle brackets: never a space between the name
    // and its '<'; inside the brackets per the Haxe spacing option
    if (type2 == TYPE_PARAM || type2 == GENERIC_PARAM) return spaceIf(false);
    boolean typeParams = elementType == TYPE_PARAM || elementType == GENERIC_PARAM;
    if (typeParams && (type1 == OLESS || type2 == OGREATER)) return spaceIf(haxe.SPACE_WITHIN_TYPE_PARAMETERS);
    return null;
  }

  /** The space before a '(' per statement kind, a switch's parens and brace, and the '> =' of a type parameter default. */
  @Nullable
  private Spacing parenBeforeSpacing(Pair pair) {
    if (pair.type2() == PLPAREN) {
      Spacing beforeParen = beforeParenSpacing();
      if (beforeParen != null) return beforeParen;
    }
    if (elementType == SWITCH_STATEMENT && pair.type2() == PARENTHESIZED_EXPRESSION) return spaceIf(common.SPACE_BEFORE_SWITCH_PARENTHESES);
    if (elementType == SWITCH_STATEMENT && pair.type2() == SWITCH_BLOCK) {
      return braceSpacing(common.SPACE_BEFORE_SWITCH_LBRACE, common.BRACE_STYLE, pair);
    }
    if (pair.type1() == OGREATER && pair.type2() == OASSIGN) return spaceIf(false);
    return null;
  }

  /** The space before the node's '(' per its kind; null for a node without such a setting. */
  @Nullable
  private Spacing beforeParenSpacing() {
    if (elementType == GUARD) return spaceIf(common.SPACE_BEFORE_IF_PARENTHESES);
    if (elementType == WHILE_STATEMENT || elementType == DO_WHILE_STATEMENT) return spaceIf(common.SPACE_BEFORE_WHILE_PARENTHESES);
    if (elementType == FOR_STATEMENT) return spaceIf(common.SPACE_BEFORE_FOR_PARENTHESES);
    if (elementType == TRY_STATEMENT) return spaceIf(common.SPACE_BEFORE_TRY_PARENTHESES);
    if (elementType == CATCH_STATEMENT) return spaceIf(common.SPACE_BEFORE_CATCH_PARENTHESES);
    if (FUNCTION_DEFINITION.contains(elementType)) return spaceIf(common.SPACE_BEFORE_METHOD_PARENTHESES);
    if (elementType == CALL_EXPRESSION) return spaceIf(common.SPACE_BEFORE_METHOD_CALL_PARENTHESES);
    return null;
  }

  /**
   * The '{' of a statement's block body. BLOCK_STATEMENTs that are the
   * single sub-element of an enclosing block, such as GUARDED_STATEMENT or
   * DO_WHILE_BODY, are presented as the enclosing statement type and NOT as
   * a separate BLOCK_STATEMENT sub-element.
   */
  @Nullable
  private Spacing braceBeforeSpacing(Pair pair) {
    // lineEnds.emptyCurly=NoBreak: an EMPTY body's {} stays on the header's
    // line even under next-line brace styles - the collapse rule above folds
    // the braces themselves, this pair keeps them from moving down
    if (isEmptyBlock(bodyBlockOf(pair)) && emptyBodyStaysInline(elementType)) return joined();
    boolean guardedBlock = pair.type2() == GUARDED_STATEMENT && pair.typeType2() == BLOCK_STATEMENT;
    if (elementType == IF_STATEMENT && guardedBlock) return braceSpacing(common.SPACE_BEFORE_IF_LBRACE, common.BRACE_STYLE, pair);
    boolean loopBlock = pair.type2() == DO_WHILE_BODY && pair.typeType2() == BLOCK_STATEMENT;
    if (loopBlock && elementType == WHILE_STATEMENT) return braceSpacing(common.SPACE_BEFORE_WHILE_LBRACE, common.BRACE_STYLE, pair);
    if (loopBlock && elementType == DO_WHILE_STATEMENT) return braceSpacing(common.SPACE_BEFORE_DO_LBRACE, common.BRACE_STYLE, pair);
    return pair.type2() == BLOCK_STATEMENT ? blockBraceSpacing(pair) : null;
  }

  /** The brace style before the node's own BLOCK_STATEMENT child; null for a node without a brace setting. */
  @Nullable
  private Spacing blockBraceSpacing(Pair pair) {
    if (elementType == ELSE_STATEMENT) return braceSpacing(common.SPACE_BEFORE_ELSE_LBRACE, common.BRACE_STYLE, pair);
    if (elementType == FOR_STATEMENT) return braceSpacing(common.SPACE_BEFORE_FOR_LBRACE, common.BRACE_STYLE, pair);
    if (elementType == TRY_STATEMENT) return braceSpacing(common.SPACE_BEFORE_TRY_LBRACE, common.BRACE_STYLE, pair);
    if (elementType == CATCH_STATEMENT) return braceSpacing(common.SPACE_BEFORE_CATCH_LBRACE, common.BRACE_STYLE, pair);
    if (FUNCTION_DEFINITION.contains(elementType)) return braceSpacing(common.SPACE_BEFORE_METHOD_LBRACE, common.METHOD_BRACE_STYLE, pair);
    return null;
  }

  /** The space inside '(...)' per kind; parameter and call parens may also open/close on their own line. */
  @Nullable
  private Spacing parenWithinSpacing(Pair pair) {
    IElementType type1 = pair.type1();
    if (type1 != PLPAREN && pair.type2() != PRPAREN) return null;
    Spacing filled = filledListParenSpacing(pair);
    if (filled != null) return filled;
    if (elementType == GUARD) return spaceIf(common.SPACE_WITHIN_IF_PARENTHESES);
    if (elementType == WHILE_STATEMENT || elementType == DO_WHILE_STATEMENT) return spaceIf(common.SPACE_WITHIN_WHILE_PARENTHESES);
    if (elementType == FOR_STATEMENT) return spaceIf(common.SPACE_WITHIN_FOR_PARENTHESES);
    if (parentType == SWITCH_STATEMENT && elementType == PARENTHESIZED_EXPRESSION) return spaceIf(common.SPACE_WITHIN_SWITCH_PARENTHESES);
    if (elementType == TRY_STATEMENT) return spaceIf(common.SPACE_WITHIN_TRY_PARENTHESES);
    if (elementType == CATCH_STATEMENT) return spaceIf(common.SPACE_WITHIN_CATCH_PARENTHESES);
    if (FUNCTION_DEFINITION.contains(elementType)) {
      boolean ownLine = parenOnNextLine(type1, common.METHOD_PARAMETERS_LPAREN_ON_NEXT_LINE, common.METHOD_PARAMETERS_RPAREN_ON_NEXT_LINE);
      return spaceAndBreakIf(common.SPACE_WITHIN_METHOD_PARENTHESES, ownLine);
    }
    if (elementType == CALL_EXPRESSION) {
      boolean ownLine = parenOnNextLine(type1, common.CALL_PARAMETERS_LPAREN_ON_NEXT_LINE, common.CALL_PARAMETERS_RPAREN_ON_NEXT_LINE);
      return spaceAndBreakIf(common.SPACE_WITHIN_METHOD_CALL_PARENTHESES, ownLine);
    }
    if (elementType == PARENTHESIZED_EXPRESSION) {
      // plain grouping parens; their own line breaks apply only under
      // binary-operation wrapping
      boolean wraps = common.BINARY_OPERATION_WRAP != CommonCodeStyleSettings.DO_NOT_WRAP;
      boolean ownLine = wraps && parenOnNextLine(type1, common.PARENTHESES_EXPRESSION_LPAREN_WRAP, common.PARENTHESES_EXPRESSION_RPAREN_WRAP);
      return spaceAndBreakIf(common.SPACE_WITHIN_PARENTHESES, ownLine);
    }
    return null;
  }

  /** The setting for the pair's paren: the '(' setting when the pair opens with one, else the ')' setting. */
  private static boolean parenOnNextLine(IElementType type1, boolean lparenOnNextLine, boolean rparenOnNextLine) {
    return type1 == PLPAREN ? lparenOnNextLine : rparenOnNextLine;
  }

  /** Object-literal colons, the ternary's sides, and the braces of a value block, a typedef body or a hinted anonymous type. */
  @Nullable
  private Spacing literalAndValueBlockSpacing(Pair pair) {
    // object literal field colon ({a: 1}) - hxformat's objectFieldColonPolicy
    if (elementType == OBJECT_LITERAL_ELEMENT && pair.type2() == OCOLON) return spaceIf(haxe.SPACE_BEFORE_OBJECT_FIELD_COLON);
    if (elementType == OBJECT_LITERAL_ELEMENT && pair.type1() == OCOLON) return spaceIf(haxe.SPACE_AFTER_OBJECT_FIELD_COLON);
    Spacing ternary = elementType == TERNARY_EXPRESSION ? ternarySpacing(pair) : null;
    if (ternary != null) return ternary;
    // a block used as a VALUE (x = { ... }) opens under the brace style like
    // any other block - haxe-formatter's leftCurly covers every { but an
    // object literal's; a typedef's body brace too (lineEnds.typedefCurly
    // follows leftCurly)
    boolean valueBlock = pair.type2() == VALUE_INIT_BLOCK && (elementType == VAR_INIT || elementType == ASSIGN_EXPRESSION);
    boolean typedefBody = elementType == TYPEDEF_DECLARATION && pair.type1() == OASSIGN && pair.typeType2() == ANONYMOUS_TYPE;
    if (valueBlock || typedefBody) return braceSpacing(common.SPACE_AROUND_ASSIGNMENT_OPERATORS, common.BRACE_STYLE, pair);
    // a multi-line anonymous type in a type hint opens on the next line, one
    // step in (lineEnds.anonTypeCurly follows leftCurly); a one-line one
    // stays on the hint's line
    boolean hintedAnonymousType = elementType == TYPE_TAG && pair.type1() == OCOLON && pair.typeType2() == ANONYMOUS_TYPE;
    if (hintedAnonymousType && nextLineBraces(common) && pair.node2().textContains('\n')) return lineBreak();
    return null;
  }

  /** The four sides of a ?: operator, each per its own setting. */
  @Nullable
  private Spacing ternarySpacing(Pair pair) {
    if (pair.typeType2() == OQUEST) return spaceIf(common.SPACE_BEFORE_QUEST);
    if (pair.typeType2() == OCOLON) return spaceIf(common.SPACE_BEFORE_COLON);
    if (pair.typeType1() == OQUEST) return spaceIf(common.SPACE_AFTER_QUEST);
    if (pair.typeType1() == OCOLON) return spaceIf(common.SPACE_AFTER_COLON);
    return null;
  }

  /** Assignment operators (=, -=, ...) and the ';' ending a statement. */
  @Nullable
  private Spacing assignmentSpacing(Pair pair) {
    if (isSpacedAssignment(pair)) return spaceIf(common.SPACE_AROUND_ASSIGNMENT_OPERATORS);
    if (pair.type2() == OSEMI) return Spacing.createSpacing(0, 0, 0, true, 1);
    return null;
  }

  /**
   * An assignment sign on either side of the pair - the sign itself or
   * wrapped in its operator element - meeting a COMPOSITE right-hand node:
   * a bare token there (the ';' of an empty initializer, a directive) keeps
   * its own rule, and an XML attribute's '=' is markup, not an assignment.
   */
  private boolean isSpacedAssignment(Pair pair) {
    boolean assignment = ASSIGN_OPERATORS.contains(pair.type1())
                         || ASSIGN_OPERATORS.contains(pair.typeType1())
                         || ASSIGN_OPERATORS.contains(pair.typeType2());
    return assignment && pair.typeType2() != null && !inXmlTag();
  }

  private boolean inXmlTag() {
    return elementType == XML_MARKUP_ATTRIBUTE || parentType == XML_LITERAL_EXPRESSION;
  }

  /** Binary and unary operators per their settings; a logic or additive chain may break before its operator first. */
  @Nullable
  private Spacing operatorSpacing(Pair pair) {
    boolean logicChain = elementType == LOGIC_AND_EXPRESSION || elementType == LOGIC_OR_EXPRESSION;
    Spacing logicBreak = logicChain ? chainBreakSpacing(Kind.LOGIC, LOGIC_OPERATORS, pair) : null;
    if (logicBreak != null) return logicBreak;
    if (wrapsOperator(pair, LOGIC_OPERATORS)) return spaceIf(common.SPACE_AROUND_LOGICAL_OPERATORS);
    Spacing compare = compareSpacing(pair);
    if (compare != null) return compare;
    if (wrapsOperator(pair, BITWISE_OPERATORS)) return spaceIf(common.SPACE_AROUND_BITWISE_OPERATORS);
    Spacing additiveBreak = elementType == ADDITIVE_EXPRESSION ? chainBreakSpacing(Kind.ADDITIVE, ADDITIVE_OPERATORS, pair) : null;
    if (additiveBreak != null) return additiveBreak;
    if (wrapsOperator(pair, ADDITIVE_OPERATORS) && elementType != PREFIX_EXPRESSION) return spaceIf(common.SPACE_AROUND_ADDITIVE_OPERATORS);
    if (wrapsOperator(pair, MULTIPLICATIVE_OPERATORS)) return spaceIf(common.SPACE_AROUND_MULTIPLICATIVE_OPERATORS);
    if (wrapsOperator(pair, UNARY_OPERATORS) && elementType == PREFIX_EXPRESSION) return spaceIf(common.SPACE_AROUND_UNARY_OPERATOR);
    // >> and >>> arrive as composite operator elements over split '>' tokens
    // (generics-friendly lexing), so the ELEMENT types match too, not only
    // the wrapped token of a one-token operator
    boolean shift = SHIFT_OPERATORS.contains(pair.type1()) || SHIFT_OPERATORS.contains(pair.type2()) || wrapsOperator(pair, SHIFT_OPERATORS);
    if (shift) return spaceIf(common.SPACE_AROUND_SHIFT_OPERATORS);
    // the split '>' tokens INSIDE such an operator must stay glued
    if (SHIFT_OPERATORS.contains(elementType)) return glued();
    return null;
  }

  /**
   * wrapping.opBoolChain / opAddSubChain: a chain that explodes, or fills
   * past the margin, breaks before the operator with the operator LEADING
   * (HaxeOperatorChainRules). Null when the chain rules leave the pair alone.
   */
  @Nullable
  private Spacing chainBreakSpacing(Kind kind, TokenSet operators, Pair pair) {
    if (operators.contains(pair.typeType2()) && chainBreaksBefore(kind, pair.node2())) return lineBreak();
    if (operators.contains(pair.typeType1()) && chainBreaksBefore(kind, pair.node1())) return joined();
    return null;
  }

  /** Equality (==, !=) and relational (<, <=, ...) operators, each per its own setting; both arrive wrapped in a COMPARE_OPERATION. */
  @Nullable
  private Spacing compareSpacing(Pair pair) {
    if (wrapsCompareOperator(pair, EQUALITY_OPERATORS)) return spaceIf(common.SPACE_AROUND_EQUALITY_OPERATORS);
    if (wrapsCompareOperator(pair, RELATIONAL_OPERATORS)) return spaceIf(common.SPACE_AROUND_RELATIONAL_OPERATORS);
    return null;
  }

  private static boolean wrapsCompareOperator(Pair pair, TokenSet operators) {
    return (pair.type1() == COMPARE_OPERATION && operators.contains(pair.typeType1()))
           || (pair.type2() == COMPARE_OPERATION && operators.contains(pair.typeType2()));
  }

  /** Either node of the pair is an operator element wrapping one of the operators. */
  private static boolean wrapsOperator(Pair pair, TokenSet operators) {
    return operators.contains(pair.typeType1()) || operators.contains(pair.typeType2());
  }

  /** The else/while/catch keywords after their bodies, and "else if". */
  @Nullable
  private Spacing keywordSpacing(Pair pair) {
    IElementType type1 = pair.type1();
    IElementType type2 = pair.type2();
    if (type2 == ELSE_STATEMENT) {
      return keywordPlacement(common.SPACE_BEFORE_ELSE_KEYWORD, common.ELSE_ON_NEW_LINE, pair.node1(), haxe.IF_BODY_PLACEMENT);
    }
    if (type2 == KWHILE) {
      return keywordPlacement(common.SPACE_BEFORE_WHILE_KEYWORD, common.WHILE_ON_NEW_LINE, pair.node1(), haxe.DO_WHILE_BODY_PLACEMENT);
    }
    if (type2 == CATCH_STATEMENT) {
      int precedingBody = type1 == CATCH_STATEMENT ? haxe.CATCH_BODY_PLACEMENT : haxe.TRY_BODY_PLACEMENT;
      return keywordPlacement(common.SPACE_BEFORE_CATCH_KEYWORD, common.CATCH_ON_NEW_LINE, pair.node1(), precedingBody);
    }
    // "else if" - the if inside the ELSE_STATEMENT
    if (type1 == KELSE && type2 == IF_STATEMENT) {
      int lineFeeds = common.SPECIAL_ELSE_IF_TREATMENT ? 0 : 1;
      return Spacing.createSpacing(1, 1, lineFeeds, false, common.KEEP_BLANK_LINES_IN_CODE);
    }
    return null;
  }

  /** A multi-var's split, the call-argument fill, and commas. */
  @Nullable
  private Spacing listSpacing(Pair pair) {
    IElementType type1 = pair.type1();
    // wrapping.multiVar: a multi-var whose JOINED line would pass the split
    // width breaks after every comma; under the width the written shape is
    // kept (the tool's length-based joins are not reproduced)
    boolean multiVarItem = elementType == LOCAL_VAR_DECLARATION_LIST && type1 == OCOMMA && pair.type2() == LOCAL_VAR_DECLARATION;
    if (multiVarItem && HaxeMultiVarSplit.splits(node, common, haxe)) return lineBreak();
    // wrapping.callParameter/functionSignature fillLine judged on the JOINED
    // line: a written break between arguments goes, and the arguments the
    // tool moves down start their line (HaxeCallArgumentFill)
    if (haxe.FILL_CALL_ARGUMENTS_ON_JOINED_LINE) {
      Spacing fill = callFillSpacing(pair);
      if (fill != null) return fill;
    }
    if (type1 == OCOMMA) return spaceIf(isArgumentList() ? common.SPACE_AFTER_COMMA_IN_TYPE_ARGUMENTS : common.SPACE_AFTER_COMMA);
    if (pair.type2() == OCOMMA) return spaceIf(common.SPACE_BEFORE_COMMA);
    return null;
  }

  /** The node is the argument list of a call, a new or a function. */
  private boolean isArgumentList() {
    return ARGUMENT_LISTS.contains(elementType)
           && (parentType == CALL_EXPRESSION || parentType == NEW_EXPRESSION || FUNCTION_DEFINITION.contains(parentType));
  }

  /** The remaining colons and arrows, metadata parens and the return keyword. */
  @Nullable
  private Spacing colonAndMiscSpacing(Pair pair) {
    IElementType type1 = pair.type1();
    IElementType type2 = pair.type2();
    // the (expr : Type) type-check colon, spaced UNLIKE type-hint colons
    if (elementType == TYPE_CHECK_EXPR && (type1 == OCOLON || type2 == OCOLON)) return spaceIf(haxe.SPACE_AROUND_TYPE_CHECK_COLON);
    Spacing metadata = metadataParenSpacing(pair);
    if (metadata != null) return metadata;
    // a return's value joins the keyword's line; the value's own internals
    // may still break
    boolean returnValue = elementType == RETURN_STATEMENT && type1 == KRETURN && type2 != OSEMI;
    if (haxe.RETURN_VALUE_ON_SAME_LINE && returnValue) return joined();
    if (type1 == OCOLON && elementType == TYPE_TAG) return spaceIf(haxe.SPACE_AFTER_TYPE_REFERENCE_COLON);
    if (type2 == TYPE_TAG) return spaceIf(haxe.SPACE_BEFORE_TYPE_REFERENCE_COLON);
    if (type1 == OARROW) return spaceIf(arrowSpaced(pair.node1()));
    if (type2 == OARROW) return spaceIf(arrowSpaced(pair.node2()));
    return null;
  }

  /** Metadata parens - metadata has its own token set, hence the qualified names. The @:name-to-( gap is always snug; inside per the option. */
  @Nullable
  private Spacing metadataParenSpacing(Pair pair) {
    boolean insideMeta = elementType == HaxeMetadataTokenTypes.COMPILE_TIME_META || elementType == HaxeMetadataTokenTypes.RUN_TIME_META;
    if (!insideMeta) return null;
    if (pair.type2() == HaxeMetadataTokenTypes.PLPAREN) return spaceIf(false);
    if (pair.type1() == HaxeMetadataTokenTypes.PLPAREN || pair.type2() == HaxeMetadataTokenTypes.PRPAREN) {
      return spaceIf(haxe.SPACE_WITHIN_METADATA_PARENTHESES);
    }
    return null;
  }

  /**
   * An if/try used as a VALUE ({@code var x = if (c) a else b;}) under its
   * own placement: Same joins condition, bodies and keywords onto one line,
   * Keep re-breaks exactly where the source broke (a forced break, so a
   * pass that drops custom line breaks never pulls an else or catch up),
   * Next hands the pieces to the statement rules. A block body keeps the
   * brace rules, and a keyword after its closing brace follows the
   * statement rules unless kept.
   */
  @Nullable
  private Spacing valueExpressionSpacing(Pair pair) {
    int placement = valueExpressionPlacement(pair);
    if (placement == HaxeCodeStyleSettings.BODY_PLACEMENT_SAME_LINE) return joined();
    if (placement == HaxeCodeStyleSettings.BODY_PLACEMENT_KEEP) return writtenBreakBefore(pair.node2()) ? lineBreak() : joined();
    return null;
  }

  /**
   * The placement of the pair as a piece of a value-position if/try -
   * condition to body, body to else/catch, keyword to body; NEXT_LINE when
   * the pair is no such piece or the policy leaves it to the statement rules.
   */
  private int valueExpressionPlacement(Pair pair) {
    IElementType type1 = pair.type1();
    IElementType type2 = pair.type2();
    if (elementType == IF_STATEMENT) {
      boolean conditionThenBody = type1 == GUARD && type2 == GUARDED_STATEMENT && pair.typeType2() != BLOCK_STATEMENT;
      boolean bodyThenElse = type1 == GUARDED_STATEMENT && type2 == ELSE_STATEMENT;
      if (conditionThenBody) return valuePlacement(node, haxe.VALUE_IF_BODY_PLACEMENT);
      if (bodyThenElse) return keywordAfterBodyPlacement(pair.node1(), valuePlacement(node, haxe.VALUE_IF_BODY_PLACEMENT));
    }
    if (elementType == ELSE_STATEMENT && type1 == KELSE && type2 != BLOCK_STATEMENT && type2 != IF_STATEMENT) {
      return valuePlacement(node.getTreeParent(), haxe.VALUE_IF_BODY_PLACEMENT);
    }
    if (elementType == TRY_STATEMENT) {
      boolean tryThenBody = type1 == KTRY && type2 != BLOCK_STATEMENT && type2 != CATCH_STATEMENT;
      if (tryThenBody) return valuePlacement(node, haxe.VALUE_TRY_BODY_PLACEMENT);
      if (type2 == CATCH_STATEMENT) return keywordAfterBodyPlacement(pair.node1(), valuePlacement(node, haxe.VALUE_TRY_BODY_PLACEMENT));
    }
    if (elementType == CATCH_STATEMENT && type1 == PRPAREN && type2 != BLOCK_STATEMENT) {
      return valuePlacement(node.getTreeParent(), haxe.VALUE_TRY_BODY_PLACEMENT);
    }
    return HaxeCodeStyleSettings.BODY_PLACEMENT_NEXT_LINE;
  }

  /** The resolved value placement when the statement sits in value position, else NEXT_LINE (the statement rules). */
  private int valuePlacement(ASTNode statement, int setting) {
    return isExpressionPosition(statement) ? resolvedBodyPlacement(setting) : HaxeCodeStyleSettings.BODY_PLACEMENT_NEXT_LINE;
  }

  /** An else/catch after a block body joins only under Keep; the brace policies decide otherwise. */
  private static int keywordAfterBodyPlacement(ASTNode body, int placement) {
    boolean afterBlock = endsWithRightCurly(body);
    return afterBlock && placement == HaxeCodeStyleSettings.BODY_PLACEMENT_SAME_LINE
           ? HaxeCodeStyleSettings.BODY_PLACEMENT_NEXT_LINE
           : placement;
  }

  /** The statement is a value if/try whose pieces the value placement lays out itself. */
  private boolean isValueExpression(ASTNode statement, int setting) {
    return valuePlacement(statement, setting) != HaxeCodeStyleSettings.BODY_PLACEMENT_NEXT_LINE;
  }

  /** The whitespace before the node carries a line break in the source. */
  private static boolean writtenBreakBefore(ASTNode node) {
    ASTNode previous = node.getTreePrev();
    return previous != null && WHITESPACES.contains(previous.getElementType()) && previous.textContains('\n');
  }

  /**
   * The configured placement for a (header, non-block body) pair - or KEEP
   * when the pair is no such thing. The pieces of a value if/try laid out
   * by the value placement are exempt.
   */
  private int nonBlockBodyPlacement(Pair pair) {
    IElementType type1 = pair.type1();
    IElementType type2 = pair.type2();
    IElementType typeType2 = pair.typeType2();
    if (elementType == IF_STATEMENT && type2 == GUARDED_STATEMENT && typeType2 != BLOCK_STATEMENT
        && !isValueExpression(node, haxe.VALUE_IF_BODY_PLACEMENT)) {
      return resolvedBodyPlacement(haxe.IF_BODY_PLACEMENT);
    }
    if (elementType == ELSE_STATEMENT && type1 == KELSE && type2 != BLOCK_STATEMENT && type2 != IF_STATEMENT
        && !isValueExpression(node.getTreeParent(), haxe.VALUE_IF_BODY_PLACEMENT)) {
      return resolvedBodyPlacement(haxe.ELSE_BODY_PLACEMENT);
    }
    if (type2 == DO_WHILE_BODY && typeType2 != BLOCK_STATEMENT) {
      return resolvedBodyPlacement(elementType == DO_WHILE_STATEMENT ? haxe.DO_WHILE_BODY_PLACEMENT : haxe.WHILE_BODY_PLACEMENT);
    }
    if (elementType == FOR_STATEMENT && type1 == PRPAREN && type2 != BLOCK_STATEMENT) {
      return resolvedBodyPlacement(haxe.FOR_BODY_PLACEMENT);
    }
    if (elementType == TRY_STATEMENT && type1 == KTRY && type2 != BLOCK_STATEMENT && type2 != CATCH_STATEMENT
        && !isValueExpression(node, haxe.VALUE_TRY_BODY_PLACEMENT)) {
      return resolvedBodyPlacement(haxe.TRY_BODY_PLACEMENT);
    }
    if (elementType == CATCH_STATEMENT && type1 == PRPAREN && type2 != BLOCK_STATEMENT
        && !isValueExpression(node.getTreeParent(), haxe.VALUE_TRY_BODY_PLACEMENT)) {
      return resolvedBodyPlacement(haxe.CATCH_BODY_PLACEMENT);
    }
    return HaxeCodeStyleSettings.BODY_PLACEMENT_KEEP;
  }

  /** DEFAULT defers to the IDE's own "keep control statement in one line" checkbox. */
  private int resolvedBodyPlacement(int placement) {
    if (placement != HaxeCodeStyleSettings.BODY_PLACEMENT_DEFAULT) return placement;
    return common.KEEP_CONTROL_STATEMENT_IN_ONE_LINE
           ? HaxeCodeStyleSettings.BODY_PLACEMENT_KEEP
           : HaxeCodeStyleSettings.BODY_PLACEMENT_NEXT_LINE;
  }

  private static boolean isBlankConditionToken(ASTNode leaf) {
    return leaf.getElementType() == PPEXPRESSION && leaf.getText().isBlank();
  }

  /** Spaces between an inline region's edge tokens; -1 keeps the written 0..1. */
  private int inlineDirectiveSpaces(IElementType edge1, IElementType edge2) {
    if (edge2 == PPIF) {
      if (OPENING_BRACKETS.contains(edge1)) return 0;
      boolean typeHintColon = edge1 == OCOLON && elementType == TYPE_TAG;
      if (typeHintColon) return haxe.SPACE_AFTER_TYPE_REFERENCE_COLON ? 1 : 0;
      return 1;
    }
    if (edge1 == PPEXPRESSION && edge2 == PPEXPRESSION) return -1;
    if (edge2 == PPEND) return OPENING_BRACKETS.contains(edge1) ? 0 : 1;
    if (edge1 == PPEND) return HUGS_CLOSING_DIRECTIVE.contains(edge2) ? 0 : 1;
    return 1;
  }

  /** The #if..#end region holding the directive token is written on one line. */
  private static boolean isInlineConditional(ASTNode token) {
    if (token.textContains('\n')) return false;
    IElementType type = token.getElementType();
    boolean newlineBefore = type != PPIF && breaksBeforeRegionEnd(regionOpener(token));
    boolean newlineAfter = type != PPEND && breaksBeforeRegionEnd(regionCloser(token));
    return !newlineBefore && !newlineAfter;
  }

  /** A newline lies between the token and its region's end, or the region is unterminated that way. */
  private static boolean breaksBeforeRegionEnd(RegionEnd end) {
    return end.directive() == null || end.crossedNewline();
  }

  /** The node's first leaf that is not whitespace-only (a chameleon body's edge whitespace is skipped, as the block builder does). */
  @Nullable
  private static ASTNode firstCodeLeaf(ASTNode node) {
    int end = node.getStartOffset() + node.getTextLength();
    PsiElement leaf = PsiTreeUtil.getDeepestFirst(node.getPsi());
    while (leaf != null && leaf.getTextRange().getStartOffset() < end) {
      if (!FormatterUtil.containsWhiteSpacesOnly(leaf.getNode())) return leaf.getNode();
      leaf = PsiTreeUtil.nextLeaf(leaf);
    }
    return null;
  }

  /** The node's last leaf that is not whitespace-only. */
  @Nullable
  private static ASTNode lastCodeLeaf(ASTNode node) {
    int start = node.getStartOffset();
    PsiElement leaf = PsiTreeUtil.getDeepestLast(node.getPsi());
    while (leaf != null && leaf.getTextRange().getEndOffset() > start) {
      if (!FormatterUtil.containsWhiteSpacesOnly(leaf.getNode())) return leaf.getNode();
      leaf = PsiTreeUtil.prevLeaf(leaf);
    }
    return null;
  }

  /** The setting for an arrow's kind: arrow function, Haxe 4 function type or Haxe 3 function type. */
  private boolean arrowSpaced(ASTNode arrow) {
    ASTNode parent = arrow.getTreeParent();
    if (parent == null || parent.getElementType() != FUNCTION_TYPE) return haxe.SPACE_AROUND_ARROW;
    return isNewFunctionTypeArrow(arrow)
           ? haxe.SPACE_AROUND_FUNCTION_TYPE_ARROW
           : haxe.SPACE_AROUND_OLD_FUNCTION_TYPE_ARROW;
  }

  /**
   * A Haxe 4 function-type arrow follows a parenthesized argument list
   * (() -> Void, (Int) -> Void, (a:Int, b:Int) -> Int); parens that merely
   * group a function type ((Int->Int)->Int) keep the Haxe 3 kind, as
   * haxe-formatter classifies them. The parser hands a single parenthesized
   * argument out as a FUNCTION_ARGUMENT, a list as bare parens.
   */
  private static boolean isNewFunctionTypeArrow(ASTNode arrow) {
    ASTNode before = realNeighbor(arrow, false);
    if (before == null) return false;
    if (before.getElementType() == PRPAREN) return true;
    if (before.getElementType() != FUNCTION_ARGUMENT) return false;
    ASTNode first = before.getFirstChildNode();
    return first != null && first.getElementType() == PLPAREN && before.findChildByType(FUNCTION_TYPE) == null;
  }

  /** More import-section content behind the directive: an import conditional opening, or a continuation of one. */
  private static boolean continuesImportSection(@NotNull ASTNode node, @NotNull IElementType type) {
    // #else/#elseif/#end belong to the enclosing conditional either way
    return type != PPIF || conditionalWrapsImports(node);
  }

  private Spacing importSectionKeepSpacing() {
    return keepCappedBlanks(haxe.KEEP_BLANK_LINES_BETWEEN_IMPORTS);
  }

  /** The fallback pair shape with a tighter blank-line cap: single space at most, written breaks kept. */
  private static Spacing keepCappedBlanks(int keepBlankLines) {
    return Spacing.createSpacing(0, 1, 0, true, keepBlankLines);
  }

  /** A forced line break, nothing kept: the second node starts its own line. */
  private static Spacing lineBreak() {
    return Spacing.createSpacing(0, 0, 1, false, 0);
  }

  /** A forced join with one space, nothing kept: the pair stays on one line. */
  private static Spacing joined() {
    return Spacing.createSpacing(1, 1, 0, false, 0);
  }

  /** No whitespace at all. */
  private static Spacing glued() {
    return Spacing.createSpacing(0, 0, 0, false, 0);
  }

  /** A gap of blank lines - BLANK_LINES_* count blank lines, Spacing counts LINE FEEDS (one more). */
  private static Spacing blankLines(int blanks, boolean keepBreaks, int keepBlanks) {
    return Spacing.createSpacing(0, 0, 1 + blanks, keepBreaks, keepBlanks);
  }

  /**
   * A comma pair in a filled list: the argument after the comma joins the
   * line, or starts its own when the fill moves it down. A pair beside a
   * comment keeps the general rules.
   */
  @Nullable
  private Spacing callFillSpacing(Pair pair) {
    ASTNode argument = pair.node2();
    if (pair.type1() != OCOMMA || LIST_PUNCTUATION.contains(pair.type2()) || COMMENTS.contains(pair.type2())) return null;
    ASTNode list = HaxeCallArgumentFill.filledListOf(argument);
    if (list == null) return null;
    return HaxeCallArgumentFill.brokenArguments(list, common, haxe).contains(argument) ? lineBreak() : joined();
  }

  /**
   * The gap between a filled list and its parens under the joined-line fill:
   * the tool re-joins it, so a written break before the first argument or
   * the closing paren goes. Nothing written after the opening paren stays
   * read-only, so the margin wrap cannot move the first argument down - the
   * tool never does, however long it is.
   */
  @Nullable
  private Spacing filledListParenSpacing(Pair pair) {
    if (!haxe.FILL_CALL_ARGUMENTS_ON_JOINED_LINE) return null;
    boolean opening = pair.type1() == PLPAREN;
    ASTNode inner = opening ? pair.node2() : pair.node1();
    IElementType innerType = inner.getElementType();
    if (LIST_PUNCTUATION.contains(innerType) || COMMENTS.contains(innerType)) return null;
    if (HaxeCallArgumentFill.filledListOf(inner) == null) return null;
    boolean gapWritten = pair.node1().getTextRange().getEndOffset() < pair.node2().getStartOffset();
    return opening && !gapWritten ? Spacing.getReadOnlySpacing() : glued();
  }

  /** The node is a typedef's body (or its field list) under a next-line brace style. */
  private static boolean typedefBodyExpands(ASTNode node, CommonCodeStyleSettings common) {
    if (!nextLineBraces(common)) return false;
    IElementType type = node.getElementType();
    if (type == ANONYMOUS_TYPE_BODY) return isTypedefBody(node);
    if (type != ANONYMOUS_TYPE_FIELD_LIST) return false;
    ASTNode body = node.getTreeParent();
    return body != null && isTypedefBody(body);
  }

  /** The anonymous type body of a typedef declaration (body - type - wrapper - typedef). */
  private static boolean isTypedefBody(ASTNode body) {
    ASTNode type = body.getTreeParent();
    ASTNode wrapper = type == null ? null : type.getTreeParent();
    ASTNode owner = wrapper == null ? null : wrapper.getTreeParent();
    return owner != null && owner.getElementType() == TYPEDEF_DECLARATION;
  }

  private boolean chainBreaksBefore(Kind kind, ASTNode operator) {
    return HaxeOperatorChainRules.breaksBefore(kind, operator, common, haxe);
  }

  private static boolean isImportOrUsing(@Nullable IElementType type) {
    return type == IMPORT_STATEMENT || type == USING_STATEMENT;
  }

  private static boolean isImportSectionEdge(@Nullable IElementType type) {
    return isImportOrUsing(type) || type == PACKAGE_STATEMENT;
  }

  /**
   * The first real content after an opening directive is an import/using -
   * parsed (active branch), or as an INACTIVE branch's text, which the
   * neighbor scan cannot see into. PPBODY is a PsiComment, so the
   * whitespace-and-comment skips would step over the very branch inspected.
   */
  private static boolean conditionalWrapsImports(ASTNode directive) {
    for (ASTNode n = directive.getTreeNext(); n != null; n = n.getTreeNext()) {
      IElementType type = n.getElementType();
      if (WHITESPACES.contains(type) || ONLY_COMMENTS.contains(type)) continue;
      if (type == PPBODY) {
        String content = n.getText().strip();
        return content.startsWith("import ") || content.startsWith("using ");
      }
      if (CONDITIONALLY_NOT_COMPILED.contains(type)) continue;
      return isImportOrUsing(type);
    }
    return false;
  }

  /** The case belongs to a switch used as a VALUE - its bodies keep their written line per expressionCase=keep. */
  private static boolean isExpressionSwitchCase(ASTNode switchCase) {
    ASTNode switchBlock = switchCase.getTreeParent();
    ASTNode switchStatement = switchBlock == null ? null : switchBlock.getTreeParent();
    return switchStatement != null
           && switchStatement.getElementType() == SWITCH_STATEMENT
           && isExpressionPosition(switchStatement);
  }

  /** The member a comment introduces: the next real sibling, metadata resolved to the declaration it decorates. */
  @Nullable
  private static ASTNode followingMember(ASTNode node) {
    ASTNode next = UsefulPsiTreeUtil.getNextSiblingSkipWhiteSpacesAndComments(node);
    if (next != null && next.getElementType() == EMBEDDED_META) {
      PsiElement element = HaxeMetadataUtils.getAssociatedElement(next.getPsi());
      return element == null ? null : element.getNode();
    }
    return next;
  }

  /**
   * The blank lines a field-group boundary demands between two field
   * declarations (haxe-formatter's classEmptyLines.afterStaticVars and
   * afterPrivateVars): a change of staticness or visibility splits the var
   * block. Zero within a group, for non-field nodes, and while the setting
   * is off.
   */
  private int fieldGroupGap(@NotNull ASTNode first, @NotNull ASTNode second) {
    int gap = haxe.BLANK_LINES_BETWEEN_FIELD_GROUPS;
    if (gap <= 0) return 0;
    if (!(first.getPsi() instanceof HaxeNamedComponent left)
        || !(second.getPsi() instanceof HaxeNamedComponent right)) {
      return 0;
    }
    boolean sameGroup = left.isStatic() == right.isStatic() && left.isPublic() == right.isPublic();
    return sameGroup ? 0 : gap;
  }

  /**
   * The blanks a field pair demands beyond the plain around-field count: a
   * group boundary, and the stand-off after a DOCUMENTED field
   * (afterFieldsWithDocComments).
   */
  private int fieldExtraGap(@NotNull ASTNode first, @NotNull ASTNode second) {
    int gap = fieldGroupGap(first, second);
    if (hasDocComment(first)) {
      gap = Math.max(gap, haxe.BLANK_LINES_AFTER_DOCUMENTED_FIELD);
    }
    return gap;
  }

  /** The blanks a field's doc comment demands from the field above it: the field pair's extra gap or the before-doc-comment count, whichever is larger. */
  private int fieldDocGap(@NotNull ASTNode previousField, @NotNull ASTNode documentedField) {
    return Math.max(fieldExtraGap(previousField, documentedField), haxe.BLANK_LINES_BEFORE_FIELD_DOC_COMMENT);
  }

  /** The declaration's own doc comment: the previous real sibling, metadata skipped (doc sits above the meta). */
  private static boolean hasDocComment(@NotNull ASTNode declaration) {
    ASTNode prev = UsefulPsiTreeUtil.getPrevSiblingSkipWhiteSpaces(declaration);
    while (prev != null && prev.getElementType() == EMBEDDED_META) {
      prev = UsefulPsiTreeUtil.getPrevSiblingSkipWhiteSpaces(prev);
    }
    return prev != null && prev.getElementType() == DOC_COMMENT;
  }

  /** The element type of the nearest sibling that is real code - not whitespace, comment or conditional-compilation token. */
  @Nullable
  private static IElementType realNeighborType(ASTNode node, boolean forward) {
    ASTNode neighbor = realNeighbor(node, forward);
    return neighbor == null ? null : neighbor.getElementType();
  }

  /**
   * The nearest sibling that is real code - not whitespace, comment or
   * conditional-compilation token. Not the PsiComment-based sibling skips:
   * a #error line is a PsiComment they would step over, but real code here.
   */
  @Nullable
  private static ASTNode realNeighbor(ASTNode node, boolean forward) {
    ASTNode neighbor = forward ? node.getTreeNext() : node.getTreePrev();
    while (neighbor != null) {
      IElementType type = neighbor.getElementType();
      var skipped = WHITESPACES.contains(type) || ONLY_COMMENTS.contains(type) || CONDITIONALLY_NOT_COMPILED.contains(type);
      if (!skipped) return neighbor;
      neighbor = forward ? neighbor.getTreeNext() : neighbor.getTreePrev();
    }
    return null;
  }

  /**
   * An if/try/switch used as a VALUE ({@code var x = if (c) 1 else 2;})
   * rather than as a statement - it follows the value placements, not the
   * statement-body policies.
   */
  private static boolean isExpressionPosition(ASTNode statement) {
    ASTNode parent = statement.getTreeParent();
    if (parent == null) return false;
    IElementType parentType = parent.getElementType();
    boolean statementPosition = parentType == BLOCK_STATEMENT
                                || parentType == SWITCH_CASE_BLOCK
                                || parentType == GUARDED_STATEMENT
                                || parentType == ELSE_STATEMENT
                                || parentType == DO_WHILE_BODY
                                || parentType == FOR_STATEMENT
                                || parentType == MODULE_METHOD_DECLARATION
                                // an inactive branch's statements sit under the chameleon's
                                // list wrappers and format like active statements
                                || parentType == PPBODY
                                || parentType == INACTIVE_STATEMENT_LIST
                                || FUNCTION_DEFINITION.contains(parentType);
    return !statementPosition;
  }

  /** A for/while whose enclosing construct is an array/map literal: {@code [for (x in y) v]}. */
  private static boolean isComprehension(ASTNode statement) {
    ASTNode parent = statement.getTreeParent();
    IElementType parentType = parent == null ? null : parent.getElementType();
    if (parentType == EXPRESSION_LIST || parentType == MAP_LOOP_INITIALIZER_EXPRESSION) {
      parent = parent.getTreeParent();
      parentType = parent == null ? null : parent.getElementType();
    }
    return BRACKET_LITERALS.contains(parentType);
  }

  /** Only braces and whitespace inside - the {}-collapse owns its interior. */
  /** The BLOCK_STATEMENT the pair's second node is, or wraps as a guarded/do-while body; null for any other node. */
  @Nullable
  private static ASTNode bodyBlockOf(Pair pair) {
    if (pair.type2() == BLOCK_STATEMENT) return pair.node2();
    boolean wrapsBlock = (pair.type2() == GUARDED_STATEMENT || pair.type2() == DO_WHILE_BODY) && pair.typeType2() == BLOCK_STATEMENT;
    return wrapsBlock ? pair.node2().getFirstChildNode() : null;
  }

  /** Which keep-in-one-line option keeps an empty body's {} on the header's line. */
  private boolean emptyBodyStaysInline(@Nullable IElementType headerType) {
    // FUNCTION_LITERAL first: FUNCTION_DEFINITION contains it too
    if (headerType == FUNCTION_LITERAL) return common.KEEP_SIMPLE_LAMBDAS_IN_ONE_LINE;
    if (FUNCTION_DEFINITION.contains(headerType)) return common.KEEP_SIMPLE_METHODS_IN_ONE_LINE;
    return common.KEEP_SIMPLE_BLOCKS_IN_ONE_LINE;
  }

  /**
   * A placement option decides the keyword's line after a BLOCK, overriding
   * kept line breaks: false must JOIN "} else", not merely allow it. After a
   * non-block body ("trace(x); else") the written break stays - joining onto
   * the statement reads wrong and haxe-formatter keeps it on its own line too
   * (any body placement other than KEEP forces that break).
   */
  private Spacing keywordPlacement(boolean spaceBefore, boolean onNewLine, ASTNode before, int precedingBodySetting) {
    if (!endsWithRightCurly(before)) {
      boolean breakBefore = onNewLine || resolvedBodyPlacement(precedingBodySetting) != HaxeCodeStyleSettings.BODY_PLACEMENT_KEEP;
      return spaceAndBreakIf(spaceBefore, breakBefore);
    }
    int spaces = spaceBefore ? 1 : 0;
    return Spacing.createSpacing(spaces, spaces, onNewLine ? 1 : 0, false, 0);
  }

  /** The node's last token, trailing whitespace and comments skipped at every level, is a '}'. */
  private static boolean endsWithRightCurly(ASTNode node) {
    ASTNode last = node;
    for (ASTNode child = lastCodeChild(last); child != null; child = lastCodeChild(last)) {
      last = child;
    }
    return last.getElementType() == PRCURLY;
  }

  /** The last child that is not whitespace or a comment; null for a leaf. */
  @Nullable
  private static ASTNode lastCodeChild(ASTNode node) {
    ASTNode child = node.getLastChildNode();
    while (child != null && (WHITESPACES.contains(child.getElementType()) || ONLY_COMMENTS.contains(child.getElementType()))) {
      child = child.getTreePrev();
    }
    return child;
  }

  /** One space or none; written breaks kept per the node's policy. */
  private Spacing spaceIf(boolean space) {
    return spaceAndBreakIf(space, false);
  }

  /** One space or none, and a forced line break when asked; written breaks kept per the node's policy. */
  private Spacing spaceAndBreakIf(boolean space, boolean lineBreak) {
    int spaces = space ? 1 : 0;
    int lineFeeds = lineBreak ? 1 : 0;
    return Spacing.createSpacing(spaces, spaces, lineFeeds, keepLineBreaks, common.KEEP_BLANK_LINES_IN_CODE);
  }

  /** A '{' under a brace style: on the header's line, on its own line, or (NEXT_LINE_IF_WRAPPED) on its own line only when the header wrapped. */
  private Spacing braceSpacing(boolean spaceBefore, @CommonCodeStyleSettings.BraceStyleConstant int braceStyle, Pair pair) {
    int spaces = spaceBefore ? 1 : 0;
    if (braceStyle == CommonCodeStyleSettings.NEXT_LINE_IF_WRAPPED) {
      TextRange header = pair.child1().getTextRange();
      return Spacing.createDependentLFSpacing(spaces, spaces, header, keepLineBreaks, common.KEEP_BLANK_LINES_IN_CODE);
    }
    int lineFeeds = braceStyle == CommonCodeStyleSettings.END_OF_LINE ? 0 : 1;
    return Spacing.createSpacing(spaces, spaces, lineFeeds, false, 0);
  }

  private static boolean isClassBodyType(IElementType type) {
    return CLASS_BODY_TYPES.contains(type);
  }

  private static boolean isClassDeclaration(IElementType type) {
    return CLASS_TYPES.contains(type);
  }

  /** Any top-level type declaration; CLASS_TYPES lacks the body-less typedef kind. */
  private static boolean isTypeDeclaration(IElementType type) {
    return CLASS_TYPES.contains(type) || type == TYPEDEF_DECLARATION;
  }

  /**
   * The pair separates two type declarations: the first ends one (or is the
   * #end closing a region that does), the second starts the next (or
   * introduces it: a comment, metadata, the #if opening its region).
   */
  private static boolean betweenTypeDeclarations(ASTNode node1, ASTNode node2) {
    ASTNode before = node1.getElementType() == PPEND ? realNeighbor(node1, false) : node1;
    if (before == null || !isTypeDeclaration(before.getElementType())) return false;
    ASTNode after = typeIntroducedBy(node2);
    return after != null && isTypeDeclaration(after.getElementType());
  }

  /** The declaration a node opens: itself, or the member a comment, metadata or #if leads into. */
  @Nullable
  private static ASTNode typeIntroducedBy(ASTNode node) {
    IElementType type = node.getElementType();
    if (type == PPIF) return realNeighbor(node, true);
    if (ONLY_COMMENTS.contains(type) || type == EMBEDDED_META) return followingMember(node);
    return node;
  }

  /**
   * The first IMPORT_GROUP_PACKAGE_DEPTH package segments of an import - the
   * grouping key. A bare {@code import Std;} groups by its own name, like
   * haxe-formatter's firstLevelPackage.
   */
  private String importGroupKey(ASTNode importStatement) {
    // the qualified path between the "import" keyword and ';'/"as"/"in" -
    // wildcard tails included ("a.b.*")
    String text = importStatement.getText()
      .replaceFirst("^import\\s+", "")
      .replaceFirst("\\s.*$", "")
      .replaceFirst(";$", "");
    // the dotted name's segments
    String[] segments = text.split("\\.");
    int depth = Math.max(1, haxe.IMPORT_GROUP_PACKAGE_DEPTH);
    int keep = Math.min(depth, segments.length);
    return String.join(".", Arrays.asList(segments).subList(0, keep));
  }

  private boolean isFirstChild(ASTNode child) {
    return child == node.getFirstChildNode();
  }

  private boolean isLastChild(ASTNode child) {
    return child == node.getLastChildNode();
  }

  /**
   * A multi-line object literal or anonymous type body keeps its one-per-line
   * shape even when custom line breaks are being removed - haxe-formatter
   * keeps those as written while it joins arrays, arguments and chains.
   */
  private static boolean keepsWrittenLayout(IElementType elementType) {
    return elementType == OBJECT_LITERAL
           || elementType == ANONYMOUS_TYPE_BODY
           || elementType == ANONYMOUS_TYPE_FIELD_LIST;
  }

  private static boolean isFieldDeclaration(@Nullable IElementType type) {
    // an incremental reparse can hand a field out as a LOCAL_VAR_DECLARATION_LIST
    // (its minimal form): the parser lacks the class-vs-method context then
    return type == FIELD_DECLARATION || type == LOCAL_VAR_DECLARATION_LIST;
  }

  private static boolean isMethodOrConstructorDeclaration(@Nullable IElementType type) {
    return type == METHOD_DECLARATION || type == CONSTRUCTOR_DECLARATION;
  }

  private static boolean isMemberDeclaration(@Nullable IElementType type) {
    return isFieldDeclaration(type) || isMethodOrConstructorDeclaration(type);
  }

  // Use this for debugging.  Beware: It is incredibly slow to log all of this.
  private String composeSpacingBlockData(Pair pair) {
    return """
      MyNode:%s ElementType:%s ParentType:%s
       Child1: Node1:%s Type1:%s FirstChildNode:%s FirstChildType:%s
       Child2: Node2:%s Type2:%s FirstChildNode:%s FirstChildType:%s\
      """.formatted(node, elementType, parentType,
                    pair.node1(), pair.type1(), pair.node1().getFirstChildNode(), pair.typeType1(),
                    pair.node2(), pair.type2(), pair.node2().getFirstChildNode(), pair.typeType2());
  }

  private static String nodeText(@Nullable Block child) {
    if (child == null) return "<null child>";
    String debugName = child.getDebugName();
    if (debugName != null) return debugName;
    if (child instanceof AbstractBlock block) return block.getNode().getText();
    return child.getClass().getName();
  }

  private static String composeSpacingData(Block child1, Block child2, @Nullable Spacing spacing) {
    String sp = spacing != null ? spacing.toString() : "<null spacing>";
    return "Between " + nodeText(child1) + " and " + nodeText(child2) + ", spacing is " + sp;
  }
}
