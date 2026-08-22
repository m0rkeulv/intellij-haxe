package com.intellij.plugins.haxe.ide.formatter.settings;

import com.fasterxml.jackson.databind.JsonNode;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.plugins.haxe.HaxeBundle;
import com.intellij.plugins.haxe.lang.psi.HaxeFile;
import com.intellij.psi.PsiFile;
import com.intellij.application.options.CodeStyle;
import com.intellij.psi.codeStyle.modifier.CodeStyleSettingsModifier;
import com.intellij.psi.codeStyle.modifier.CodeStyleStatusBarUIContributor;
import com.intellij.psi.codeStyle.modifier.TransientCodeStyleSettings;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Formats a file by the project's OWN hxformat.json (haxe-formatter config)
 * when one exists, the way EditorConfig support overrides settings per file:
 * the nearest config above the file wins (matching the CLI's upward search),
 * applied as the full hxformat defaults image plus the file's overrides onto
 * TRANSIENT settings - no scheme is created or changed. Opt-out per scheme
 * via {@link HaxeCodeStyleSettings#USE_PROJECT_HXFORMAT}.
 */
public class HaxeHxformatSettingsModifier implements CodeStyleSettingsModifier {

  @Override
  public boolean modifySettings(@NotNull TransientCodeStyleSettings settings, @NotNull PsiFile file) {
    if (!(file instanceof HaxeFile)) return false;
    if (!settings.getCustomSettings(HaxeCodeStyleSettings.class).USE_PROJECT_HXFORMAT) return false;
    VirtualFile virtualFile = file.getOriginalFile().getVirtualFile();
    if (virtualFile == null) return false;
    VirtualFile configFile = HaxeHxformatConfigCache.findConfig(file.getProject(), virtualFile);
    if (configFile == null) return false;

    HaxeHxformatConfigCache cache = HaxeHxformatConfigCache.getInstance(file.getProject());
    settings.addDependency(cache.tracker());
    JsonNode root = cache.parsed(configFile);
    if (root == null) return false;
    // their disableFormatting turns the formatter off for the folder; the
    // closest IDE equivalent is falling back to the plain scheme settings
    if (root.path("disableFormatting").asBoolean(false)) return false;

    HxformatCodeStyle.applyDefaults(settings);
    HxformatCodeStyle.applyJson(settings, root);
    return true;
  }

  @Override
  public boolean mayOverrideSettingsOf(@NotNull Project project) {
    return CodeStyle.getSettings(project).getCustomSettings(HaxeCodeStyleSettings.class).USE_PROJECT_HXFORMAT;
  }

  @Override
  public String getName() {
    return HaxeBundle.message("hxformat.modifier.name");
  }

  @Override
  public @Nullable CodeStyleStatusBarUIContributor getStatusBarUiContributor(@NotNull TransientCodeStyleSettings transientSettings) {
    PsiFile file = transientSettings.getPsiFile();
    if (file == null) return null;
    VirtualFile virtualFile = file.getVirtualFile();
    if (virtualFile == null) return null;
    VirtualFile configFile = HaxeHxformatConfigCache.findConfig(file.getProject(), virtualFile);
    return configFile == null ? null : new HaxeHxformatStatusBarContributor(configFile);
  }
}
