package com.intellij.plugins.haxe.lang.lexer;

import com.intellij.lang.ASTNode;
import com.intellij.lang.PsiBuilder;
import com.intellij.lang.PsiBuilderFactory;
import com.intellij.lang.parser.GeneratedParserUtilBase;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.plugins.haxe.HaxeLanguage;
import com.intellij.plugins.haxe.lang.parser.HaxeParser;
import com.intellij.plugins.haxe.lang.psi.impl.HaxeInactiveBody;
import com.intellij.psi.PsiElement;
import com.intellij.psi.TokenType;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.tree.ILazyParseableElementType;
import com.intellij.psi.tree.TokenSet;
import org.jetbrains.annotations.NotNull;

import java.util.List;

import static com.intellij.plugins.haxe.lang.lexer.HaxeTokenTypes.*;

/**
 * An INACTIVE conditional-compilation branch as a chameleon: the region
 * between directives arrives as one merged token (see HaxeLexer) and is
 * lazily parsed with a GRADED ladder of entry points chosen by where the
 * branch sits - members between class members, statements inside a body,
 * module content at top level, an expression for inline branches. A branch
 * that parses cleanly under no entry keeps the context's best entry WITH the
 * parser's own error recovery when that recovery pinned real structure (a
 * broken statement stays a local error, the rest keeps real PSI); fragments
 * that recover into nothing but error shells fall back to a flat run of raw
 * tokens. The formatter preserves both verbatim (only clean parses
 * reformat), matching haxe-formatter's own refusal to touch what it cannot
 * parse.
 */
public class HaxeInactiveBodyElementType extends ILazyParseableElementType {

  private static final List<IElementType> MEMBER_CONTEXT = List.of(INACTIVE_MEMBER_LIST, INACTIVE_STATEMENT_LIST, EXPRESSION);
  private static final List<IElementType> STATEMENT_CONTEXT = List.of(INACTIVE_STATEMENT_LIST, EXPRESSION, INACTIVE_MEMBER_LIST);
  private static final List<IElementType> MODULE_CONTEXT = List.of(INACTIVE_MODULE_LIST, INACTIVE_MEMBER_LIST, INACTIVE_STATEMENT_LIST);
  private static final List<IElementType> EXPRESSION_CONTEXT = List.of(EXPRESSION, INACTIVE_STATEMENT_LIST);

  public HaxeInactiveBodyElementType() {
    super("PPBODY", HaxeLanguage.INSTANCE);
  }

  @Override
  public ASTNode createNode(CharSequence text) {
    return new HaxeInactiveBody(this, text);
  }

  /**
   * The entry that graded the branch ERROR-FREE, or null for a recovered
   * (error-carrying) parse - which the formatter preserves verbatim.
   * Recorded on the chameleon node because the parsed tree's ROOT type is not
   * reliable - the expression root collapses into the concrete expression.
   */
  public static final Key<IElementType> PARSED_GRADE = Key.create("haxe.inactive.parsed.grade");

  @Override
  protected ASTNode doParseContents(@NotNull ASTNode chameleon, @NotNull PsiElement psi) {
    Project project = psi.getProject();
    List<IElementType> ladder = entryLadder(chameleon);
    for (IElementType entry : ladder) {
      ASTNode parsed = parse(project, chameleon, entry);
      if (!containsErrors(parsed)) {
        chameleon.putUserData(PARSED_GRADE, entry);
        return parsed;
      }
    }
    // no entry parses clean - keep the context's best recovery when it PINNED
    // real structure (a broken statement inside otherwise-parseable members:
    // the error stays local, the rest keeps real PSI for references and
    // completion). Trivial fragments - a spliced keyword, half an operator -
    // recover into nothing but error shells and read better as flat soup.
    // Either way the null grade keeps the formatter preserving the text.
    chameleon.putUserData(PARSED_GRADE, null);
    ASTNode recovered = parse(project, chameleon, ladder.get(0));
    if (hasMeaningfulStructure(recovered)) {
      return recovered;
    }
    return tokenSoup(project, chameleon);
  }

  private static final TokenSet STRUCTURE_WRAPPERS = TokenSet.create(
    INACTIVE_MEMBER_LIST,
    INACTIVE_STATEMENT_LIST,
    INACTIVE_MODULE_LIST,
    EXPRESSION);

