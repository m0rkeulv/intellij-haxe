/*
 * Copyright 2000-2013 JetBrains s.r.o.
 * Copyright 2014-2014 AS3Boyan
 * Copyright 2014-2014 Elias Ku
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

import com.intellij.formatting.Wrap;
import com.intellij.formatting.WrapType;
import com.intellij.lang.ASTNode;
import com.intellij.psi.codeStyle.CommonCodeStyleSettings;
import com.intellij.psi.formatter.FormatterUtil;
import com.intellij.psi.formatter.WrappingUtil;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.Nullable;

import static com.intellij.plugins.haxe.lang.lexer.HaxeTokenTypeSets.*;
import static com.intellij.plugins.haxe.lang.lexer.HaxeTokenTypes.*;


/**
 * @author: Fedor.Korotkov
 */
public class HaxeWrappingProcessor {
  private final ASTNode myNode;
  private final CommonCodeStyleSettings mySettings;
  // chop-down wrapping only breaks EVERY element together when they share
  // the wrap object; these groups are owned by the construct's own processor
  // and reached from nested levels through the parent link
  private Wrap sharedItemWrap;
  private Wrap sharedChainWrap;
  private HaxeWrappingProcessor parentProcessor;

  public HaxeWrappingProcessor(ASTNode node, CommonCodeStyleSettings settings) {
    myNode = node;
    mySettings = settings;
  }

  void setParentProcessor(@Nullable HaxeWrappingProcessor parent) {
    parentProcessor = parent;
  }

