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

import com.intellij.formatting.*;
import com.intellij.formatting.templateLanguages.BlockWithParent;
import com.intellij.lang.ASTNode;
import com.intellij.openapi.util.TextRange;
import com.intellij.plugins.haxe.HaxeLanguage;
import com.intellij.plugins.haxe.ide.formatter.settings.HaxeCodeStyleSettings;
import com.intellij.plugins.haxe.lang.psi.impl.HaxeInactiveBody;
import com.intellij.psi.codeStyle.CodeStyleSettings;
import com.intellij.psi.codeStyle.CommonCodeStyleSettings;
import com.intellij.psi.formatter.FormatterUtil;
import com.intellij.psi.formatter.common.AbstractBlock;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.tree.TokenSet;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static com.intellij.plugins.haxe.lang.lexer.HaxeTokenTypeSets.*;
import static com.intellij.plugins.haxe.lang.lexer.HaxeTokenTypes.*;

/**
 * @author: Fedor.Korotkov
 */
public class HaxeBlock extends AbstractBlock implements BlockWithParent {
  // after one of these, a new line sits one step in: an opening bracket, a
  // type body, a directive or a comment
  private static final TokenSet INDENT_OPENERS = TokenSet.orSet(
    TokenSet.create(PLPAREN, PLCURLY, CONDITIONAL_STATEMENT_ID, PPELSE, PPEND, PPELSEIF), CLASS_BODY_TYPES, ONLY_COMMENTS);

  private final HaxeIndentProcessor myIndentProcessor;
  private final HaxeSpacingProcessor mySpacingProcessor;
  private final HaxeWrappingProcessor myWrappingProcessor;
  private final HaxeAlignmentProcessor myAlignmentProcessor;
  // the wrap of this assignment's sign, once built: the call arguments
  // after it wrap as its children
  private Wrap myAssignmentSignWrap = null;
  private final Indent myIndent;
  private final CodeStyleSettings mySettings;
  private BlockWithParent myParent;

  protected HaxeBlock(ASTNode node,
                      Wrap wrap,
                      Alignment alignment,
                      CodeStyleSettings settings) {
    super(node, wrap, alignment);
    mySettings = settings;
    CommonCodeStyleSettings common = settings.getCommonSettings(HaxeLanguage.INSTANCE);
    HaxeCodeStyleSettings haxe = settings.getCustomSettings(HaxeCodeStyleSettings.class);
    myIndentProcessor = new HaxeIndentProcessor(common, haxe);
    mySpacingProcessor = new HaxeSpacingProcessor(node, common, haxe);
    myWrappingProcessor = new HaxeWrappingProcessor(node, common);
    myAlignmentProcessor = new HaxeAlignmentProcessor(node, common);
    myIndent = myIndentProcessor.getChildIndent(myNode);
  }

  @Nullable
  @Override
  public String getDebugName() {
    return getClass().getSimpleName() + "(" + myNode.getElementType() + ")";
  }

  /**
   * A PPBODY chameleon spans the WHOLE region between directives - the lexer
   * remaps every token there, edge whitespace included. Reported as-is that
   * whitespace would sit inside the block, out of the engine's reach, and
   * blank lines around an inactive branch would survive every spacing rule -
   * so the range is trimmed to the branch's real content.
   */
  @Override
  public @NotNull TextRange getTextRange() {
    if (myNode.getElementType() != PPBODY) return super.getTextRange();
    // trim by the same child-node walk that builds the sub-blocks, so every
    // child block stays inside the reported range
    ASTNode first = myNode.getFirstChildNode();
    while (first != null && FormatterUtil.containsWhiteSpacesOnly(first)) first = first.getTreeNext();
    ASTNode last = myNode.getLastChildNode();
    while (last != null && FormatterUtil.containsWhiteSpacesOnly(last)) last = last.getTreePrev();
    if (first == null || last == null) return super.getTextRange();  // a whitespace-only branch stays as written
    return new TextRange(first.getStartOffset(), last.getTextRange().getEndOffset());
  }

  @Override
  public Indent getIndent() {
    return myIndent;
  }

  @Override
  public Spacing getSpacing(Block child1, @NotNull Block child2) {
    return mySpacingProcessor.getSpacing(child1, child2);
  }

  @Override
  protected List<Block> buildChildren() {
    IElementType type = getNode().getElementType();
    if (type == DOC_COMMENT) return buildDocCommentChildren();
    if (type == PPBODY) return buildInactiveBranchChildren();
    return childBlocks(child -> childBlock(child, createChildWrap(child), createChildAlignment(child), true));
  }

