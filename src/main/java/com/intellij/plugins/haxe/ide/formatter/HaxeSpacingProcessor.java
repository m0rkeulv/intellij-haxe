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
import com.intellij.plugins.haxe.ide.formatter.settings.HaxeCodeStyleSettings;
import com.intellij.plugins.haxe.lang.psi.HaxeNamedComponent;
import com.intellij.plugins.haxe.lang.psi.HaxeTypeTag;
import com.intellij.plugins.haxe.metadata.lexer.HaxeMetadataTokenTypes;
import com.intellij.plugins.haxe.metadata.util.HaxeMetadataUtils;
import com.intellij.plugins.haxe.util.UsefulPsiTreeUtil;

import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.codeStyle.CommonCodeStyleSettings;
import com.intellij.psi.formatter.FormatterUtil;
import com.intellij.psi.formatter.common.AbstractBlock;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.util.PsiTreeUtil;
import lombok.CustomLog;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.List;

import static com.intellij.plugins.haxe.ide.formatter.HaxeFormatterTokenSets.FUNCTION_HEADER_END;
import static com.intellij.plugins.haxe.ide.formatter.HaxeFormatterTokenSets.HUGS_CLOSING_DIRECTIVE;
import static com.intellij.plugins.haxe.ide.formatter.HaxeFormatterTokenSets.OPENING_BRACKETS;
import static com.intellij.plugins.haxe.lang.lexer.HaxeTokenTypeSets.*;
import static com.intellij.plugins.haxe.lang.lexer.HaxeTokenTypes.*;

/**
 * @author: Fedor.Korotkov
 */
@CustomLog
public class HaxeSpacingProcessor {
  private final ASTNode myNode;
  private final CommonCodeStyleSettings mySettings;
  private final HaxeCodeStyleSettings myHaxeCodeStyleSettings;
  // whether the pair being spaced keeps a written line break: the
  // KEEP_LINE_BREAKS setting (off during the platform's second reformat,
  // which drops custom breaks), or a construct whose written layout stands
  private boolean keepLineBreaks;