  Wrap createChildWrap(ASTNode child, Wrap defaultWrap, Wrap childWrap) {
    final IElementType childType = child.getElementType();
    final IElementType elementType = myNode.getElementType();
    if (childType == OCOMMA || childType == OSEMI) return defaultWrap;

    //
    // Array/map/object literals: the items AND the closing bracket share ONE
    // chop wrap (owned by the literal's processor), so a margin-busting
    // literal breaks one item per line with the bracket on its own line.
    // The item list itself stays unwrapped - breaks come from the items.
    //
    if (mySettings.ARRAY_INITIALIZER_WRAP != CommonCodeStyleSettings.DO_NOT_WRAP) {
      final ASTNode listParent = myNode.getTreeParent();
      final IElementType listParentType = listParent == null ? null : listParent.getElementType();
      boolean literalItems = (elementType == EXPRESSION_LIST && listParentType == ARRAY_LITERAL)
                             || elementType == MAP_INITIALIZER_EXPRESSION_LIST;
      if (literalItems) {
        HaxeWrappingProcessor literalProcessor = parentProcessor != null ? parentProcessor : this;
        return literalProcessor.sharedItemWrap(mySettings.ARRAY_INITIALIZER_WRAP);
      }
      if (elementType == OBJECT_LITERAL && childType == OBJECT_LITERAL_ELEMENT) {
        return sharedItemWrap(mySettings.ARRAY_INITIALIZER_WRAP);
      }
      boolean literalCloser = ((elementType == ARRAY_LITERAL || elementType == MAP_LITERAL) && childType == PRBRACK)
                              || (elementType == OBJECT_LITERAL && childType == PRCURLY);
      if (literalCloser) {
        return sharedItemWrap(mySettings.ARRAY_INITIALIZER_WRAP);
      }
      if ((elementType == ARRAY_LITERAL || elementType == MAP_LITERAL)
          && (childType == EXPRESSION_LIST || childType == MAP_INITIALIZER_EXPRESSION_LIST)) {
        return Wrap.createWrap(WrapType.NONE, true);
      }
    }

    //
    // Method chains: every link whose receiver is a CALL breaks before its
    // dot, all links sharing the chain's ONE wrap group so chopping folds
    // the whole chain. The first link's receiver is a plain reference, so it
    // stays with the receiver (haxe-formatter's OnePerLineAfterFirst).
    //
    if (elementType == REFERENCE_EXPRESSION && childType == ODOT
        && mySettings.METHOD_CALL_CHAIN_WRAP != CommonCodeStyleSettings.DO_NOT_WRAP
        && myNode.getFirstChildNode() != null
        && myNode.getFirstChildNode().getElementType() == CALL_EXPRESSION) {
      return chainItemWrap(mySettings.METHOD_CALL_CHAIN_WRAP);
    }

    //
    // extends/implements clauses share the list's ONE wrap group. Chop mode
    // wraps the first element too (folds every clause); the fill modes must
    // NOT (wrap-first-element pulls the break back to the first clause when
    // a later one overflows).
    //
    if (elementType == INHERIT_LIST
        && (childType == EXTENDS_DECLARATION || childType == IMPLEMENTS_DECLARATION)
        && mySettings.EXTENDS_LIST_WRAP != CommonCodeStyleSettings.DO_NOT_WRAP
        && child != myNode.getFirstChildNode()) {
      if (sharedItemWrap == null) {
        // the settings UI stores "chop down if long" as EVERY_ITEM|AS_NEEDED
        boolean chop = (mySettings.EXTENDS_LIST_WRAP & CommonCodeStyleSettings.WRAP_ON_EVERY_ITEM) != 0;
        sharedItemWrap = Wrap.createWrap(WrappingUtil.getWrapType(mySettings.EXTENDS_LIST_WRAP), chop);
      }
      return sharedItemWrap;
    }

    //
    // Function definition/call
    //
    if (elementType == PARAMETER_LIST || elementType == EXPRESSION_LIST || elementType == CALL_EXPRESSION_LIST) {
      final ASTNode parent = myNode.getTreeParent();
      if (parent == null) {
        return defaultWrap;
      }
      final IElementType parentType = parent.getElementType();
      if (parentType == CALL_EXPRESSION &&
          mySettings.CALL_PARAMETERS_WRAP != CommonCodeStyleSettings.DO_NOT_WRAP) {
        if (myNode.getFirstChildNode() == child) {
          return createWrap(mySettings.CALL_PARAMETERS_LPAREN_ON_NEXT_LINE);
        }
        if (!mySettings.PREFER_PARAMETERS_WRAP && childWrap != null) {
          return Wrap.createChildWrap(childWrap, WrappingUtil.getWrapType(mySettings.CALL_PARAMETERS_WRAP), true);
        }
        return Wrap.createWrap(WrappingUtil.getWrapType(mySettings.CALL_PARAMETERS_WRAP), true);
      }
      if (FUNCTION_DEFINITION.contains(parentType) &&
          mySettings.METHOD_PARAMETERS_WRAP != CommonCodeStyleSettings.DO_NOT_WRAP) {
        if (myNode.getFirstChildNode() == child) {
          return createWrap(mySettings.METHOD_PARAMETERS_LPAREN_ON_NEXT_LINE);
        }
        if (childType == PRPAREN) {
          return createWrap(mySettings.METHOD_PARAMETERS_RPAREN_ON_NEXT_LINE);
        }
        return Wrap.createWrap(WrappingUtil.getWrapType(mySettings.METHOD_PARAMETERS_WRAP), true);
      }
    }

    if (elementType == CALL_EXPRESSION) {
      if (mySettings.CALL_PARAMETERS_WRAP != CommonCodeStyleSettings.DO_NOT_WRAP) {
        if (childType == PRPAREN) {
          return createWrap(mySettings.CALL_PARAMETERS_RPAREN_ON_NEXT_LINE);
        }
      }
    }

    //
    // If
    //
    if (elementType == IF_STATEMENT && childType == KELSE) {
      return createWrap(mySettings.ELSE_ON_NEW_LINE);
    }

    //
    //Binary expressions
    //
    if (BINARY_EXPRESSIONS.contains(elementType) && mySettings.BINARY_OPERATION_WRAP != CommonCodeStyleSettings.DO_NOT_WRAP) {
      if ((mySettings.BINARY_OPERATION_SIGN_ON_NEXT_LINE && BINARY_OPERATORS.contains(childType)) ||
          (!mySettings.BINARY_OPERATION_SIGN_ON_NEXT_LINE && isRightOperand(child))) {
        return Wrap.createWrap(WrappingUtil.getWrapType(mySettings.BINARY_OPERATION_WRAP), true);
      }
    }

    //
    // Assignment
    //
    if (elementType == ASSIGN_EXPRESSION && mySettings.ASSIGNMENT_WRAP != CommonCodeStyleSettings.DO_NOT_WRAP) {
      if (childType != ASSIGN_OPERATION) {
        if (FormatterUtil.isPrecededBy(child, ASSIGN_OPERATION) &&
            mySettings.PLACE_ASSIGNMENT_SIGN_ON_NEXT_LINE) {
          return Wrap.createWrap(WrapType.NONE, true);
        }
        return Wrap.createWrap(WrappingUtil.getWrapType(mySettings.ASSIGNMENT_WRAP), true);
      }
      else if (mySettings.PLACE_ASSIGNMENT_SIGN_ON_NEXT_LINE) {
        return Wrap.createWrap(WrapType.NORMAL, true);
      }
    }

    //
    // Ternary expressions
    //
    if (elementType == TERNARY_EXPRESSION) {
      if (myNode.getFirstChildNode() != child) {
        if (mySettings.TERNARY_OPERATION_SIGNS_ON_NEXT_LINE) {
          if (!FormatterUtil.isPrecededBy(child, OQUEST) &&
              !FormatterUtil.isPrecededBy(child, OCOLON)) {
            return Wrap.createWrap(WrappingUtil.getWrapType(mySettings.TERNARY_OPERATION_WRAP), true);
          }
        }
        else if (childType != OQUEST && childType != OCOLON) {
          return Wrap.createWrap(WrappingUtil.getWrapType(mySettings.TERNARY_OPERATION_WRAP), true);
        }
      }
      return Wrap.createWrap(WrapType.NONE, true);
    }
    return defaultWrap;
  }

