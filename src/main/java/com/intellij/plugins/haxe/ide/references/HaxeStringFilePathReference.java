package com.intellij.plugins.haxe.ide.references;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectUtil;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.plugins.haxe.lang.psi.HaxeStringLiteralExpression;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFileSystemItem;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiReferenceBase;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A string literal whose VALUE is a resolvable file path — navigable like a
 * URL in a string, but inside the IDE. Resolution tries three bases in
 * order: absolute (only when the text looks absolute), relative to the
 * project base dir, relative to the directory of the containing file. Soft:
 * an unresolvable path is prose, never an error — the contributor only
 * attaches this reference when {@link #resolveFile} succeeds.
 */
public class HaxeStringFilePathReference extends PsiReferenceBase<HaxeStringLiteralExpression> {

  private final String value;

  public HaxeStringFilePathReference(@NotNull HaxeStringLiteralExpression literal, @NotNull TextRange range, @NotNull String value) {
    super(literal, range, true);
    this.value = value;
  }

  @Override
  @Nullable
  public PsiElement resolve() {
    VirtualFile file = resolveFile(getElement(), value);
    if (file == null) return null;
    PsiManager psiManager = PsiManager.getInstance(getElement().getProject());
    PsiFileSystemItem item = file.isDirectory() ? psiManager.findDirectory(file) : psiManager.findFile(file);
    return item;
  }

  /** The file (or directory) the path denotes, or null — the three-base lookup. */
  @Nullable
  public static VirtualFile resolveFile(@NotNull PsiElement context, @NotNull String path) {
    String normalized = path.replace('\\', '/');
    if (normalized.isEmpty()) return null;

    if (looksAbsolute(normalized)) {
      return LocalFileSystem.getInstance().findFileByPath(normalized);
    }

    Project project = context.getProject();
    VirtualFile projectDir = ProjectUtil.guessProjectDir(project);
    if (projectDir != null) {
      VirtualFile fromProjectRoot = projectDir.findFileByRelativePath(normalized);
      if (fromProjectRoot != null) return fromProjectRoot;
    }

    VirtualFile containing = context.getContainingFile().getOriginalFile().getVirtualFile();
    VirtualFile parent = containing != null ? containing.getParent() : null;
    return parent != null ? parent.findFileByRelativePath(normalized) : null;
  }

  // a drive-letter prefix (C:/) or a leading slash - anything else is
  // treated as relative and never handed to the local file system root
  private static boolean looksAbsolute(@NotNull String normalized) {
    if (normalized.startsWith("/")) return true;
    return normalized.length() > 2
           && Character.isLetter(normalized.charAt(0))
           && normalized.charAt(1) == ':'
           && normalized.charAt(2) == '/';
  }
}