  /**
   * Line blocks over the lazily parsed doc sub-tree: only the managed
   * line-leading whitespace between them is formatted (per the doc indent
   * rules); wraps and alignments never apply inside a comment. The toggle
   * keeps the comment one opaque block.
   */
  private List<Block> buildDocCommentChildren() {
    if (!mySettings.getCustomSettings(HaxeCodeStyleSettings.class).FORMAT_DOC_COMMENTS) {
      return EMPTY;
    }
    return childBlocks(child -> childBlock(child, Wrap.createWrap(WrapType.NONE, false), null, false));
  }

  /**
   * Blocks over an inactive conditional branch's lazily parsed sub-tree: the
   * children are ordinary Haxe PSI, so the normal indent and spacing rules
   * apply inside. A branch without a clean parse stays one opaque block,
   * preserved verbatim like the reference formatter's own fallback.
   */
  private List<Block> buildInactiveBranchChildren() {
    HaxeCodeStyleSettings haxe = mySettings.getCustomSettings(HaxeCodeStyleSettings.class);
    if (!(getNode().getPsi() instanceof HaxeInactiveBody body) || HaxeInactiveBranches.preservedVerbatim(body, haxe)) {
      return EMPTY;
    }
    return childBlocks(child -> childBlock(child, createChildWrap(child), null, true));
  }

  /** One block per non-whitespace child, in order. */
  private List<Block> childBlocks(Function<ASTNode, HaxeBlock> blockOf) {
    List<Block> children = new ArrayList<>();
    for (ASTNode child = getNode().getFirstChildNode(); child != null; child = child.getTreeNext()) {
      if (!FormatterUtil.containsWhiteSpacesOnly(child)) children.add(blockOf.apply(child));
    }
    return children;
  }

  private HaxeBlock childBlock(ASTNode child, Wrap wrap, @Nullable Alignment alignment, boolean linkWrapping) {
    HaxeBlock block = new HaxeBlock(child, wrap, alignment, mySettings);
    block.setParent(this);
    // wrap groups can span levels (a literal's items chop with its closing
    // bracket, a call chain's dots chop together) - the child's processor
    // reaches the enclosing ones through this link
    if (linkWrapping) block.myWrappingProcessor.setParentProcessor(myWrappingProcessor);
    return block;
  }

  private Wrap createChildWrap(ASTNode child) {
    Wrap wrap = myWrappingProcessor.createChildWrap(child, myAssignmentSignWrap);
    if (child.getElementType() == ASSIGN_OPERATION) {
      myAssignmentSignWrap = wrap;
    }
    return wrap;
  }

  @Nullable
  protected Alignment createChildAlignment(ASTNode child) {
    if (child.getElementType() != PLPAREN && child.getElementType() != BLOCK_STATEMENT) {
      return myAlignmentProcessor.createChildAlignment();
    }
    return null;
  }

  @NotNull
  @Override
  public ChildAttributes getChildAttributes(final int newIndex) {
    int index = newIndex;
    ASTBlock prev = null;
    while (index > 0) {
      index--;
      prev = (ASTBlock)getSubBlocks().get(index);
      IElementType type = prev.getNode().getElementType();
      if (type != OSEMI && !WHITESPACES.contains(type)) break;
    }

    IElementType elementType = myNode.getElementType();
    IElementType prevType = prev == null ? null : prev.getNode().getElementType();
    if (opensIndentedRegion(elementType, prevType)) {
      return new ChildAttributes(Indent.getNormalIndent(), null);
    }
    if (index == 0) {
      return new ChildAttributes(Indent.getNoneIndent(), null);
    }
    return new ChildAttributes(prev.getIndent(), prev.getAlignment());
  }

  @Override
  public boolean isLeaf() {
    return false;
  }

  @Override
  public BlockWithParent getParent() {
    return myParent;
  }

  @Override
  public void setParent(BlockWithParent newParent) {
    myParent = newParent;
  }

  /** A new line after the previous child sits one step in: after an indent opener, or a statement head's closing paren. */
  private static boolean opensIndentedRegion(IElementType elementType, @Nullable IElementType prevType) {
    return INDENT_OPENERS.contains(prevType) || isEndsWithRPAREN(elementType, prevType);
  }

  private static boolean isEndsWithRPAREN(IElementType elementType, @Nullable IElementType prevType) {
    return prevType == PRPAREN &&
           (elementType == IF_STATEMENT ||
            elementType == FOR_STATEMENT ||
            elementType == WHILE_STATEMENT);
  }
}