  private Wrap sharedItemWrap(int wrapSetting) {
    if (sharedItemWrap == null) {
      sharedItemWrap = Wrap.createWrap(WrappingUtil.getWrapType(wrapSetting), true);
    }
    return sharedItemWrap;
  }

  /** The chain's wrap group, owned by the OUTERMOST link: nested links delegate up while the parent is still chain. */
  private Wrap chainItemWrap(int wrapSetting) {
    final ASTNode parent = myNode.getTreeParent();
    final IElementType parentType = parent == null ? null : parent.getElementType();
    boolean parentIsChain = parentProcessor != null
                            && (parentType == CALL_EXPRESSION || parentType == REFERENCE_EXPRESSION);
    if (parentIsChain) {
      return parentProcessor.chainItemWrap(wrapSetting);
    }
    if (sharedChainWrap == null) {
      sharedChainWrap = Wrap.createWrap(WrappingUtil.getWrapType(wrapSetting), true);
    }
    return sharedChainWrap;
  }

  private boolean isRightOperand(ASTNode child) {
    return myNode.getLastChildNode() == child;
  }

  private static Wrap createWrap(boolean isNormal) {
    return Wrap.createWrap(isNormal ? WrapType.NORMAL : WrapType.NONE, true);
  }

  private static Wrap createChildWrap(ASTNode child, int parentWrap, boolean newLineAfterLBrace, boolean newLineBeforeRBrace) {
    IElementType childType = child.getElementType();
    if (childType != PLPAREN && childType != PRPAREN) {
      if (FormatterUtil.isPrecededBy(child, PLBRACK)) {
        if (newLineAfterLBrace) {
          return Wrap.createChildWrap(Wrap.createWrap(parentWrap, true), WrapType.ALWAYS, true);
        }
        else {
          return Wrap.createWrap(WrapType.NONE, true);
        }
      }
      return Wrap.createWrap(WrappingUtil.getWrapType(parentWrap), true);
    }
    if (childType == PRBRACK && newLineBeforeRBrace) {
      return Wrap.createWrap(WrapType.ALWAYS, true);
    }
    return Wrap.createWrap(WrapType.NONE, true);
  }
}