  public HaxeSpacingProcessor(ASTNode node, CommonCodeStyleSettings settings, HaxeCodeStyleSettings haxeCodeStyleSettings) {
    myNode = node;
    mySettings = settings;
    myHaxeCodeStyleSettings = haxeCodeStyleSettings;
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

  // Use this for debugging.  Beware: It is incredibly slow to log all of this.
  private String composeSpacingBlockData(Block child1, Block child2) {
    if (!(child1 instanceof AbstractBlock) || !(child2 instanceof AbstractBlock)) {
      return "Children not abstract blocks:" + nodeText(child1) + ", " + nodeText(child2);
    }
    final IElementType elementType = myNode.getElementType();
    final IElementType parentType = myNode.getTreeParent() == null ? null : myNode.getTreeParent().getElementType();
    final ASTNode node1 = ((AbstractBlock)child1).getNode();
    final IElementType type1 = node1.getElementType();
    final ASTNode node2 = ((AbstractBlock)child2).getNode();
    IElementType type2 = node2.getElementType();
    final ASTNode nodeNode1 = node1 == null ? null : node1.getFirstChildNode();
    final IElementType typeType1 = nodeNode1 == null ? null : nodeNode1.getElementType();
    final ASTNode nodeNode2 = node2 == null ? null : node2.getFirstChildNode();
    final IElementType typeType2 = nodeNode2 == null ? null : nodeNode2.getElementType();

    StringBuilder b = new StringBuilder();
    b.append("MyNode:").append(myNode.toString());
    b.append(" ElementType:").append(elementType);
    b.append(" ParentType:").append(parentType);

    b.append("\n Child1:");
    b.append(" Node1:").append(node1);
    b.append(" Type1:").append(type1);
    b.append(" FirstChildNode:").append(nodeNode1);
    b.append(" FirstChildType:").append(typeType1);

    b.append("\n Child2:");
    b.append(" Node2:").append(node2);
    b.append(" Type2:").append(type2);
    b.append(" FirstChildNode:").append(nodeNode2);
    b.append(" FirstChildType:").append(typeType2);

    return b.toString();
  }

  private String nodeText(Block child) {
    String name = null == child ? "<null child>" : (child instanceof HaxeBlock) ? ((HaxeBlock)child).getDebugName() : null;
    if (null == name && child instanceof AbstractBlock) name = ((AbstractBlock)child).getNode().getText();
    if (null == name) name = child.getClass().getName();
    return name;
  }

  private String composeSpacingData(Block child1, Block child2, Spacing spacing) {
    String sp = null != spacing ? spacing.toString() : "<null spacing>";
    return "Between " + nodeText(child1) + " and " + nodeText(child2) + ", spacing is " + sp;
  }

  public Spacing getSpacing(Block child1, Block child2) {
    if (log.isTraceEnabled()) {
      log.trace(composeSpacingBlockData(child1, child2));
    }
    Spacing spacing = getSpacingInternal(child1, child2);
    if (log.isDebugEnabled()) {
      log.debug(composeSpacingData(child1, child2, spacing));
    }
    return spacing;
  }

  public Spacing getSpacingInternal(Block child1, Block child2) {
    if (!(child1 instanceof AbstractBlock) || !(child2 instanceof AbstractBlock)) {
      return null;
    }

    // inside a doc comment only line-leading indentation is managed: line breaks
    // and blank lines are markdown content (paragraphs) and are all kept.
    // The engine computes keepBlankLines + 1, so MAX_VALUE would overflow.
    if (myNode.getElementType() == DOC_COMMENT) {
      return Spacing.createSpacing(0, 9999, 0, true, 9999);
    }

    final IElementType elementType = myNode.getElementType();
    keepLineBreaks = mySettings.KEEP_LINE_BREAKS || keepsWrittenLayout(elementType);
    final IElementType parentType = myNode.getTreeParent() == null ? null : myNode.getTreeParent().getElementType();
    final IElementType typeNext = getNextElementType();
    final ASTNode node1 = ((AbstractBlock)child1).getNode();
    final IElementType type1 = node1.getElementType();
    final ASTNode node2 = ((AbstractBlock)child2).getNode();
    IElementType type2 = node2.getElementType();
    final ASTNode nodeNode1 = node1 == null ? null : node1.getFirstChildNode();
    final IElementType typeType1 = nodeNode1 == null ? null : nodeNode1.getElementType();
    final ASTNode nodeNode2 = node2 == null ? null : node2.getFirstChildNode();
    final IElementType typeType2 = nodeNode2 == null ? null : nodeNode2.getElementType();

    // TODO: Add Metadata spacing rules AND associated UI.
    //  (When looking for examples, Java code uses the word "Annotations".)

    // TODO: Do this for comments, too??
    // If type2 is metadata, then camouflage it as the type that follows it.
    // memberNode2 carries the REAL member behind the camouflage for rules
    // that inspect the declaration (field grouping).
    ASTNode memberNode2 = node2;
    if(type2 == EMBEDDED_META) {
      PsiElement element = HaxeMetadataUtils.getAssociatedElement(node2.getPsi());
      if (null != element) {
        memberNode2 = element.getNode();
        type2 = memberNode2.getElementType();
      }
    }

    //if (
    //  type1 == IMPORT_STATEMENT ||
    //    //type1 == PACKAGE_STATEMENT ||
    //    type1 == USING_STATEMENT) {
    //  return addSingleSpaceIf(false, true);
    //}

    // a block comment OPENING the file is a license header - it keeps a
    // minimum gap to whatever follows (doc comments attach to their member
    // and are not headers)
    boolean fileHeaderComment = type1 == MML_COMMENT
                                && node1.getTreePrev() == null
                                && myNode.getTreeParent() == null;
    if (fileHeaderComment && myHaxeCodeStyleSettings.MINIMUM_BLANK_LINES_AFTER_FILE_HEADER > 0) {
      int minimumFeeds = 1 + myHaxeCodeStyleSettings.MINIMUM_BLANK_LINES_AFTER_FILE_HEADER;
      return Spacing.createSpacing(0, 0, minimumFeeds, true, mySettings.KEEP_BLANK_LINES_IN_CODE);
    }

    // BLANK_LINES_* count blank lines; Spacing counts LINE FEEDS (one more)
    if (type1.equals(PACKAGE_STATEMENT)) {
      return Spacing.createSpacing(0, 0, 1 + mySettings.BLANK_LINES_AFTER_PACKAGE, true, mySettings.KEEP_BLANK_LINES_IN_CODE);
    }

    // grouping on: imports from different package groups get an exact gap,
    // same-group imports stay snug. Grouping off: the keep cap applies.
    // Either way the section-end rules below own the blank after the section.
    if (type1 == IMPORT_STATEMENT && type2 == IMPORT_STATEMENT
        && myHaxeCodeStyleSettings.BLANK_LINES_BETWEEN_IMPORT_GROUPS > 0) {
      boolean sameGroup = importGroupKey(node1).equals(importGroupKey(node2));
      int blanks = sameGroup ? 0 : myHaxeCodeStyleSettings.BLANK_LINES_BETWEEN_IMPORT_GROUPS;
      return Spacing.createSpacing(0, 0, 1 + blanks, false, blanks);
    }
    if ((type1 == IMPORT_STATEMENT && type2 == IMPORT_STATEMENT)
        || (type1 == USING_STATEMENT && type2 == USING_STATEMENT)) {
      return Spacing.createSpacing(0, 0, 1, true, myHaxeCodeStyleSettings.KEEP_BLANK_LINES_BETWEEN_IMPORTS);
    }

    // conditional-compilation directives wrapping imports belong to the
    // import section: the betweenImports cap spans them, and the section-end
    // gap moves behind the closing #end (haxe-formatter's markImports)
    boolean cc1 = CONDITIONALLY_NOT_COMPILED.contains(type1);
    boolean cc2 = CONDITIONALLY_NOT_COMPILED.contains(type2);
    if (cc1 || cc2) {
      Spacing importSection = importSectionDirectiveSpacing(node1, node2, type1, type2, cc1, cc2);
      if (importSection != null) return importSection;
    }

    // a #if..#end region written on ONE line stays there, spaced like
    // haxe-formatter's sharp rules; a { body still follows the brace style
    Spacing inlineDirective = inlineDirectiveSpacing(node1, node2, type2);
    if (inlineDirective != null) return inlineDirective;

    // a comment inside the import section belongs to the import BELOW it -
    // the section-end blank must not push it away from its import
    if (type1 == IMPORT_STATEMENT && type2 != IMPORT_STATEMENT && !ONLY_COMMENTS.contains(type2)) {
      return Spacing.createSpacing(0, 0, 1 + mySettings.BLANK_LINES_AFTER_IMPORTS, true, mySettings.KEEP_BLANK_LINES_IN_CODE);
    }
    if (type1 == USING_STATEMENT && type2 != USING_STATEMENT && !ONLY_COMMENTS.contains(type2)) {
      return Spacing.createSpacing(0, 0, 1 + myHaxeCodeStyleSettings.MINIMUM_BLANK_LINES_AFTER_USING, true, mySettings.KEEP_BLANK_LINES_IN_CODE);
    }

    if (elementType.equals(IMPORT_WILDCARD)) {
      return addSingleSpaceIf(false);
    }

    if (isClassDeclaration(elementType) && isClassBodyType(type2)) {
      return setBraceSpace(mySettings.SPACE_BEFORE_CLASS_LBRACE, mySettings.BRACE_STYLE, child1.getTextRange());
    }

    // adjacent ONE-LINE type declarations keep their own blank-line cap
    // (0 = snug); a multi-line neighbour follows the around-class rules
    boolean singleLineTypePair = isTypeDeclaration(type1) && isTypeDeclaration(type2)
                                 && !node1.textContains('\n') && !node2.textContains('\n');
    if (singleLineTypePair) {
      return Spacing.createSpacing(0, 0, 1, true, myHaxeCodeStyleSettings.KEEP_BLANK_LINES_BETWEEN_SINGLE_LINE_TYPES);
    }

    if (isClassDeclaration(type1)) {
      return Spacing.createSpacing(0, 0, mySettings.BLANK_LINES_AROUND_CLASS, true, mySettings.KEEP_BLANK_LINES_IN_CODE);
    }

    if (isClassBodyType(type1)) {  // End of the class body. (After the right brace.)
      return Spacing.createSpacing(0, 0, 1, false, mySettings.KEEP_BLANK_LINES_IN_CODE);
    }
    // avoid  multi-line formatting types (anonymous structures have brackets)
    boolean isType = parentType == ANONYMOUS_TYPE || PsiTreeUtil.getParentOfType(myNode.getPsi(), HaxeTypeTag.class) != null;

    // a structure extension hugs a one-line body ({ > Base, ... }) and takes
    // its own line in a multi-line one; must precede the class-body { rule,
    // whose isType arm keeps the pair as written (the OFF behavior)
    if (myHaxeCodeStyleSettings.STRUCTURE_EXTENSION_ON_OWN_LINE
        && elementType == ANONYMOUS_TYPE_BODY && type1 == PLCURLY && type2 == TYPE_EXTENDS_LIST) {
      return Spacing.createDependentLFSpacing(1, 1, myNode.getTextRange(),
                                              keepLineBreaks, mySettings.KEEP_BLANK_LINES_IN_CODE);
    }

    // type2 == PRCURLY is the EMPTY body - the before-} rule below keeps its
    // (caret) line, which smart enter and live templates rely on
    if (type1 == PLCURLY && type2 != PRCURLY && isClassBodyType(elementType) && isFirstChild(child1)) {
      // the setting is exact here (kept blanks would defeat "0 after the header")
      int lineFeeds = isType ? 0 : 1 + mySettings.BLANK_LINES_AFTER_CLASS_HEADER;
      return Spacing.createSpacing(0, 0, lineFeeds, keepLineBreaks, mySettings.BLANK_LINES_AFTER_CLASS_HEADER);
    }

    // type1 == PLCURLY is the EMPTY body - kept as written ({} stays inline,
    // a caret line stays for smart enter)
    if (type2 == PRCURLY && type1 != PLCURLY && isClassBodyType(elementType) && isLastChild(child2)) {
      int lineFeeds = isType ? 0 : 1 + mySettings.BLANK_LINES_BEFORE_CLASS_END;
      return Spacing.createSpacing(0, 0, lineFeeds, keepLineBreaks, mySettings.KEEP_BLANK_LINES_BEFORE_RBRACE);
    }

    // a blank line hugging a plain block's brace has its own keep cap
    // (emptyLines.afterLeftCurly/beforeRightCurly; class bodies above use
    // exact counts); the pair otherwise behaves like the fallback rule
    if (type1 == PLCURLY && type2 != PRCURLY && !isClassBodyType(elementType) && isFirstChild(child1)) {
      return keepCappedBlanks(myHaxeCodeStyleSettings.KEEP_BLANK_LINES_AFTER_LBRACE);
    }
    if (type2 == PRCURLY && type1 != PLCURLY && !isClassBodyType(elementType) && isLastChild(child2)) {
      return keepCappedBlanks(mySettings.KEEP_BLANK_LINES_BEFORE_RBRACE);
    }

    // a case's body: sameLine.caseBody=Next breaks an inline body onto its
    // own line - except in an EXPRESSION switch (expressionCase=keep). The
    // written shape otherwise keeps the case-colon blank cap
    // (emptyLines.beforeBlocks); blanks BETWEEN cases keep the in-code cap.
    if ((elementType == SWITCH_CASE || elementType == DEFAULT_CASE) && type2 == SWITCH_CASE_BLOCK) {
      int placement = myHaxeCodeStyleSettings.CASE_BODY_PLACEMENT;
      if (placement == HaxeCodeStyleSettings.BODY_PLACEMENT_NEXT_LINE && !isExpressionSwitchCase(myNode)) {
        return Spacing.createSpacing(0, 0, 1, false, myHaxeCodeStyleSettings.KEEP_BLANK_LINES_AFTER_CASE_COLON);
      }
      if (placement == HaxeCodeStyleSettings.BODY_PLACEMENT_SAME_LINE && !isExpressionSwitchCase(myNode)) {
        return Spacing.createSpacing(1, 1, 0, false, 0);
      }
      return keepCappedBlanks(myHaxeCodeStyleSettings.KEEP_BLANK_LINES_AFTER_CASE_COLON);
    }

    // a blank line before a member belongs BEFORE its doc comment - resolve
    // the pair as if the comment were the member's first line
    boolean memberThenDoc = type2 == DOC_COMMENT
                            && (isFieldDeclaration(type1) || isMethodDeclarationOrConstructorDeclaration(type1));
    if (memberThenDoc) {
      ASTNode documented = followingMember(node2);
      IElementType documentedType = documented == null ? null : documented.getElementType();
      if (isMethodDeclarationOrConstructorDeclaration(documentedType)) {
        return Spacing.createSpacing(0, 0, 1 + mySettings.BLANK_LINES_AROUND_METHOD, keepLineBreaks, mySettings.KEEP_BLANK_LINES_IN_DECLARATIONS);
      }
      if (isFieldDeclaration(documentedType)) {
        // a FIELD's doc comment also stands off from the previous field
        // (beforeDocCommentEmptyLines)
        int neighborGap = isMethodDeclarationOrConstructorDeclaration(type1)
                          ? mySettings.BLANK_LINES_AROUND_METHOD
                          : Math.max(fieldExtraGap(node1, documented), myHaxeCodeStyleSettings.BLANK_LINES_BEFORE_FIELD_DOC_COMMENT);
        int blanks = Math.max(mySettings.BLANK_LINES_AROUND_FIELD, neighborGap);
        return Spacing.createSpacing(0, 0, 1 + blanks, keepLineBreaks, mySettings.KEEP_BLANK_LINES_IN_DECLARATIONS);
      }
    }

    // a plain comment travels with the field BELOW it too - a field-group
    // or documented-field blank must land before the comment, not between
    // comment and field. Without one the pair keeps its written shape.
    if (ONLY_COMMENTS.contains(type2) && isFieldDeclaration(type1)) {
      ASTNode commented = followingMember(node2);
      int blanks = commented != null && isFieldDeclaration(commented.getElementType())
                   ? fieldExtraGap(node1, commented)
                   : 0;
      if (blanks > 0) {
        return Spacing.createSpacing(0, 0, 1 + blanks, keepLineBreaks, mySettings.KEEP_BLANK_LINES_IN_DECLARATIONS);
      }
    }

    if (isMethodDeclarationOrConstructorDeclaration(type1) && isMethodDeclarationOrConstructorDeclaration(type2)) {
      return Spacing.createSpacing(0, 0, 1 + mySettings.BLANK_LINES_AROUND_METHOD, keepLineBreaks, mySettings.KEEP_BLANK_LINES_IN_DECLARATIONS);
    }

    if (isMethodDeclarationOrConstructorDeclaration(type1) && isFieldDeclaration(type2)) {
      return Spacing.createSpacing(0, 0, 1 + mySettings.BLANK_LINES_AROUND_METHOD, keepLineBreaks, mySettings.KEEP_BLANK_LINES_IN_DECLARATIONS);
    }

    if (isFieldDeclaration(type1) && isFieldDeclaration(type2)) {
      int blanks = Math.max(mySettings.BLANK_LINES_AROUND_FIELD, fieldExtraGap(node1, memberNode2));
      return Spacing.createSpacing(0, 0, 1 + blanks, keepLineBreaks, mySettings.KEEP_BLANK_LINES_IN_DECLARATIONS);
    }

    if (isFieldDeclaration(type1) && isMethodDeclarationOrConstructorDeclaration(type2)) {
      return Spacing.createSpacing(0, 0, 1 + mySettings.BLANK_LINES_AROUND_METHOD, keepLineBreaks, mySettings.KEEP_BLANK_LINES_IN_DECLARATIONS);
    }

    if (DOC_COMMENT == type1) {
      return Spacing.createSpacing(0,0,1,false, mySettings.KEEP_BLANK_LINES_IN_CODE);
    }

    if (ONLY_COMMENTS.contains(type1) && (isMethodDeclarationOrConstructorDeclaration(type2) || isFieldDeclaration(type2))) { // prevent excess linefeed between doctype and function
      return Spacing.createSpacing(0, 0, 1, true, mySettings.KEEP_BLANK_LINES_IN_CODE);
    }

    // an EMPTY body's braces collapse to {} when the matching keep-in-one-line
    // option allows it (class bodies excluded - smart enter owns their caret line)
    if (type1 == PLCURLY && type2 == PRCURLY && !isClassBodyType(elementType)
        && emptyBodyStaysInline(parentType)) {
      return Spacing.createSpacing(0, 0, 0, false, 0);
    }

    // haxe-formatter's sameLine.*Body policies for a NON-BLOCK body:
    // Next forces it onto its own line, Same joins it onto the header's
    // line, Keep leaves it as written; block bodies follow the brace rules
    // instead. A for/while inside a literal is a COMPREHENSION, not a
    // control statement - its body always stays on the line.
    if (!isComprehension(myNode)) {
      int placement = nonBlockBodyPlacement(elementType, type1, type2, typeType2);
      if (placement == HaxeCodeStyleSettings.BODY_PLACEMENT_NEXT_LINE) {
        return Spacing.createSpacing(0, 0, 1, false, 0);
      }
      if (placement == HaxeCodeStyleSettings.BODY_PLACEMENT_SAME_LINE) {
        return Spacing.createSpacing(1, 1, 0, false, 0);
      }
    }

    // a NAMED function's non-block body (function f() return x;) moves to
    // its own line; anonymous/arrow function bodies always stay inline
    boolean namedFunction = elementType == METHOD_DECLARATION || elementType == CONSTRUCTOR_DECLARATION
                            || elementType == LOCAL_FUNCTION_DECLARATION || elementType == MODULE_METHOD_DECLARATION;
    boolean headerEnd = FUNCTION_HEADER_END.contains(type1);
    // the header's own trailing parts also follow a headerEnd - only what
    // comes after the LAST of them is the body
    boolean headerTrailer = FUNCTION_HEADER_END.contains(type2) || type2 == OSEMI || type2 == BLOCK_STATEMENT;
    if (myHaxeCodeStyleSettings.FUNCTION_EXPRESSION_BODY_ON_NEXT_LINE
        && namedFunction && headerEnd && !headerTrailer) {
      return Spacing.createSpacing(0, 0, 1, false, 0);
    }

    // bracketConfig NoSpace: an access target keeps its '[' snug
    if (elementType == ARRAY_ACCESS_EXPRESSION && type2 == PLBRACK) {
      return addSingleSpaceIf(false);
    }
    boolean bracketInner = (elementType == ARRAY_ACCESS_EXPRESSION || elementType == ARRAY_LITERAL || elementType == MAP_LITERAL)
                           && (type1 == PLBRACK || type2 == PRBRACK);
    if (bracketInner) {
      return addSingleSpaceIf(mySettings.SPACE_WITHIN_BRACKETS);
    }

    // inside a string's ${ } interpolation braces; the embedded expression
    // itself formats under the normal rules
    if (elementType == LONG_TEMPLATE_ENTRY
        && (type1 == LONG_TEMPLATE_ENTRY_START || type2 == LONG_TEMPLATE_ENTRY_END)) {
      return addSingleSpaceIf(myHaxeCodeStyleSettings.SPACE_WITHIN_STRING_INTERPOLATION);
    }

    // type parameter/argument angle brackets: never a space between the name
    // and its '<'; inside the brackets per the Haxe spacing option
    if (type2 == TYPE_PARAM || type2 == GENERIC_PARAM) {
      return addSingleSpaceIf(false);
    }
    boolean typeParams = elementType == TYPE_PARAM || elementType == GENERIC_PARAM;
    if (typeParams && (type1 == OLESS || type2 == OGREATER)) {
      return addSingleSpaceIf(myHaxeCodeStyleSettings.SPACE_WITHIN_TYPE_PARAMETERS);
    }

    if (type2 == PLPAREN) {
      if (elementType == GUARD) { // IF_STATEMENT) {
        return addSingleSpaceIf(mySettings.SPACE_BEFORE_IF_PARENTHESES);
      }
      else if (elementType == WHILE_STATEMENT || elementType == DO_WHILE_STATEMENT) {
        return addSingleSpaceIf(mySettings.SPACE_BEFORE_WHILE_PARENTHESES);
      }
      else if (elementType == FOR_STATEMENT) {
        return addSingleSpaceIf(mySettings.SPACE_BEFORE_FOR_PARENTHESES);
      }
      else if (elementType == TRY_STATEMENT) {
        return addSingleSpaceIf(mySettings.SPACE_BEFORE_TRY_PARENTHESES);
      }
      else if (elementType == CATCH_STATEMENT) {
        return addSingleSpaceIf(mySettings.SPACE_BEFORE_CATCH_PARENTHESES);
      }
      else if (FUNCTION_DEFINITION.contains(elementType)) {
        return addSingleSpaceIf(mySettings.SPACE_BEFORE_METHOD_PARENTHESES);
      }
      else if (elementType == CALL_EXPRESSION) {
        return addSingleSpaceIf(mySettings.SPACE_BEFORE_METHOD_CALL_PARENTHESES);
      }
    }
    if (elementType == SWITCH_STATEMENT) {
      if (type2 == PARENTHESIZED_EXPRESSION) {
        return addSingleSpaceIf(mySettings.SPACE_BEFORE_SWITCH_PARENTHESES);
      }
      else if (type2 == SWITCH_BLOCK) {
        return setBraceSpace(mySettings.SPACE_BEFORE_SWITCH_LBRACE, mySettings.BRACE_STYLE, child1.getTextRange());
      }
    }
    if (type1 == OGREATER && type2 == OASSIGN) {
      return addSingleSpaceIf(false);
    }

    // lineEnds.emptyCurly=NoBreak: an EMPTY body's {} stays on the header's
    // line even under next-line brace styles - the collapse rule above folds
    // the braces themselves, this pair keeps them from moving down
    ASTNode emptyBodyCandidate = type2 == BLOCK_STATEMENT ? node2
      : (type2 == GUARDED_STATEMENT || type2 == DO_WHILE_BODY) && typeType2 == BLOCK_STATEMENT ? nodeNode2
      : null;
    if (isEmptyBlock(emptyBodyCandidate) && emptyBodyStaysInline(elementType)) {
      return Spacing.createSpacing(1, 1, 0, false, 0);
    }

    //
    //Spacing before left braces
    //
    // NOTE: BLOCK_STATEMENTs that are a single sub-element of an enclosing block,
    //       such as GUARDED_STATEMENT or DO_WHILE_BODY are presented as the enclosing
    //       statement type and are NOT presented as a separate BLOCK_STATEMENT sub-element.
    //
    if (elementType == IF_STATEMENT) {
      if (type2 == GUARDED_STATEMENT && typeType2 == BLOCK_STATEMENT) {
        return setBraceSpace(mySettings.SPACE_BEFORE_IF_LBRACE, mySettings.BRACE_STYLE, child1.getTextRange());
      }
    }
    if (type2 == DO_WHILE_BODY && typeType2 == BLOCK_STATEMENT) {
      if (elementType == WHILE_STATEMENT) {
        return setBraceSpace(mySettings.SPACE_BEFORE_WHILE_LBRACE, mySettings.BRACE_STYLE, child1.getTextRange());
      }
      else if(elementType == DO_WHILE_STATEMENT) {
        return setBraceSpace(mySettings.SPACE_BEFORE_DO_LBRACE, mySettings.BRACE_STYLE, child1.getTextRange());
      }
    }
    if (type2 == BLOCK_STATEMENT) {
      if (elementType == ELSE_STATEMENT) { // else if (elementType == IF_STATEMENT && type1 == KELSE) {
        return setBraceSpace(mySettings.SPACE_BEFORE_ELSE_LBRACE, mySettings.BRACE_STYLE, child1.getTextRange());
      }
      else if (elementType == FOR_STATEMENT) {
        return setBraceSpace(mySettings.SPACE_BEFORE_FOR_LBRACE, mySettings.BRACE_STYLE, child1.getTextRange());
      }
      else if (elementType == TRY_STATEMENT) {
        return setBraceSpace(mySettings.SPACE_BEFORE_TRY_LBRACE, mySettings.BRACE_STYLE, child1.getTextRange());
      }
      else if (elementType == CATCH_STATEMENT) {
        return setBraceSpace(mySettings.SPACE_BEFORE_CATCH_LBRACE, mySettings.BRACE_STYLE, child1.getTextRange());
      }
      else if (FUNCTION_DEFINITION.contains(elementType)) {
        return setBraceSpace(mySettings.SPACE_BEFORE_METHOD_LBRACE, mySettings.METHOD_BRACE_STYLE, child1.getTextRange());
      }
    }

    if (type1 == PLPAREN || type2 == PRPAREN) {
      if (elementType == GUARD) { // if (elementType == IF_STATEMENT) {
        return addSingleSpaceIf(mySettings.SPACE_WITHIN_IF_PARENTHESES);
      }
      else if (elementType == WHILE_STATEMENT || elementType == DO_WHILE_STATEMENT) {
        return addSingleSpaceIf(mySettings.SPACE_WITHIN_WHILE_PARENTHESES);
      }
      else if (elementType == FOR_STATEMENT) {
        return addSingleSpaceIf(mySettings.SPACE_WITHIN_FOR_PARENTHESES);
      }
      else if (parentType == SWITCH_STATEMENT && elementType == PARENTHESIZED_EXPRESSION) {
        return addSingleSpaceIf(mySettings.SPACE_WITHIN_SWITCH_PARENTHESES);
      }
      else if (elementType == TRY_STATEMENT) {
        return addSingleSpaceIf(mySettings.SPACE_WITHIN_TRY_PARENTHESES);
      }
      else if (elementType == CATCH_STATEMENT) {
        return addSingleSpaceIf(mySettings.SPACE_WITHIN_CATCH_PARENTHESES);
      }
      else if (FUNCTION_DEFINITION.contains(elementType)) {
        final boolean newLineNeeded = type1 == PLPAREN ?
                                      mySettings.METHOD_PARAMETERS_LPAREN_ON_NEXT_LINE :
                                      mySettings.METHOD_PARAMETERS_RPAREN_ON_NEXT_LINE;
        return addSingleSpaceIf(mySettings.SPACE_WITHIN_METHOD_PARENTHESES, newLineNeeded);
      }
      else if (elementType == CALL_EXPRESSION) {
        final boolean newLineNeeded = type1 == PLPAREN ?
                                      mySettings.CALL_PARAMETERS_LPAREN_ON_NEXT_LINE :
                                      mySettings.CALL_PARAMETERS_RPAREN_ON_NEXT_LINE;
        return addSingleSpaceIf(mySettings.SPACE_WITHIN_METHOD_CALL_PARENTHESES, newLineNeeded);
      }
      else if (mySettings.BINARY_OPERATION_WRAP != CommonCodeStyleSettings.DO_NOT_WRAP && elementType == PARENTHESIZED_EXPRESSION) {
        final boolean newLineNeeded = type1 == PLPAREN ?
                                      mySettings.PARENTHESES_EXPRESSION_LPAREN_WRAP :
                                      mySettings.PARENTHESES_EXPRESSION_RPAREN_WRAP;
        return addSingleSpaceIf(false, newLineNeeded);
      }
    }

    // object literal field colon ({a: 1}) - hxformat's objectFieldColonPolicy
    if (elementType == OBJECT_LITERAL_ELEMENT) {
      if (type2 == OCOLON) {
        return addSingleSpaceIf(myHaxeCodeStyleSettings.SPACE_BEFORE_OBJECT_FIELD_COLON);
      }
      if (type1 == OCOLON) {
        return addSingleSpaceIf(myHaxeCodeStyleSettings.SPACE_AFTER_OBJECT_FIELD_COLON);
      }
    }

    if (elementType == TERNARY_EXPRESSION) {
      if (typeType2 == OQUEST) {
        return addSingleSpaceIf(mySettings.SPACE_BEFORE_QUEST);
      }
      else if (typeType2 == OCOLON) {
        return addSingleSpaceIf(mySettings.SPACE_BEFORE_COLON);
      }
      else if (typeType1 == OQUEST) {
        return addSingleSpaceIf(mySettings.SPACE_AFTER_QUEST);
      }
      else if (typeType1 == OCOLON) {
        return addSingleSpaceIf(mySettings.SPACE_AFTER_COLON);
      }
    }

    // a block used as a VALUE (x = { ... }) opens under the brace style like
    // any other block - haxe-formatter's leftCurly covers every { but an
    // object literal's
    boolean valueBlock = type2 == VALUE_INIT_BLOCK && (elementType == VAR_INIT || elementType == ASSIGN_EXPRESSION);
    if (valueBlock) {
      return setBraceSpace(mySettings.SPACE_AROUND_ASSIGNMENT_OPERATORS, mySettings.BRACE_STYLE, child1.getTextRange());
    }

    //
    // Spacing around assignment operators (=, -=, etc.)
    //
    if (ASSIGN_OPERATORS.contains(type1)
        || ASSIGN_OPERATORS.contains(typeType1)
        || ASSIGN_OPERATORS.contains(typeType2)
        || type2 == VAR_INIT) {
      if (typeType2 != null && !isInXmlTag(typeType2, elementType, parentType)) {
        return addSingleSpaceIf(mySettings.SPACE_AROUND_ASSIGNMENT_OPERATORS);
      }
    }

    if (type2 == OSEMI) {
      return Spacing.createSpacing(0, 0, 0, true, 1);
    }

    //
    // Spacing around  logical operators (&&, OR, etc.)
    //
    // wrapping.opBoolChain: a qualifying chain goes one operand per line
    // with LEADING operators; other chains keep their written shape
    boolean logicChain = elementType == LOGIC_AND_EXPRESSION || elementType == LOGIC_OR_EXPRESSION;
    if (logicChain && LOGIC_OPERATORS.contains(typeType2) && boolChainSplitsOnePerLine(myNode)) {
      return Spacing.createSpacing(0, 0, 1, false, 0);
    }
    if (logicChain && LOGIC_OPERATORS.contains(typeType1) && boolChainSplitsOnePerLine(myNode)) {
      return Spacing.createSpacing(1, 1, 0, false, 0);
    }
    if (LOGIC_OPERATORS.contains(typeType1) || LOGIC_OPERATORS.contains(typeType2)) {
      return addSingleSpaceIf(mySettings.SPACE_AROUND_LOGICAL_OPERATORS);
    }
    //
    // Spacing around  equality operators (==, != etc.)
    //
    if ((type1 == COMPARE_OPERATION && EQUALITY_OPERATORS.contains(typeType1)) ||
        (type2 == COMPARE_OPERATION && EQUALITY_OPERATORS.contains(typeType2))) {
      return addSingleSpaceIf(mySettings.SPACE_AROUND_EQUALITY_OPERATORS);
    }
    //
    // Spacing around  relational operators (<, <= etc.)
    //
    if ((type1 == COMPARE_OPERATION && RELATIONAL_OPERATORS.contains(typeType1)) ||
        (type2 == COMPARE_OPERATION && RELATIONAL_OPERATORS.contains(typeType2))) {
      return addSingleSpaceIf(mySettings.SPACE_AROUND_RELATIONAL_OPERATORS);
    }
    //
    // Spacing around  additive operators ( &, |, ^, etc.)
    //
    if (BITWISE_OPERATORS.contains(typeType1) || BITWISE_OPERATORS.contains(typeType2)) {
      return addSingleSpaceIf(mySettings.SPACE_AROUND_BITWISE_OPERATORS);
    }
    //
    // Spacing around  additive operators ( +, -, etc.)
    //
    // wrapping.opAddSubChain: a chain that explodes, or fills past the
    // margin, breaks before the operator with the operator LEADING
    // (HaxeAdditiveChainRules)
    boolean additiveChain = elementType == ADDITIVE_EXPRESSION;
    if (additiveChain && ADDITIVE_OPERATORS.contains(typeType2) && additiveChainBreaksBefore(node2)) {
      return Spacing.createSpacing(0, 0, 1, false, 0);
    }
    if (additiveChain && ADDITIVE_OPERATORS.contains(typeType1) && additiveChainBreaksBefore(node1)) {
      return Spacing.createSpacing(1, 1, 0, false, 0);
    }
    //ADDITIVE_OPERATOR == type2
    if ((ADDITIVE_OPERATORS.contains(typeType1) || ADDITIVE_OPERATORS.contains(typeType2)) &&
        elementType != PREFIX_EXPRESSION) {
      return addSingleSpaceIf(mySettings.SPACE_AROUND_ADDITIVE_OPERATORS);
    }
    //
    // Spacing around  multiplicative operators ( *, /, %, etc.)
    //
    if ((MULTIPLICATIVE_OPERATORS.contains(typeType1) || MULTIPLICATIVE_OPERATORS.contains(typeType2))) {
      return addSingleSpaceIf(mySettings.SPACE_AROUND_MULTIPLICATIVE_OPERATORS);
    }
    //
    // Spacing around  unary operators ( NOT, ++, etc.)
    //
    if ((UNARY_OPERATORS.contains(typeType1) || UNARY_OPERATORS.contains(typeType2)) &&
        elementType == PREFIX_EXPRESSION) {
      return addSingleSpaceIf(mySettings.SPACE_AROUND_UNARY_OPERATOR);
    }
    //
    // Spacing around  shift operators ( <<, >>, >>>, etc.)
    //
    // >> and >>> arrive as composite operator elements over split '>' tokens
    // (generics-friendly lexing), so the ELEMENT types match too, not only
    // the wrapped token of a one-token operator
    if (SHIFT_OPERATORS.contains(type1) || SHIFT_OPERATORS.contains(type2)
        || SHIFT_OPERATORS.contains(typeType1) || SHIFT_OPERATORS.contains(typeType2)) {
      return addSingleSpaceIf(mySettings.SPACE_AROUND_SHIFT_OPERATORS);
    }
    // the split '>' tokens INSIDE such an operator must stay glued
    if (SHIFT_OPERATORS.contains(elementType)) {
      return Spacing.createSpacing(0, 0, 0, false, 0);
    }

    //
    //Spacing before keyword (else, catch, etc)
    //
    // a value-position if/try keeps its keywords as written
    // (expressionIf/expressionTry=Same)
    if ((type2 == ELSE_STATEMENT || type2 == CATCH_STATEMENT) && isExpressionPosition(myNode)) {
      boolean spaceBefore = type2 == ELSE_STATEMENT ? mySettings.SPACE_BEFORE_ELSE_KEYWORD
                                                    : mySettings.SPACE_BEFORE_CATCH_KEYWORD;
      return addSingleSpaceIf(spaceBefore);
    }
    if (type2 == ELSE_STATEMENT) {
      return keywordPlacement(mySettings.SPACE_BEFORE_ELSE_KEYWORD, mySettings.ELSE_ON_NEW_LINE, node1,
                              resolvedBodyPlacement(myHaxeCodeStyleSettings.IF_BODY_PLACEMENT));
    }
    if (type2 == KWHILE) {
      return keywordPlacement(mySettings.SPACE_BEFORE_WHILE_KEYWORD, mySettings.WHILE_ON_NEW_LINE, node1,
                              resolvedBodyPlacement(myHaxeCodeStyleSettings.DO_WHILE_BODY_PLACEMENT));
    }
    if (type2 == CATCH_STATEMENT) {
      int precedingBody = resolvedBodyPlacement(type1 == CATCH_STATEMENT
                                                ? myHaxeCodeStyleSettings.CATCH_BODY_PLACEMENT
                                                : myHaxeCodeStyleSettings.TRY_BODY_PLACEMENT);
      return keywordPlacement(mySettings.SPACE_BEFORE_CATCH_KEYWORD, mySettings.CATCH_ON_NEW_LINE, node1, precedingBody);
    }

    //
    //Other
    //

    if (type1 == KELSE && type2 == IF_STATEMENT) {  // Inside of ELSE_STATEMENT
      return Spacing.createSpacing(1, 1, mySettings.SPECIAL_ELSE_IF_TREATMENT ? 0 : 1, false, mySettings.KEEP_BLANK_LINES_IN_CODE);
    }

    // wrapping.multiVar: a multi-var whose JOINED line would pass the split
    // width breaks after every comma; under the width the written shape is
    // kept (the tool's length-based joins are not reproduced)
    if (elementType == LOCAL_VAR_DECLARATION_LIST && type1 == OCOMMA && type2 == LOCAL_VAR_DECLARATION
        && multiVarLineExceedsSplitWidth()) {
      return Spacing.createSpacing(0, 0, 1, false, 0);
    }

    // wrapping.callParameter fillLine judged on the JOINED line: an argument
    // the tool moves down starts its line here too, whatever fits after the
    // other breaks (HaxeCallFill)
    if (myHaxeCodeStyleSettings.FILL_CALL_ARGUMENTS_ON_JOINED_LINE && callFillBreaksBefore(node2, type1)) {
      return Spacing.createSpacing(0, 0, 1, false, 0);
    }
    if (type1 == OCOMMA && (elementType == PARAMETER_LIST || elementType == EXPRESSION_LIST || elementType == CALL_EXPRESSION_LIST) &&
        (parentType == CALL_EXPRESSION ||
         parentType == NEW_EXPRESSION ||
         FUNCTION_DEFINITION.contains(parentType))) {
      return addSingleSpaceIf(mySettings.SPACE_AFTER_COMMA_IN_TYPE_ARGUMENTS);
    }

    if (type1 == OCOMMA) {
      return addSingleSpaceIf(mySettings.SPACE_AFTER_COMMA);
    }

    if (type2 == OCOMMA) {
      return addSingleSpaceIf(mySettings.SPACE_BEFORE_COMMA);
    }

    // the (expr : Type) type-check colon, spaced UNLIKE type-hint colons
    if (elementType == TYPE_CHECK_EXPR && (type1 == OCOLON || type2 == OCOLON)) {
      return addSingleSpaceIf(myHaxeCodeStyleSettings.SPACE_AROUND_TYPE_CHECK_COLON);
    }

    // metadata parens - metadata has its own token set, hence the qualified
    // names. The @:name-to-( gap is always snug; inside per the option
    boolean insideMeta = elementType == HaxeMetadataTokenTypes.COMPILE_TIME_META
                         || elementType == HaxeMetadataTokenTypes.RUN_TIME_META;
    if (insideMeta) {
      if (type2 == HaxeMetadataTokenTypes.PLPAREN) {
        return addSingleSpaceIf(false);
      }
      if (type1 == HaxeMetadataTokenTypes.PLPAREN || type2 == HaxeMetadataTokenTypes.PRPAREN) {
        return addSingleSpaceIf(myHaxeCodeStyleSettings.SPACE_WITHIN_METADATA_PARENTHESES);
      }
    }

    // plain grouping parens - the keyword/call paren kinds have their own
    // rules above
    if (elementType == PARENTHESIZED_EXPRESSION && (type1 == PLPAREN || type2 == PRPAREN)) {
      return addSingleSpaceIf(mySettings.SPACE_WITHIN_PARENTHESES);
    }

    // a return's value joins the keyword's line; the value's own internals
    // may still break
    if (myHaxeCodeStyleSettings.RETURN_VALUE_ON_SAME_LINE
        && elementType == RETURN_STATEMENT && type1 == KRETURN && type2 != OSEMI) {
      return Spacing.createSpacing(1, 1, 0, false, 0);
    }

    if (type1 == OCOLON && elementType == TYPE_TAG) {
      return addSingleSpaceIf(myHaxeCodeStyleSettings.SPACE_AFTER_TYPE_REFERENCE_COLON);
    }

    if (type2 == TYPE_TAG) {
      return addSingleSpaceIf(myHaxeCodeStyleSettings.SPACE_BEFORE_TYPE_REFERENCE_COLON);
    }

    if (type1 == OARROW || type2 == OARROW) {
      return addSingleSpaceIf(arrowSpaced(type1 == OARROW ? node1 : node2));
    }

    return Spacing.createSpacing(0, 1, 0, true, mySettings.KEEP_BLANK_LINES_IN_CODE);
  }

    private boolean isInXmlTag(IElementType typeType2, IElementType elementType, IElementType parentType) {
      if(elementType == XML_MARKUP_ATTRIBUTE) return true;
      if(parentType == XML_LITERAL_EXPRESSION) return true;
      return false;
    }

  @Nullable
  private IElementType getNextElementType() {
    if (myNode.getTreeParent() == null) return null;

    ASTNode parent = myNode.getTreeParent();
    ASTNode[] parentChildren = parent.getChildren(null);
    List<ASTNode> list = Arrays.asList(parentChildren);
    int myNodeIndex = list.indexOf(myNode);
    for (int i = myNodeIndex+1; i < list.size(); i++) {
      ASTNode node = list.get(i);
      IElementType type = node.getElementType();
      if (!WHITESPACES.contains(type)) {
        return type;
      }
    }


      return null;
  }

  /**
   * The configured placement for a (header, non-block body) pair — or KEEP
   * when the pair is no such thing. Value-position ifs/tries are exempt
   * (expressionIf/expressionTry=Same keeps them as written).
   */
  private int nonBlockBodyPlacement(IElementType elementType, IElementType type1, IElementType type2, IElementType typeType2) {
    HaxeCodeStyleSettings haxe = myHaxeCodeStyleSettings;
    if (elementType == IF_STATEMENT && type2 == GUARDED_STATEMENT && typeType2 != BLOCK_STATEMENT
        && !isExpressionPosition(myNode)) {
      return resolvedBodyPlacement(haxe.IF_BODY_PLACEMENT);
    }
    if (elementType == ELSE_STATEMENT && type1 == KELSE && type2 != BLOCK_STATEMENT && type2 != IF_STATEMENT
        && !isExpressionPosition(myNode.getTreeParent())) {
      return resolvedBodyPlacement(haxe.ELSE_BODY_PLACEMENT);
    }
    if (type2 == DO_WHILE_BODY && typeType2 != BLOCK_STATEMENT) {
      return resolvedBodyPlacement(elementType == DO_WHILE_STATEMENT ? haxe.DO_WHILE_BODY_PLACEMENT : haxe.WHILE_BODY_PLACEMENT);
    }
    if (elementType == FOR_STATEMENT && type1 == PRPAREN && type2 != BLOCK_STATEMENT) {
      return resolvedBodyPlacement(haxe.FOR_BODY_PLACEMENT);
    }
    if (elementType == TRY_STATEMENT && type1 == KTRY && type2 != BLOCK_STATEMENT && type2 != CATCH_STATEMENT
        && !isExpressionPosition(myNode)) {
      return resolvedBodyPlacement(haxe.TRY_BODY_PLACEMENT);
    }
    if (elementType == CATCH_STATEMENT && type1 == PRPAREN && type2 != BLOCK_STATEMENT
        && !isExpressionPosition(myNode.getTreeParent())) {
      return resolvedBodyPlacement(haxe.CATCH_BODY_PLACEMENT);
    }
    return HaxeCodeStyleSettings.BODY_PLACEMENT_KEEP;
  }

  /** DEFAULT defers to the IDE's own "keep control statement in one line" checkbox. */
  private int resolvedBodyPlacement(int placement) {
    if (placement != HaxeCodeStyleSettings.BODY_PLACEMENT_DEFAULT) return placement;
    return mySettings.KEEP_CONTROL_STATEMENT_IN_ONE_LINE
           ? HaxeCodeStyleSettings.BODY_PLACEMENT_KEEP
           : HaxeCodeStyleSettings.BODY_PLACEMENT_NEXT_LINE;
  }

  /**
   * Blank-line handling for pairs touching a conditional-compilation token
   * near the import section: a directive whose conditional wraps imports is
   * covered by the section's keep cap, and the closing #end of such a
   * conditional takes over the section-end gap. Returns null when the pair
   * is not part of an import section (an inline #if in an expression, a
   * conditional around a type declaration).
   */
  @Nullable
  private Spacing importSectionDirectiveSpacing(ASTNode node1, ASTNode node2,
                                                IElementType type1, IElementType type2,
                                                boolean cc1, boolean cc2) {
    // the closing #end of the LAST import conditional carries the
    // section-end gap - unless more section content follows it
    IElementType lastSectionStatement = type1 == PPEND && !ONLY_COMMENTS.contains(type2)
                                        ? realNeighborType(node1, false)
                                        : null;
    boolean sectionCloses = isImportOrUsing(lastSectionStatement)
                            && !isImportOrUsing(type2)
                            && !(cc2 && continuesImportSection(node2, type2));
    if (sectionCloses) {
      int blanks = lastSectionStatement == USING_STATEMENT
                   ? myHaxeCodeStyleSettings.MINIMUM_BLANK_LINES_AFTER_USING
                   : mySettings.BLANK_LINES_AFTER_IMPORTS;
      return Spacing.createSpacing(0, 0, 1 + blanks, true, mySettings.KEEP_BLANK_LINES_IN_CODE);
    }
    if (isImportOrUsing(type1) && cc2) {
      // an OPENING directive joins the section only when its conditional
      // holds imports; #else/#elseif/#end already belong to the section
      boolean opensImports = type2 != PPIF || conditionalWrapsImports(node2);
      return opensImports ? importSectionKeepSpacing() : null;
    }
    if (cc1 && isImportOrUsing(type2)) {
      return importSectionKeepSpacing();
    }
    if (cc1 && cc2) {
      boolean inSection = isImportSectionEdge(realNeighborType(node1, false))
                          || isImportOrUsing(realNeighborType(node2, true));
      return inSection ? importSectionKeepSpacing() : null;
    }
    return null;
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
  private Spacing inlineDirectiveSpacing(ASTNode node1, ASTNode node2, IElementType type2) {
    if (type2 == BLOCK_STATEMENT) return null;
    ASTNode edge1 = lastCodeLeaf(node1);
    ASTNode edge2 = firstCodeLeaf(node2);
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
    if (isBlankConditionToken(edge1) || isBlankConditionToken(edge2)) return Spacing.createSpacing(0, 0, 0, false, 0);
    int spaces = inlineDirectiveSpaces(edgeType1, edgeType2);
    if (spaces < 0) return Spacing.createSpacing(0, 1, 0, false, 0);
    return Spacing.createSpacing(spaces, spaces, 0, false, 0);
  }

  private static boolean isBlankConditionToken(ASTNode leaf) {
    return leaf.getElementType() == PPEXPRESSION && leaf.getText().isBlank();
  }

  /** Spaces between an inline region's edge tokens; -1 keeps the written 0..1. */
  private int inlineDirectiveSpaces(IElementType edge1, IElementType edge2) {
    if (edge2 == PPIF) {
      if (OPENING_BRACKETS.contains(edge1)) return 0;
      boolean typeHintColon = edge1 == OCOLON && myNode.getElementType() == TYPE_TAG;
      if (typeHintColon) return myHaxeCodeStyleSettings.SPACE_AFTER_TYPE_REFERENCE_COLON ? 1 : 0;
      return 1;
    }
    if (edge1 == PPEXPRESSION && edge2 == PPEXPRESSION) return -1;
    if (edge2 == PPEND) return OPENING_BRACKETS.contains(edge1) ? 0 : 1;
    if (edge1 == PPEND) return HUGS_CLOSING_DIRECTIVE.contains(edge2) ? 0 : 1;
    return 1;
  }

  /** The #if..#end region holding the token is written on one line. */
  private static boolean isInlineConditional(ASTNode token) {
    if (token.textContains('\n')) return false;
    return !newlineTowardsRegionEnd(token, false) && !newlineTowardsRegionEnd(token, true);
  }

  /**
   * Walks the leaves from the token to its region's #if (backward) or #end
   * (forward), through nested regions; true when a newline lies between, or
   * the region is unterminated.
   */
  private static boolean newlineTowardsRegionEnd(ASTNode token, boolean forward) {
    IElementType target = forward ? PPEND : PPIF;
    IElementType nested = forward ? PPIF : PPEND;
    if (token.getElementType() == target) return false;
    int depth = 0;
    PsiElement leaf = token.getPsi();
    while (true) {
      leaf = forward ? PsiTreeUtil.nextLeaf(leaf) : PsiTreeUtil.prevLeaf(leaf);
      if (leaf == null) return true;
      IElementType type = leaf.getNode().getElementType();
      if (type == target && depth == 0) return false;
      if (type == target) depth--;
      else if (type == nested) depth++;
      else if (leaf.textContains('\n')) return true;
    }
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
    if (parent == null || parent.getElementType() != FUNCTION_TYPE) return myHaxeCodeStyleSettings.SPACE_AROUND_ARROW;
    return isNewFunctionTypeArrow(arrow)
           ? myHaxeCodeStyleSettings.SPACE_AROUND_FUNCTION_TYPE_ARROW
           : myHaxeCodeStyleSettings.SPACE_AROUND_OLD_FUNCTION_TYPE_ARROW;
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
    return keepCappedBlanks(myHaxeCodeStyleSettings.KEEP_BLANK_LINES_BETWEEN_IMPORTS);
  }

  /** The fallback pair shape with a tighter blank-line cap: single space at most, written breaks kept. */
  private static Spacing keepCappedBlanks(int keepBlankLines) {
    return Spacing.createSpacing(0, 1, 0, true, keepBlankLines);
  }

  /** Whether the whole multi-var statement, joined onto its current line, would pass the configured split width. */
  private boolean multiVarLineExceedsSplitWidth() {
    int splitWidth = myHaxeCodeStyleSettings.MULTI_VAR_SPLIT_WIDTH;
    if (splitWidth <= 0) return false;
    // a declarator at or under the fill threshold keeps the list filling
    // (the tool's anyItemLength rule precedes its split rule)
    int fillItemLength = myHaxeCodeStyleSettings.MULTI_VAR_FILL_ITEM_LENGTH;
    if (fillItemLength > 0 && shortestDeclaratorLength(myNode) <= fillItemLength) return false;
    PsiFile file = myNode.getPsi().getContainingFile();
    if (file == null) return false;
    // the statement's post-format line indent matches its current one in all
    // but pathological inputs - good enough for a width heuristic
    CharSequence text = file.getViewProvider().getContents();
    String lineIndent = HaxeIndentText.lineIndentAt(text, myNode.getStartOffset());
    int indentColumns = HaxeIndentText.indentWidth(lineIndent, tabSize());
    return indentColumns + oneLineWidth(myNode) >= splitWidth;
  }

  /** The node's width as it would print on one line: every whitespace run (line breaks included) one space. */
  private static int oneLineWidth(@NotNull ASTNode node) {
    // any whitespace run, joined into the single space it prints as
    return node.getText().replaceAll("\\s+", " ").length();
  }

  private int tabSize() {
    CommonCodeStyleSettings.IndentOptions options = mySettings.getIndentOptions();
    return options == null ? 4 : options.TAB_SIZE;
  }

  /** The pair's second node is a call argument (after a comma) that the joined-line fill moves down. */
  private boolean callFillBreaksBefore(ASTNode argument, IElementType type1) {
    if (type1 != OCOMMA) return false;
    ASTNode list = HaxeCallFill.listOf(argument);
    return list != null && HaxeCallFill.brokenArguments(list, mySettings).contains(argument);
  }

  private boolean additiveChainBreaksBefore(ASTNode operator) {
    return HaxeAdditiveChainRules.breaksBefore(operator, mySettings, myHaxeCodeStyleSettings);
  }

  /**
   * The shortest declarator as haxe-formatter measures its multiVar items:
   * each with its trailing comma or semicolon, the first one two wider (its
   * item starts at the var keyword's gap).
   */
  private static int shortestDeclaratorLength(@NotNull ASTNode declarationList) {
    int shortest = Integer.MAX_VALUE;
    boolean first = true;
    for (ASTNode child = declarationList.getFirstChildNode(); child != null; child = child.getTreeNext()) {
      if (child.getElementType() != LOCAL_VAR_DECLARATION) continue;
      int trailing = first ? 2 : 1;
      shortest = Math.min(shortest, oneLineWidth(child) + trailing);
      first = false;
    }
    return shortest == Integer.MAX_VALUE ? 0 : shortest;
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
   * neighbor scan cannot see into.
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

  /**
   * Whether the whole and/or chain around this expression goes one operand
   * per line, per the configured opBoolChain thresholds (inclusive, like
   * the tool's conditions): a joined line reaching the line threshold while
   * holding an operand at the item threshold, or the operand count reached
   * with items totaling more than the total guard. The chain spans every
   * same-chain nesting level (mixed && and || included, matching the tool);
   * a parenthesized group counts as ONE operand and starts a chain of its
   * own. Line length is measured as the chain JOINED onto its current line
   * (the trailing close-parens are not counted - a few columns of slack no
   * threshold sits on).
   */
  private boolean boolChainSplitsOnePerLine(@NotNull ASTNode chainMember) {
    HaxeCodeStyleSettings haxe = myHaxeCodeStyleSettings;
    ASTNode root = chainMember;
    while (root.getTreeParent() != null && isLogicExpression(root.getTreeParent().getElementType())) {
      root = root.getTreeParent();
    }
    int[] metrics = new int[3];
    collectBoolChainItems(root, metrics);
    boolean countTrigger = haxe.BOOL_CHAIN_SPLIT_ITEM_COUNT > 0
                           && metrics[0] >= haxe.BOOL_CHAIN_SPLIT_ITEM_COUNT
                           && metrics[1] > haxe.BOOL_CHAIN_SPLIT_TOTAL_LENGTH;
    if (countTrigger) return true;
    if (haxe.BOOL_CHAIN_SPLIT_LINE_LENGTH <= 0 || haxe.BOOL_CHAIN_SPLIT_ITEM_LENGTH <= 0) return false;
    if (metrics[2] < haxe.BOOL_CHAIN_SPLIT_ITEM_LENGTH) return false;
    PsiFile file = root.getPsi().getContainingFile();
    if (file == null) return false;
    CharSequence text = file.getViewProvider().getContents();
    int startColumn = HaxeIndentText.columnAt(text, root.getStartOffset(), tabSize());
    return startColumn + oneLineWidth(root) >= haxe.BOOL_CHAIN_SPLIT_LINE_LENGTH;
  }

  /** Fills {count, total item length, longest item length}. */
  private static void collectBoolChainItems(@NotNull ASTNode chain, int[] metrics) {
    for (ASTNode child = chain.getFirstChildNode(); child != null; child = child.getTreeNext()) {
      IElementType type = child.getElementType();
      if (WHITESPACES.contains(type) || COMMENTS.contains(type) || LOGIC_OPERATORS.contains(childTokenType(child))) {
        continue;
      }
      if (isLogicExpression(type)) {
        collectBoolChainItems(child, metrics);
        continue;
      }
      metrics[0]++;
      int length = oneLineWidth(child);
      metrics[1] += length;
      metrics[2] = Math.max(metrics[2], length);
    }
  }

  private static boolean isLogicExpression(@Nullable IElementType type) {
    return type == LOGIC_AND_EXPRESSION || type == LOGIC_OR_EXPRESSION;
  }

  /** The wrapped operator element's own token, for set membership tests. */
  @Nullable
  private static IElementType childTokenType(@NotNull ASTNode node) {
    ASTNode first = node.getFirstChildNode();
    return first == null ? node.getElementType() : first.getElementType();
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
    int gap = myHaxeCodeStyleSettings.BLANK_LINES_BETWEEN_FIELD_GROUPS;
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
      gap = Math.max(gap, myHaxeCodeStyleSettings.BLANK_LINES_AFTER_DOCUMENTED_FIELD);
    }
    return gap;
  }

  /** The declaration's own doc comment: the previous real sibling, metadata skipped (doc sits above the meta). */
  private static boolean hasDocComment(@NotNull ASTNode declaration) {
    for (ASTNode prev = declaration.getTreePrev(); prev != null; prev = prev.getTreePrev()) {
      IElementType type = prev.getElementType();
      if (WHITESPACES.contains(type) || type == EMBEDDED_META) continue;
      return type == DOC_COMMENT;
    }
    return false;
  }

  /** The element type of the nearest sibling that is real code — not whitespace, comment or conditional-compilation token. */
  @Nullable
  private static IElementType realNeighborType(ASTNode node, boolean forward) {
    ASTNode neighbor = realNeighbor(node, forward);
    return neighbor == null ? null : neighbor.getElementType();
  }

  /** The nearest sibling that is real code — not whitespace, comment or conditional-compilation token. */
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
   * An if/try used as a VALUE ({@code var x = if (c) 1 else 2;}) rather than
   * as a statement - haxe-formatter's expressionIf/expressionTry=Same keeps
   * those on one line regardless of the statement-body policies.
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
    return parentType == ARRAY_LITERAL || parentType == MAP_LITERAL;
  }

  /** Only braces and whitespace inside - the {}-collapse owns its interior. */
  private static boolean isEmptyBlock(@Nullable ASTNode block) {
    if (block == null || block.getElementType() != BLOCK_STATEMENT) return false;
    for (ASTNode child = block.getFirstChildNode(); child != null; child = child.getTreeNext()) {
      IElementType type = child.getElementType();
      if (type == PLCURLY || type == PRCURLY || WHITESPACES.contains(type)) continue;
      return false;
    }
    return true;
  }

  /** Which keep-in-one-line option keeps an empty body's {} on the header's line. */
  private boolean emptyBodyStaysInline(IElementType headerType) {
    // FUNCTION_LITERAL first: FUNCTION_DEFINITION contains it too
    if (headerType == FUNCTION_LITERAL) return mySettings.KEEP_SIMPLE_LAMBDAS_IN_ONE_LINE;
    if (FUNCTION_DEFINITION.contains(headerType)) return mySettings.KEEP_SIMPLE_METHODS_IN_ONE_LINE;
    return mySettings.KEEP_SIMPLE_BLOCKS_IN_ONE_LINE;
  }

  /**
   * A placement option decides the keyword's line after a BLOCK, overriding
   * kept line breaks: false must JOIN "} else", not merely allow it. After a
   * non-block body ("trace(x); else") the written break stays — joining onto
   * the statement reads wrong and haxe-formatter keeps it on its own line too
   * (any body placement other than KEEP forces that break).
   */
  private Spacing keywordPlacement(boolean spaceBefore, boolean onNewLine, ASTNode before, int precedingBodyPlacement) {
    final int spaces = spaceBefore ? 1 : 0;
    if (!endsWithRightCurly(before)) {
      boolean breakBefore = onNewLine || precedingBodyPlacement != HaxeCodeStyleSettings.BODY_PLACEMENT_KEEP;
      return addSingleSpaceIf(spaceBefore, breakBefore);
    }
    return Spacing.createSpacing(spaces, spaces, onNewLine ? 1 : 0, false, 0);
  }

  private static boolean endsWithRightCurly(ASTNode node) {
    ASTNode last = node;
    while (last != null) {
      ASTNode child = last.getLastChildNode();
      if (child == null) break;
      // trailing whitespace/comments hide the real last token
      while (child != null && (WHITESPACES.contains(child.getElementType()) || ONLY_COMMENTS.contains(child.getElementType()))) {
        child = child.getTreePrev();
      }
      if (child == null) break;
      last = child;
    }
    return last != null && last.getElementType() == PRCURLY;
  }

  private Spacing addSingleSpaceIf(boolean condition) {
    return addSingleSpaceIf(condition, false);
  }

  private Spacing addSingleSpaceIf(boolean condition, boolean linesFeed) {
    final int spaces = condition ? 1 : 0;
    final int lines = linesFeed ? 1 : 0;
    return Spacing.createSpacing(spaces, spaces, lines, keepLineBreaks, mySettings.KEEP_BLANK_LINES_IN_CODE);
  }

  private Spacing setBraceSpace(boolean needSpaceSetting,
                                @CommonCodeStyleSettings.BraceStyleConstant int braceStyleSetting,
                                TextRange textRange) {
    final int spaces = needSpaceSetting ? 1 : 0;
    if (braceStyleSetting == CommonCodeStyleSettings.NEXT_LINE_IF_WRAPPED && textRange != null) {
      return Spacing.createDependentLFSpacing(spaces, spaces, textRange, keepLineBreaks, mySettings.KEEP_BLANK_LINES_IN_CODE);
    }
    else {
      final int lineBreaks = braceStyleSetting == CommonCodeStyleSettings.END_OF_LINE ||
                             braceStyleSetting == CommonCodeStyleSettings.NEXT_LINE_IF_WRAPPED ? 0 : 1;
      return Spacing.createSpacing(spaces, spaces, lineBreaks, false, 0);
    }
  }

  private Spacing setStatementSpacing(int minSpaces, int maxSpaces, int minLineFeeds, boolean keepLineBreaks, int keepBlankLines) {
    int lineFeeds = 1 +  minLineFeeds;
    return Spacing.createSpacing(minSpaces, maxSpaces, lineFeeds, keepLineBreaks, keepBlankLines);
  }

  private boolean isClassBodyType(IElementType type) {
    return CLASS_BODY_TYPES.contains(type);
  }

  private boolean isClassDeclaration(IElementType type) {
    return CLASS_TYPES.contains(type);
  }

  /** Any top-level type declaration; CLASS_TYPES lacks the body-less typedef kind. */
  private static boolean isTypeDeclaration(IElementType type) {
    return CLASS_TYPES.contains(type) || type == TYPEDEF_DECLARATION;
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
    int depth = Math.max(1, myHaxeCodeStyleSettings.IMPORT_GROUP_PACKAGE_DEPTH);
    int keep = Math.min(depth, segments.length);
    return String.join(".", Arrays.asList(segments).subList(0, keep));
  }

  private boolean blockBeginsWith(Block block, IElementType type) {
    if (null == block && null == type) return false;
    List<Block> subBlocks = block.getSubBlocks();
    if (!subBlocks.isEmpty()) {
      Block first = subBlocks.getFirst();
      final ASTNode node = ((AbstractBlock)first).getNode();
      return node.getElementType() == type;
    }
    return false;
  }

  private boolean isFirstChild(Block block) {
    return ((AbstractBlock)block).getNode() == myNode.getFirstChildNode();
  }

  private boolean isLastChild(Block block) {
    return ((AbstractBlock)block).getNode() == myNode.getLastChildNode();
  }

  private boolean isFieldDeclaration(IElementType type) {
    // Sometimes, the field declaration rule gets matched as a LOCAL_VAR_DECLARATION_LIST (in its minimal form)
    // during an incremental reparse, because the parser doesn't have the class vs. method context at that point.

    return type == FIELD_DECLARATION
        || type == LOCAL_VAR_DECLARATION_LIST;
  }

  private boolean isMethodDeclarationOrConstructorDeclaration(IElementType type) {
    return type == METHOD_DECLARATION || type == CONSTRUCTOR_DECLARATION;
  }
}
