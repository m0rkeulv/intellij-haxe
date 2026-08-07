package com.intellij.plugins.haxe.ide.references;

import com.intellij.lang.ASTNode;
import com.intellij.openapi.util.TextRange;
import com.intellij.patterns.PlatformPatterns;
import com.intellij.plugins.haxe.lang.lexer.HaxeTokenTypes;
import com.intellij.plugins.haxe.lang.psi.HaxeStringLiteralExpression;
import com.intellij.plugins.haxe.lang.psi.impl.HaxeReferenceUtil;
import com.intellij.plugins.haxe.util.HaxeQnameResolveUtil;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiReference;
import com.intellij.psi.PsiReferenceContributor;
import com.intellij.psi.PsiReferenceProvider;
import com.intellij.psi.PsiReferenceRegistrar;
import com.intellij.psi.tree.IElementType;
import com.intellij.util.ProcessingContext;
import com.intellij.util.SmartList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Makes string literals navigable when their VALUE denotes something real:
 * a file path (absolute, project-relative or relative to the containing
 * file's directory) or a fully-qualified name. References are attached ONLY
 * when the target resolves — an unresolvable string stays plain prose with
 * no link styling and no ctrl-click. See doc/string-path-fqn-links.md.
 */
public class HaxeStringLiteralReferenceContributor extends PsiReferenceContributor {

  @Override
  public void registerReferenceProviders(@NotNull PsiReferenceRegistrar registrar) {
    registrar.registerReferenceProvider(
      PlatformPatterns.psiElement(HaxeStringLiteralExpression.class),
      new StringLiteralReferenceProvider());
  }

  private static final class StringLiteralReferenceProvider extends PsiReferenceProvider {

    // longer than any plausible path or qname - everything above is prose
    private static final int MAX_CANDIDATE_LENGTH = 300;

    // a bare file name with an extension ("build.hxml") - the shape that can
    // be a path without containing any separator
    private static final Pattern NAME_WITH_EXTENSION = Pattern.compile(".+\\.[A-Za-z0-9]{1,10}");

    @Override
    public PsiReference @NotNull [] getReferencesByElement(@NotNull PsiElement element, @NotNull ProcessingContext context) {
      HaxeStringLiteralExpression literal = (HaxeStringLiteralExpression)element;
      String value = constantValueOf(literal);
      if (value == null || value.length() < 3 || value.length() > MAX_CANDIDATE_LENGTH) {
        return PsiReference.EMPTY_ARRAY;
      }

      // the whole content between the quotes is the link
      TextRange range = new TextRange(1, literal.getTextLength() - 1);
      List<PsiReference> references = new SmartList<>();

      if (looksLikePath(value) && HaxeStringFilePathReference.resolveFile(literal, value) != null) {
        references.add(new HaxeStringFilePathReference(literal, range, value));
      }
      // require a dot: bare capitalized words ("Main", "Error") are everyday
      // prose; only a DOTTED name reads as an intentional qualified name
      boolean qnameShaped = value.indexOf('.') > 0 && HaxeReferenceUtil.textCanBeQname(value);
      if (qnameShaped && HaxeQnameResolveUtil.findClassOrMember(value, literal.getProject()) != null) {
        references.add(new HaxeStringQnameReference(literal, range, value));
      }
      return references.isEmpty() ? PsiReference.EMPTY_ARRAY : references.toArray(PsiReference.EMPTY_ARRAY);
    }

    private static boolean looksLikePath(@NotNull String value) {
      return value.indexOf('/') >= 0 || value.indexOf('\\') >= 0 || NAME_WITH_EXTENSION.matcher(value).matches();
    }

    /**
     * The literal's compile-time value, or null when it has none worth
     * linking: interpolation makes the value dynamic, and escape sequences
     * beyond quote/backslash/slash (newline, tab, unicode) never appear in
     * paths or qualified names.
     */
    @Nullable
    private static String constantValueOf(@NotNull HaxeStringLiteralExpression literal) {
      StringBuilder value = new StringBuilder(literal.getTextLength());
      for (ASTNode child = literal.getNode().getFirstChildNode(); child != null; child = child.getTreeNext()) {
        IElementType type = child.getElementType();
        if (type == HaxeTokenTypes.OPEN_QUOTE || type == HaxeTokenTypes.CLOSING_QUOTE) continue;
        if (type == HaxeTokenTypes.REGULAR_STRING_PART) {
          value.append(child.getText());
          continue;
        }
        if (type == HaxeTokenTypes.ESCAPED_STRING_PART) {
          String unescaped = unescapePathChar(child.getText());
          if (unescaped == null) return null;
          value.append(unescaped);
          continue;
        }
        // template entries, invalid escapes, anything unexpected
        return null;
      }
      return value.toString();
    }

    @Nullable
    private static String unescapePathChar(@NotNull String escape) {
      return switch (escape) {
        case "\\\\" -> "\\";
        case "\\/" -> "/";
        case "\\\"" -> "\"";
        case "\\'" -> "'";
        default -> null;
      };
    }
  }
}
