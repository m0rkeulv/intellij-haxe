package com.intellij.plugins.haxe.ide.formatter;

import com.intellij.lang.ASTNode;
import com.intellij.plugins.haxe.ide.formatter.settings.HaxeCodeStyleSettings;
import com.intellij.plugins.haxe.lang.psi.impl.HaxeInactiveBody;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;

/**
 * Which inactive conditional branches the formatter leaves byte-identical:
 * every branch while formatting them is toggled off, and the token-soup
 * blobs that cannot parse either way. The block formatter, the branch
 * aligner and the comment passes all keep their hands off such a branch.
 */
public final class HaxeInactiveBranches {

  private HaxeInactiveBranches() {
  }

  public static boolean preservedVerbatim(@NotNull HaxeInactiveBody body, @NotNull HaxeCodeStyleSettings settings) {
    return !settings.FORMAT_INACTIVE_BRANCHES || !body.hasCleanParse();
  }

  /** The node lies inside an inactive branch that stays as written. */
  public static boolean insidePreservedBranch(@NotNull ASTNode node, @NotNull HaxeCodeStyleSettings settings) {
    HaxeInactiveBody body = PsiTreeUtil.getParentOfType(node.getPsi(), HaxeInactiveBody.class);
    return body != null && preservedVerbatim(body, settings);
  }
}
