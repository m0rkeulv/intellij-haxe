package com.intellij.plugins.haxe.ide.annotator.semantics;

import com.intellij.lang.ASTNode;
import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.lang.annotation.Annotator;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.project.DumbAware;
import com.intellij.plugins.haxe.HaxeBundle;
import com.intellij.plugins.haxe.ide.annotator.HaxeStandardAnnotation;
import com.intellij.plugins.haxe.lang.lexer.HaxeTokenTypes;
import com.intellij.plugins.haxe.lang.psi.*;
import com.intellij.plugins.haxe.model.HaxeDocumentModel;
import com.intellij.plugins.haxe.model.fixer.HaxeFixer;
import com.intellij.plugins.haxe.v2.compiler.HaxeLanguageLevel;
import com.intellij.plugins.haxe.v2.compiler.HaxeLanguageLevelUtil;
import com.intellij.psi.PsiElement;
import com.intellij.psi.impl.source.tree.LeafPsiElement;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

/**
 * Flags language constructs the module's language level does not support:
 * newer syntax below its introduction level, and constructs removed at the
 * level in use. The feature/level pairs come from doc/haxe-language-levels.md;
 * the grammar accepts the superset, so these are post-parse checks.
 */
public class HaxeLanguageFeatureAnnotator implements Annotator, DumbAware {

  @Override
  public void annotate(@NotNull PsiElement element, @NotNull AnnotationHolder holder) {
    if (AnnotatorUtil.shouldSkip(element)) return;

    if (element instanceof LeafPsiElement leaf) {
      IElementType type = leaf.getElementType();
      if (type == HaxeTokenTypes.OQUEST_DOT) {
        requireLevel(holder, element, HaxeLanguageLevel.HAXE_4_3, "haxe.feature.safe.navigation");
      }
      else if (type == HaxeTokenTypes.KFINAL) {
        // every final position (fields, locals, classes, methods) is 4.0+;
        // 3.4 only had the @:final metadata, which is META text, not this token
        requireLevel(holder, element, HaxeLanguageLevel.HAXE_4_0, "haxe.feature.final.keyword");
      }
      else if (type == HaxeTokenTypes.OBIT_AND && isIntersectionTypeContext(element)) {
        requireLevel(holder, element, HaxeLanguageLevel.HAXE_4_0, "haxe.feature.intersection.types");
      }
      else if (type == HaxeTokenTypes.LITBIN && !HaxeLanguageLevelUtil.isAtLeast(element, HaxeLanguageLevel.HAXE_5_0)) {
        // WARNING, not error: the plugin's lexer has always accepted 0b
        // literals, but the compiler only gained them in Haxe 5
        HaxeStandardAnnotation.requiresLanguageLevel(holder, element, HaxeLanguageLevel.HAXE_5_0,
                                                     HaxeBundle.message("haxe.feature.binary.literals"),
                                                     HighlightSeverity.WARNING)
          .create();
      }
      return;
    }

    switch (element) {
      case HaxeCoalescingAssignOperator operator ->
        requireLevel(holder, operator, HaxeLanguageLevel.HAXE_4_3, "haxe.feature.null.coalescing");
      // before HaxeLocalVarDeclaration: capture vars are a subtype of it
      case HaxeSwitchCaseCaptureVar captureVar ->
        requireLevel(holder, captureVar, HaxeLanguageLevel.HAXE_4_0, "haxe.feature.case.var.patterns");
      case HaxeLocalVarDeclarationList list ->
        requireTokenLevel(holder, list, HaxeTokenTypes.KSTATIC, HaxeLanguageLevel.HAXE_4_3, "haxe.feature.local.static.variables");
      case HaxeLocalVarDeclaration declaration ->
        requireTokenLevel(holder, declaration, HaxeTokenTypes.KSTATIC, HaxeLanguageLevel.HAXE_4_3, "haxe.feature.local.static.variables");
      case HaxeRestParameter rest ->
        requireLevel(holder, rest, HaxeLanguageLevel.HAXE_4_2, "haxe.feature.rest.arguments");
      case HaxeModuleFieldDeclaration field ->
        requireLevel(holder, nameOrSelf(field), HaxeLanguageLevel.HAXE_4_2, "haxe.feature.module.fields");
      case HaxeModuleMethodDeclaration method ->
        requireLevel(holder, nameOrSelf(method), HaxeLanguageLevel.HAXE_4_2, "haxe.feature.module.fields");
      case HaxeKeyValueIterator iterator ->
        requireLevel(holder, iterator, HaxeLanguageLevel.HAXE_4_0, "haxe.feature.key.value.iterators");
      case HaxeAbstractTypeDeclaration declaration -> {
        // the enum/abstract keywords live under the ABSTRACT_CLASS_TYPE child
        HaxeAbstractClassType classType = PsiTreeUtil.getChildOfType(declaration, HaxeAbstractClassType.class);
        if (classType != null) {
          requireTokenLevel(holder, classType, HaxeTokenTypes.KENUM, HaxeLanguageLevel.HAXE_4_0, "haxe.feature.enum.abstract");
        }
      }
      case HaxeFunctionLiteral literal -> checkArrowFunction(literal, holder);
      case HaxePropertyAccessor accessor -> checkPropertyAccessor(accessor, holder);
      case HaxeCatchStatement catchStatement -> checkUntypedCatch(catchStatement, holder);
      case HaxeComponentName componentName -> checkReservedIdentifier(componentName, holder);
      case HaxeReferenceExpression reference -> checkDeprecatedStdMember(reference, holder);
      default -> { }
    }
  }

