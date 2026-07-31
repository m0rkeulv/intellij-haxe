package com.intellij.plugins.haxe.ide.annotator.semantics;

import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.lang.annotation.Annotator;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.plugins.haxe.HaxeBundle;
import com.intellij.plugins.haxe.ide.annotator.HaxeStandardAnnotation;
import com.intellij.plugins.haxe.lang.psi.HaxeCoalescingExpression;
import com.intellij.plugins.haxe.lang.psi.HaxeExpression;
import com.intellij.plugins.haxe.model.evaluator.HaxeExpressionEvaluator;
import com.intellij.plugins.haxe.model.type.ResultHolder;
import com.intellij.plugins.haxe.v2.compiler.HaxeLanguageLevel;
import com.intellij.plugins.haxe.v2.compiler.HaxeLanguageLevelUtil;
import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.NotNull;

import java.util.List;

import static com.intellij.plugins.haxe.ide.annotator.HaxeSemanticAnnotatorInspections.INCOMPATIBLE_INITIALIZATION;

public class HaxeNullCoalescingAnnotator implements Annotator {

    @Override
    public void annotate(@NotNull PsiElement element, @NotNull AnnotationHolder holder) {
        if(!element.isValid()) return;

        if (element instanceof HaxeCoalescingExpression coalescingExpression) {
            check(coalescingExpression, holder);
        }
    }

    public static void check(final HaxeCoalescingExpression coalescingExpression, final AnnotationHolder holder) {
        // ?? exists only at 4.3+ - below that the construct itself is the error,
        // and the type checks (which assume a valid operator) do not apply
        if (!HaxeLanguageLevelUtil.isAtLeast(coalescingExpression, HaxeLanguageLevel.HAXE_4_3)) {
            HaxeStandardAnnotation.requiresLanguageLevel(holder, coalescingExpression, HaxeLanguageLevel.HAXE_4_3,
                                                         HaxeBundle.message("haxe.feature.null.coalescing"))
              .create();
            return;
        }
        if (!INCOMPATIBLE_INITIALIZATION.isEnabled(coalescingExpression)) return;

        List<HaxeExpression> expressionList = coalescingExpression.getExpressionList();
        if (expressionList.size() == 2) {
            HaxeExpression left = expressionList.getFirst();
            HaxeExpression right = expressionList.getLast();

            ResultHolder leftType = HaxeExpressionEvaluator.evaluate(left).result;
            ResultHolder rightType = HaxeExpressionEvaluator.evaluate(right).result;

            if(leftType.isVoid()) {
                holder.newAnnotation(HighlightSeverity.ERROR,"Cannot use Void as value").range(left).create();;
                return;
            }
            if(rightType.isVoid()) {
                holder.newAnnotation(HighlightSeverity.ERROR,"Cannot use Void as value").range(right).create();;
                return;
            }

            if (!leftType.canAssign(rightType) && !rightType.canAssign(leftType)) {
                // the haxe compiler seems to always mark the right expression as incorrect so we do the same
                HaxeStandardAnnotation.typeMismatch(holder, right, leftType.toPresentationString(), rightType.toPresentationString()).create();
            }
        }
    }
}