  /**
   * Whether recovery produced NESTED real structure: a non-shell composite
   * containing another non-shell composite (a declaration with its name, a
   * statement with its expression). Error elements, dummy blocks and the
   * bare entry wrappers are shells; so is a lone concrete node whose only
   * content is an error husk around raw tokens.
   */
  private static boolean hasMeaningfulStructure(@NotNull ASTNode node) {
    IElementType type = node.getElementType();
    // collapsed nested chameleons must not be touched in a detached tree
    if (type instanceof ILazyParseableElementType) {
      return false;
    }
    if (!isShell(type) && hasRealCompositeChild(node)) {
      return true;
    }
    for (ASTNode child = node.getFirstChildNode(); child != null; child = child.getTreeNext()) {
      if (hasMeaningfulStructure(child)) {
        return true;
      }
    }
    return false;
  }

  private static boolean isShell(@NotNull IElementType type) {
    return type == TokenType.ERROR_ELEMENT
           || type == GeneratedParserUtilBase.DUMMY_BLOCK
           || STRUCTURE_WRAPPERS.contains(type);
  }

  private static boolean hasRealCompositeChild(@NotNull ASTNode node) {
    for (ASTNode child = node.getFirstChildNode(); child != null; child = child.getTreeNext()) {
      IElementType type = child.getElementType();
      if (type instanceof ILazyParseableElementType) continue;
      if (isShell(type)) continue;
      if (child.getFirstChildNode() != null) {
        return true;
      }
    }
    return false;
  }

  /** The opaque fallback: the branch's raw tokens as flat leaves, structure-free. */
  @NotNull
  private ASTNode tokenSoup(@NotNull Project project, @NotNull ASTNode chameleon) {
    PsiBuilder builder = PsiBuilderFactory.getInstance()
      .createBuilder(project, chameleon, new HaxeLexer(project), HaxeLanguage.INSTANCE, chameleon.getChars());
    PsiBuilder.Marker root = builder.mark();
    while (!builder.eof()) {
      builder.advanceLexer();
    }
    root.done(this);
    return builder.getTreeBuilt().getFirstChildNode();
  }

  /** Entries in context order: what the branch's surroundings say its content most likely is. */
  @NotNull
  private static List<IElementType> entryLadder(@NotNull ASTNode chameleon) {
    ASTNode parent = chameleon.getTreeParent();
    IElementType parentType = parent == null ? null : parent.getElementType();
    if (parentType == CLASS_BODY || parentType == ABSTRACT_BODY
        || parentType == INTERFACE_BODY || parentType == EXTERN_CLASS_DECLARATION_BODY) {
      return MEMBER_CONTEXT;
    }
    if (parentType == BLOCK_STATEMENT || parentType == SWITCH_CASE_BLOCK) {
      return STATEMENT_CONTEXT;
    }
    if (parentType == null || parentType == HaxeTokenTypeSets.HAXE_FILE || parentType == MODULE) {
      return MODULE_CONTEXT;
    }
    // inline positions (expression lists, initializers, conditions...)
    return EXPRESSION_CONTEXT;
  }

  @NotNull
  private static ASTNode parse(@NotNull Project project, @NotNull ASTNode chameleon, @NotNull IElementType entry) {
    PsiBuilder builder = PsiBuilderFactory.getInstance()
      .createBuilder(project, chameleon, new HaxeLexer(project), HaxeLanguage.INSTANCE, chameleon.getChars());
    return new HaxeParser().parse(entry, builder);
  }

  private static boolean containsErrors(@NotNull ASTNode node) {
    IElementType type = node.getElementType();
    if (type == TokenType.ERROR_ELEMENT || type == GeneratedParserUtilBase.DUMMY_BLOCK) {
      return true;
    }
    // nested chameleons (doc comments, metadata) stay collapsed: the candidate
    // tree is still DETACHED, and the platform forbids parsing a chameleon
    // outside a file tree - their contents carry no grade-relevant errors anyway
    if (type instanceof ILazyParseableElementType) {
      return false;
    }
    for (ASTNode child = node.getFirstChildNode(); child != null; child = child.getTreeNext()) {
      if (containsErrors(child)) {
        return true;
      }
    }
    return false;
  }

}
