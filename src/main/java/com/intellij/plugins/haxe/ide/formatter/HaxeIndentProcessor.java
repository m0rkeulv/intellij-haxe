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
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;
import com.intellij.plugins.haxe.util.UsefulPsiTreeUtil;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.codeStyle.CommonCodeStyleSettings;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.Nullable;

import static com.intellij.plugins.haxe.lang.lexer.HaxeDocTokenTypes.*;
import static com.intellij.plugins.haxe.lang.lexer.HaxeTokenTypeSets.COMMENTS;
import static com.intellij.plugins.haxe.lang.lexer.HaxeTokenTypeSets.DOC_COMMENT;
import static com.intellij.plugins.haxe.ide.formatter.HaxeFormatterTokenSets.FUNCTION_HEADER_END;
import static com.intellij.plugins.haxe.lang.lexer.HaxeTokenTypeSets.FUNCTION_DEFINITION;
import static com.intellij.plugins.haxe.lang.lexer.HaxeTokenTypeSets.PPBODY;
import static com.intellij.plugins.haxe.lang.lexer.HaxeTokenTypes.*;

/**
 * @author: Fedor.Korotkov
 */
public class HaxeIndentProcessor {
  private final CommonCodeStyleSettings settings;

  public HaxeIndentProcessor(CommonCodeStyleSettings settings) {
    this.settings = settings;
  }

