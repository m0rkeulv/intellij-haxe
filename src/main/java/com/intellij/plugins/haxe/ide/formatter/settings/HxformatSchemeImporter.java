package com.intellij.plugins.haxe.ide.formatter.settings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.options.SchemeFactory;
import com.intellij.openapi.options.SchemeImportException;
import com.intellij.openapi.options.SchemeImporter;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.plugins.haxe.HaxeBundle;
import com.intellij.psi.codeStyle.CodeStyleScheme;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Imports a haxe-formatter (HaxeCheckstyle) hxformat.json as a code style
 * scheme: the hxformat DEFAULTS first, then the file's overrides. Config keys
 * we cannot honor are listed in the post-import message.
 */
public class HxformatSchemeImporter implements SchemeImporter<CodeStyleScheme> {

  private static final Logger LOG = Logger.getInstance(HxformatSchemeImporter.class);
  private static final int REPORTED_KEYS_LIMIT = 10;

  private List<String> lastUnsupported = List.of();

  @Override
  public String @NotNull [] getSourceExtensions() {
    return new String[]{"json"};
  }

  @Override
  public @Nullable CodeStyleScheme importScheme(@NotNull Project project,
                                               @NotNull VirtualFile selectedFile,
                                               @NotNull CodeStyleScheme currentScheme,
                                               @NotNull SchemeFactory<? extends CodeStyleScheme> schemeFactory) throws SchemeImportException {
    JsonNode root = readJson(selectedFile);
    CodeStyleScheme scheme = schemeFactory.createNewScheme(selectedFile.getNameWithoutExtension());
    HxformatCodeStyle.applyDefaults(scheme.getCodeStyleSettings());
    lastUnsupported = HxformatCodeStyle.applyJson(scheme.getCodeStyleSettings(), root);
    // the post-import balloon is short-lived - the log keeps the full list
    for (String key : lastUnsupported) {
      LOG.warn("hxformat.json import: unsupported setting " + key + " (from " + selectedFile.getPath() + ")");
    }
    return scheme;
  }

  @Override
  public @Nullable String getAdditionalImportInfo(@NotNull CodeStyleScheme scheme) {
    if (lastUnsupported.isEmpty()) {
      return HaxeBundle.message("hxformat.import.complete");
    }
    List<String> shown = lastUnsupported.subList(0, Math.min(REPORTED_KEYS_LIMIT, lastUnsupported.size()));
    String keys = String.join(", ", shown);
    if (lastUnsupported.size() > shown.size()) {
      keys += ", …";
    }
    return HaxeBundle.message("hxformat.import.partial", lastUnsupported.size(), keys);
  }

  @NotNull
  private static JsonNode readJson(@NotNull VirtualFile file) throws SchemeImportException {
    try {
      String text = new String(file.contentsToByteArray(), StandardCharsets.UTF_8);
      return new ObjectMapper().readTree(text);
    }
    catch (IOException e) {
      throw new SchemeImportException(HaxeBundle.message("hxformat.import.parse.error", e.getMessage()));
    }
  }
}
