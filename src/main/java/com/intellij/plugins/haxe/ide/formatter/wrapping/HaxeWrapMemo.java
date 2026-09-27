package com.intellij.plugins.haxe.ide.formatter.wrapping;

import com.intellij.lang.ASTNode;
import com.intellij.openapi.util.Key;
import com.intellij.plugins.haxe.HaxeLanguage;
import com.intellij.plugins.haxe.ide.formatter.settings.HaxeCodeStyleSettings;
import com.intellij.plugins.haxe.ide.formatter.wrapping.HaxeOperatorChainRules.Kind;
import com.intellij.plugins.haxe.ide.formatter.wrapping.HaxeOperatorChainRules.Thresholds;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.codeStyle.CommonCodeStyleSettings;
import com.intellij.psi.util.PsiModificationTracker;
import org.jetbrains.annotations.NotNull;

import java.util.function.Supplier;

/**
 * Per-node memo of the wrap decisions the spacing processor asks for once
 * per block pair (an argument list's broken arguments, an operator chain's
 * split), stored on the node's PSI. An entry is valid while nothing the
 * decision reads has changed: the file's tree and text (the PSI
 * modification count and the file's modification stamp) and the settings
 * the wrapping rules read, compared by VALUE - a transient per-file
 * settings copy carries no identity to key on, and a scheme's fields change
 * without notice.
 */
final class HaxeWrapMemo {

  /** A memoized value with the inputs it was computed from. */
  record Entry<T>(Inputs inputs, T value) {
  }

  /** Everything a wrap decision reads besides the node's subtree: the file's state and the rules' settings. */
  record Inputs(long psiModificationCount, long fileStamp, int margin, int tabSize, int chainWrap, int arrayWrap,
                Thresholds additive, Thresholds logic, HaxeArrayLiteralRules.Thresholds array,
                int multiVarSplitWidth, int multiVarFillItemLength) {

    static Inputs of(@NotNull PsiFile file, @NotNull CommonCodeStyleSettings common, @NotNull HaxeCodeStyleSettings haxe) {
      long psiModificationCount = PsiModificationTracker.getInstance(file.getProject()).getModificationCount();
      int margin = common.getRootSettings().getRightMargin(HaxeLanguage.INSTANCE);
      return new Inputs(psiModificationCount, file.getModificationStamp(), margin, HaxeJoinedLine.tabSize(common),
                        common.METHOD_CALL_CHAIN_WRAP, common.ARRAY_INITIALIZER_WRAP,
                        Kind.ADDITIVE.thresholds(haxe), Kind.LOGIC.thresholds(haxe), HaxeArrayLiteralRules.thresholds(haxe),
                        haxe.MULTI_VAR_SPLIT_WIDTH, haxe.MULTI_VAR_FILL_ITEM_LENGTH);
    }
  }

  private HaxeWrapMemo() {
  }

  /** The node's memoized value under the key, recomputed when its inputs changed. */
  static <T> T cached(@NotNull ASTNode node, @NotNull Key<Entry<T>> key,
                      @NotNull CommonCodeStyleSettings common, @NotNull HaxeCodeStyleSettings haxe, @NotNull Supplier<T> compute) {
    PsiElement psi = node.getPsi();
    PsiFile file = psi.getContainingFile();
    if (file == null) return compute.get();
    Inputs inputs = Inputs.of(file, common, haxe);
    Entry<T> entry = psi.getUserData(key);
    if (entry != null && entry.inputs().equals(inputs)) return entry.value();
    T value = compute.get();
    psi.putUserData(key, new Entry<>(inputs, value));
    return value;
  }
}
