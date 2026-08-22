package com.intellij.plugins.haxe.ide.formatter.settings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.util.ModificationTracker;
import com.intellij.openapi.util.SimpleModificationTracker;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.openapi.vfs.newvfs.BulkFileListener;
import com.intellij.openapi.vfs.newvfs.events.VFileEvent;
import com.intellij.psi.codeStyle.CodeStyleSettingsManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Parsed hxformat.json configs for {@link HaxeHxformatSettingsModifier}. Any
 * VFS change to a file named hxformat.json bumps the tracker (invalidating
 * the per-file transient settings that depend on it), clears the parse cache
 * and re-triggers code style recalculation.
 */
@Service(Service.Level.PROJECT)
public final class HaxeHxformatConfigCache {

  public static final String HXFORMAT_FILE_NAME = "hxformat.json";
  private static final Logger LOG = Logger.getInstance(HaxeHxformatConfigCache.class);

  private final Map<String, CachedConfig> parsedByPath = new ConcurrentHashMap<>();
  private final SimpleModificationTracker tracker = new SimpleModificationTracker();

  public static HaxeHxformatConfigCache getInstance(@NotNull Project project) {
    return project.getService(HaxeHxformatConfigCache.class);
  }

  public HaxeHxformatConfigCache(@NotNull Project project) {
    project.getMessageBus().connect().subscribe(VirtualFileManager.VFS_CHANGES, new BulkFileListener() {
      @Override
      public void after(@NotNull List<? extends @NotNull VFileEvent> events) {
        for (VFileEvent event : events) {
          if (!event.getPath().endsWith(HXFORMAT_FILE_NAME)) continue;
          parsedByPath.clear();
          tracker.incModificationCount();
          CodeStyleSettingsManager.getInstance(project).notifyCodeStyleSettingsChanged();
          return;
        }
      }
    });
  }

  /** The nearest hxformat.json from the file's directory up to the content root. */
  @Nullable
  public static VirtualFile findConfig(@NotNull Project project, @NotNull VirtualFile file) {
    VirtualFile contentRoot = ProjectFileIndex.getInstance(project).getContentRootForFile(file);
    for (VirtualFile dir = file.getParent(); dir != null; dir = dir.getParent()) {
      VirtualFile config = dir.findChild(HXFORMAT_FILE_NAME);
      if (config != null && !config.isDirectory()) {
        return config;
      }
      if (dir.equals(contentRoot)) {
        return null;
      }
    }
    return null;
  }

  /** Bumped whenever any hxformat.json changes - the transient settings' dependency. */
  public ModificationTracker tracker() {
    return tracker;
  }

  /** The parsed config, or null when the file cannot be read or parsed. */
  @Nullable
  public JsonNode parsed(@NotNull VirtualFile configFile) {
    CachedConfig cached = parsedByPath.get(configFile.getPath());
    if (cached != null && cached.stamp == configFile.getModificationStamp()) {
      return cached.root;
    }
    JsonNode root = null;
    try {
      String text = new String(configFile.contentsToByteArray(), StandardCharsets.UTF_8);
      root = new ObjectMapper().readTree(text);
    }
    catch (Exception e) {
      LOG.warn("cannot parse " + configFile.getPath() + ": " + e.getMessage());
    }
    parsedByPath.put(configFile.getPath(), new CachedConfig(configFile.getModificationStamp(), root));
    return root;
  }

  private record CachedConfig(long stamp, @Nullable JsonNode root) {
  }
}