  public Indent getChildIndent(ASTNode node) {
    final IElementType elementType = node.getElementType();
    final ASTNode prevSibling = UsefulPsiTreeUtil.getPrevSiblingSkipWhiteSpacesAndComments(node);
    final IElementType prevSiblingType = prevSibling == null ? null : prevSibling.getElementType();
    final ASTNode parent = node.getTreeParent();
    final IElementType parentType = parent != null ? parent.getElementType() : null;
    final ASTNode superParent = parent == null ? null : parent.getTreeParent();
    final IElementType superParentType = superParent == null ? null : superParent.getElementType();
    final ASTNode firstChild = node.getFirstChildNode();
    final IElementType firstChildType = firstChild == null ? null : firstChild.getElementType();

    final int braceStyle = FUNCTION_DEFINITION.contains(superParentType) ? settings.METHOD_BRACE_STYLE : settings.BRACE_STYLE;

    if (parent == null || parent.getTreeParent() == null) {
      return Indent.getNoneIndent();
    }
    // an inactive branch's block already sits at the right indent (it is a
    // comment-shaped sibling); the chameleon wrapper layers are transparent,
    // so the content aligns with the directives and inner elements use the
    // normal rules relative to their own parents
    if (parentType == PPBODY
        || parentType == INACTIVE_MEMBER_LIST
        || parentType == INACTIVE_STATEMENT_LIST
        || parentType == INACTIVE_MODULE_LIST) {
      return Indent.getNoneIndent();
    }
    if (parentType == DOC_COMMENT) {
      if (elementType == DOC_LEADING_ASTERISK) {
        // javadoc-style stars align under the /**'s first star
        return Indent.getSpaceIndent(1);
      }
      if (elementType == DOC_END) {
        // the starred style aligns its closer under the stars too; haxedoc
        // puts **/ back at the comment's own indent
        boolean starredStyle = parent.findChildByType(DOC_LEADING_ASTERISK) != null;
        return starredStyle ? Indent.getSpaceIndent(1) : Indent.getNoneIndent();
      }
      if (elementType == DOC_START) {
        return Indent.getNoneIndent();
      }
      // body lines sit one level inside the comment; author depth beyond the
      // common prefix rides inside the token text (markdown) and stays untouched
      return Indent.getNormalIndent();
    }
    if (COMMENTS.contains(elementType)) {
      // first-column preservation protects //-disabled code; a doc comment
      // belongs to its member and always follows its scope (as javadoc does)
      if (elementType != DOC_COMMENT && settings.KEEP_FIRST_COLUMN_COMMENT && isAtFirstColumn(node)) {
        return Indent.getAbsoluteNoneIndent();
      }
      // module-level comments sit at the file margin like their sibling declarations
      if (parentType == MODULE) {
        return Indent.getNoneIndent();
      }
      return Indent.getNormalIndent();
    }
    if (elementType == PLCURLY || elementType == PRCURLY) {
      switch (braceStyle) {
        case CommonCodeStyleSettings.END_OF_LINE:
        case CommonCodeStyleSettings.NEXT_LINE:
        case CommonCodeStyleSettings.NEXT_LINE_IF_WRAPPED:
          return Indent.getNoneIndent();
        case CommonCodeStyleSettings.NEXT_LINE_SHIFTED:
        case CommonCodeStyleSettings.NEXT_LINE_SHIFTED2:
          return Indent.getNormalIndent();
        default:
          return Indent.getNoneIndent();
      }
    }
    if (parentType == PARENTHESIZED_EXPRESSION) {
      if (elementType == PLPAREN || elementType == PRPAREN) {
        return Indent.getNoneIndent();
      }
      return Indent.getNormalIndent();
    }
    if (needIndent(parentType, elementType)) {
      final PsiElement psi = node.getPsi();
      if (psi.getParent() instanceof PsiFile) {
        return Indent.getNoneIndent();
      }
      return Indent.getNormalIndent();
    }
    // a parameter/argument list carries no indent of its own: its ITEMS do
    // (below), so a chopped-down list (the break right after the paren) and
    // a mid-list wrap land at the same depth instead of stacking
    if (FUNCTION_DEFINITION.contains(parentType) || parentType == CALL_EXPRESSION) {
      if (elementType == PARAMETER_LIST || elementType == EXPRESSION_LIST || elementType == CALL_EXPRESSION_LIST) {
        return Indent.getNoneIndent();
      }
    }
    // a wrapped list item continues in from the line that opened the list:
    // call arguments and enum constructor parameters one step, a method
    // signature's parameters two - the signature stands off from the body
    // that follows at one
    if (parentType == PARAMETER_LIST || parentType == EXPRESSION_LIST || parentType == CALL_EXPRESSION_LIST) {
      ASTNode listOwner = parent.getTreeParent();
      IElementType ownerType = listOwner == null ? null : listOwner.getElementType();
      // an array literal's list is indented as a block of its own (needIndent);
      // its items sit at the list's level
      if (ownerType != ARRAY_LITERAL) {
        if (elementType == PLPAREN || elementType == PRPAREN || elementType == OCOMMA) {
          return Indent.getNoneIndent();
        }
        boolean signature = parentType == PARAMETER_LIST && FUNCTION_DEFINITION.contains(ownerType);
        return signature ? Indent.getContinuationIndent() : Indent.getNormalIndent();
      }
    }
    // `new T(a, b)` keeps its arguments as direct children (no list node);
    // an argument follows the paren or a comma
    if (parentType == NEW_EXPRESSION && (prevSiblingType == PLPAREN || prevSiblingType == OCOMMA)
        && elementType != PRPAREN) {
      return Indent.getNormalIndent();
    }
    // a named function's non-block body on its own line indents one step
    // (FUNCTION_DEFINITION lacks the module-level kind); the header's own
    // trailing parts also follow a header end and stay unindented
    boolean functionParent = FUNCTION_DEFINITION.contains(parentType) || parentType == MODULE_METHOD_DECLARATION;
    boolean afterHeaderEnd = FUNCTION_HEADER_END.contains(prevSiblingType);
    boolean headerTrailer = FUNCTION_HEADER_END.contains(elementType)
                            || elementType == BLOCK_STATEMENT
                            || elementType == OSEMI;

    if (functionParent && afterHeaderEnd && !headerTrailer) {
      return Indent.getNormalIndent();
    }
    if (parentType == FOR_STATEMENT && prevSiblingType == PRPAREN && elementType != BLOCK_STATEMENT) {
      return Indent.getNormalIndent();
    }
    if (parentType == TRY_STATEMENT && prevSiblingType == KTRY
        && elementType != BLOCK_STATEMENT && elementType != CATCH_STATEMENT) {
      return Indent.getNormalIndent();
    }
    if (parentType == CATCH_STATEMENT && prevSiblingType == PRPAREN && elementType != BLOCK_STATEMENT) {
      return Indent.getNormalIndent();
    }
    if (parentType == WHILE_STATEMENT && prevSiblingType == PRPAREN
        && elementType == DO_WHILE_BODY && firstChildType != BLOCK_STATEMENT) {
      return Indent.getNormalIndent();
    }
    if (parentType == DO_WHILE_STATEMENT && prevSiblingType == KDO
        && elementType == DO_WHILE_BODY && firstChildType != BLOCK_STATEMENT) {
      return Indent.getNormalIndent();
    }
    if (parentType == RETURN_STATEMENT &&
        prevSiblingType == KRETURN &&
        elementType != BLOCK_STATEMENT) {
      return Indent.getNormalIndent();
    }
    // IF_STATEMENT statement components
    if ((parentType == GUARDED_STATEMENT || parentType == ELSE_STATEMENT) &&
        elementType != BLOCK_STATEMENT &&
        elementType != KELSE &&
        elementType != IF_STATEMENT) {
      return Indent.getNormalIndent();
    }
    if (parentType == ANONYMOUS_TYPE_BODY) {
      return Indent.getNormalIndent();
    }
    // a wrapped chain link (.map(...) on its own line) indents ONE step from
    // the chain's base line - continuation indent would be a declaration-style
    // double step
    if (parentType == REFERENCE_EXPRESSION && elementType != CALL_EXPRESSION
        && parent.getFirstChildNode() != null
        && parent.getFirstChildNode().getElementType() == CALL_EXPRESSION) {
      return Indent.getNormalIndent();
    }
    // a wrapped extends/implements clause continues the declaration header
    if (parentType == INHERIT_LIST) {
      return Indent.getContinuationIndent();
    }
    // wrapped ternary parts (branches, or the signs leading them) continue
    // the condition's line
    if (parentType == TERNARY_EXPRESSION && prevSibling != null) {
      return Indent.getContinuationIndent();
    }
    return Indent.getNoneIndent();
  }