  /** The `catch (e)` shorthand (implicit haxe.Exception) is 4.1+. */
  private static void checkUntypedCatch(@NotNull HaxeCatchStatement catchStatement, @NotNull AnnotationHolder holder) {
    HaxeParameter parameter = catchStatement.getParameter();
    if (parameter != null && parameter.getTypeTag() == null) {
      requireLevel(holder, parameter, HaxeLanguageLevel.HAXE_4_1, "haxe.feature.untyped.catch");
    }
  }

  /** `operator` and `overload` became reserved keywords in 4.0. */
  private static void checkReservedIdentifier(@NotNull HaxeComponentName componentName, @NotNull AnnotationHolder holder) {
    String name = componentName.getText();
    if (!"operator".equals(name) && !"overload".equals(name)) return;
    if (!HaxeLanguageLevelUtil.isAtLeast(componentName, HaxeLanguageLevel.HAXE_4_0)) return;
    HaxeStandardAnnotation.removedAtLanguageLevel(holder, componentName, HaxeLanguageLevel.HAXE_4_0,
                                                  HaxeBundle.message("haxe.feature.reserved.identifiers"))
      .create();
  }

  private record DeprecatedMember(@NotNull HaxeLanguageLevel since, @NotNull String replacement) {
  }

  // deliberately text-based: a WARNING for the two std calls whose spelling is
  // stable; a local named Std shadowing the toplevel class would false-positive,
  // which is not worth a resolve on every reference
  private static final Map<String, DeprecatedMember> DEPRECATED_STD_MEMBERS = Map.of(
    "Std.is", new DeprecatedMember(HaxeLanguageLevel.HAXE_4_1, "Std.isOfType"),
    "Std.instance", new DeprecatedMember(HaxeLanguageLevel.HAXE_4_0, "Std.downcast"));

  private static void checkDeprecatedStdMember(@NotNull HaxeReferenceExpression reference, @NotNull AnnotationHolder holder) {
    DeprecatedMember deprecated = DEPRECATED_STD_MEMBERS.get(reference.getText());
    if (deprecated == null) return;
    if (!HaxeLanguageLevelUtil.isAtLeast(reference, deprecated.since())) return;

    String message = HaxeBundle.message("haxe.semantic.deprecated.since.language.level",
                                        reference.getText(), deprecated.since().getPresentableText(),
                                        deprecated.replacement());
    holder.newAnnotation(HighlightSeverity.WARNING, message)
      .range(reference)
      .withFix(HaxeFixer.create(HaxeBundle.message("haxe.quickfix.replace.with", deprecated.replacement()),
                                () -> HaxeDocumentModel.fromElement(reference)
                                  .replaceElementText(reference, deprecated.replacement())))
      .create();
  }

  /** An {@code &} between types (constraint or structure intersection), as opposed to the bitwise operator. */
  private static boolean isIntersectionTypeContext(@NotNull PsiElement amp) {
    PsiElement parent = amp.getParent();
    return parent instanceof HaxeConstraintTypeList
           || parent instanceof HaxeAnonymousType
           || parent instanceof HaxeTypeOrAnonymous
           || parent instanceof HaxeTypeListPart;
  }

  /** Arrow form only - a keyword `function` literal is fine at every level. */
  private static void checkArrowFunction(@NotNull HaxeFunctionLiteral literal, @NotNull AnnotationHolder holder) {
    ASTNode node = literal.getNode();
    if (node.findChildByType(HaxeTokenTypes.KFUNCTION) != null) return;
    ASTNode arrow = node.findChildByType(HaxeTokenTypes.OARROW);
    if (arrow == null) return;
    requireLevel(holder, arrow.getPsi(), HaxeLanguageLevel.HAXE_4_0, "haxe.feature.arrow.functions");
  }

  /**
   * Accessors are keywords (get/set/null/default/never/dynamic) at 4.0+; an
   * accessor parsed as a reference expression is the removed pre-4.0
   * accessor-METHOD-name form. A `private` accessor modifier is 5.0+.
   */
  private static void checkPropertyAccessor(@NotNull HaxePropertyAccessor accessor, @NotNull AnnotationHolder holder) {
    requireTokenLevel(holder, accessor, HaxeTokenTypes.KPRIVATE, HaxeLanguageLevel.HAXE_5_0, "haxe.feature.private.accessors");

    HaxeReferenceExpression customName = accessor.getReferenceExpression();
    if (customName != null && HaxeLanguageLevelUtil.isAtLeast(accessor, HaxeLanguageLevel.HAXE_4_0)) {
      HaxeStandardAnnotation.removedAtLanguageLevel(holder, customName, HaxeLanguageLevel.HAXE_4_0,
                                                    HaxeBundle.message("haxe.feature.custom.accessor.names"))
        .create();
    }
  }

  private static void requireLevel(@NotNull AnnotationHolder holder,
                                   @NotNull PsiElement element,
                                   @NotNull HaxeLanguageLevel required,
                                   @NotNull String featureKey) {
    if (HaxeLanguageLevelUtil.isAtLeast(element, required)) return;
    HaxeStandardAnnotation.requiresLanguageLevel(holder, element, required, HaxeBundle.message(featureKey)).create();
  }

  /** Gates on a DIRECT child token, annotating just that token when present. */
  private static void requireTokenLevel(@NotNull AnnotationHolder holder,
                                        @NotNull PsiElement element,
                                        @NotNull IElementType tokenType,
                                        @NotNull HaxeLanguageLevel required,
                                        @NotNull String featureKey) {
    ASTNode token = element.getNode().findChildByType(tokenType);
    if (token == null) return;
    requireLevel(holder, token.getPsi(), required, featureKey);
  }

  @NotNull
  private static PsiElement nameOrSelf(@NotNull PsiElement declaration) {
    HaxeComponentName name = PsiTreeUtil.getChildOfType(declaration, HaxeComponentName.class);
    return name != null ? name : declaration;
  }
}