  private static boolean needIndent(@Nullable IElementType type, IElementType elementType) {
    if (type == null) {
      return false;
    }
    boolean result = type == BLOCK_STATEMENT;
    result = result || type == CLASS_BODY;
    result = result || type == ABSTRACT_BODY;
    result = result || (type == ARRAY_LITERAL && elementType != PLBRACK && elementType != PRBRACK);
    result = result || (type == MAP_LITERAL && elementType != PLBRACK && elementType != PRBRACK);
    result = result || type == OBJECT_LITERAL;
    result = result || type == XML_LITERAL_EXPRESSION;
    result = result || type == XML_MARKUP_ELEMENT;
    // NOT the map entry types: indenting an entry's children indents the
    // entry's own first token again when the literal wraps one-per-line
    result = result || type == MAP_LOOP_INITIALIZER_EXPRESSION;
    result = result || type == EXTERN_CLASS_DECLARATION_BODY;
    result = result || type == ENUM_BODY;
    result = result || type == INTERFACE_BODY;
    result = result || type == SWITCH_BLOCK;
    result = result || type == SWITCH_CASE_BLOCK;
    return result;
  }

  private static boolean isAtFirstColumn(ASTNode node) {
    PsiElement element = node.getPsi();
    if (null == element) {
      return false;
    }
    PsiFile file = element.getContainingFile();
    Project project = element.getProject();
    if (null == file || null == project) {
      return false;
    }
    Document doc = PsiDocumentManager.getInstance(project).getDocument(file);
    if (null == doc) {
      return false;
    }
    int line = doc.getLineNumber(node.getStartOffset());
    int lineStart = doc.getLineStartOffset(line);
    return node.getStartOffset() == lineStart;
  }
}
