package com.intellij.plugins.haxe.ide.formatter.hxformat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectUtil;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.util.ModificationTracker;
import com.intellij.openapi.util.SimpleModificationTracker;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.openapi.vfs.newvfs.BulkFileListener;
import com.intellij.openapi.vfs.newvfs.events.VFileEvent;
import com.intellij.psi.codeStyle.CodeStyleSettingsManager;
import com.intellij.util.PathUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Parsed hxformat.json configs for {@link HxformatSettingsModifier}, plus
 * the user's explicitly chosen fallback config ("Use as Haxe Formatting
 * Rules"), persisted per project. Any VFS change to a file named
 * hxformat.json - and any override change - bumps the tracker (invalidating
 * the per-file transient settings that depend on it), clears the parse cache
 * and re-triggers code style recalculation.
 */
@Service(Service.Level.PROJECT)
@State(name = "HaxeHxformatConfig", storages = @Storage("haxeFormatter.xml"))
public final class HxformatConfigCache implements PersistentStateComponent<HxformatConfigCache.State> {

  public static final class State {
    public String overrideConfigUrl;
  }

  public static final String HXFORMAT_FILE_NAME = "hxformat.json";
  private static final Logger LOG = Logger.getInstance(HxformatConfigCache.class);
  // jackson mappers are thread-safe once configured; one per parse is waste
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final Map<String, CachedConfig> parsedByPath = new ConcurrentHashMap<>();
  private final SimpleModificationTracker tracker = new SimpleModificationTracker();
  private final Project project;
  private State state = new State();

  public static HxformatConfigCache getInstance(@NotNull Project project) {
    return project.getService(HxformatConfigCache.class);
  }

  @Override
  public @NotNull State getState() {
    return state;
  }

  @Override
  public void loadState(@NotNull State state) {
    this.state = state;
  }

  public HxformatConfigCache(@NotNull Project project) {
    this.project = project;
    project.getMessageBus().connect().subscribe(VirtualFileManager.VFS_CHANGES, new BulkFileListener() {
      @Override
      public void after(@NotNull List<? extends @NotNull VFileEvent> events) {
        for (VFileEvent event : events) {
          // match the file NAME - a path ending in "myhxformat.json" is unrelated
          if (!HXFORMAT_FILE_NAME.equals(PathUtil.getFileName(event.getPath()))) continue;
          parsedByPath.clear();
          tracker.incModificationCount();
          CodeStyleSettingsManager.getInstance(project).notifyCodeStyleSettingsChanged();
          return;
        }
      }
    });
  }

  /**
   * The nearest hxformat.json from the file's directory up to the project base
   * directory (the CLI's upward search, bounded to the project - a content
   * root is no boundary, so a root-level config reaches nested modules), else
   * the explicitly chosen fallback config, else null.
   */
  @Nullable
  public static VirtualFile findConfig(@NotNull Project project, @NotNull VirtualFile file) {
    VirtualFile projectDir = ProjectUtil.guessProjectDir(project);
    for (VirtualFile dir = file.getParent(); dir != null; dir = dir.getParent()) {
      VirtualFile config = dir.findChild(HXFORMAT_FILE_NAME);
      if (config != null && !config.isDirectory()) {
        return config;
      }
      if (dir.equals(projectDir)) {
        break;
      }
    }
    return getInstance(project).overrideConfig();
  }

  /** The explicitly chosen fallback config, or null when unset or gone from disk. */
  @Nullable
  public VirtualFile overrideConfig() {
    String url = state.overrideConfigUrl;
    if (StringUtil.isEmpty(url)) return null;
    // a VFS URL, so the config resolves in whatever filesystem holds it
    VirtualFile file = VirtualFileManager.getInstance().findFileByUrl(url);
    return file != null && file.isValid() && !file.isDirectory() ? file : null;
  }

  @Nullable
  public String overrideConfigUrl() {
    return state.overrideConfigUrl;
  }

  /** Sets (or clears, with null) the fallback config and re-triggers code style recalculation. */
  public void setOverrideConfigUrl(@Nullable String url) {
    state.overrideConfigUrl = url;
    parsedByPath.clear();
    tracker.incModificationCount();
    CodeStyleSettingsManager.getInstance(project).notifyCodeStyleSettingsChanged();
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
      root = readJsonTree(configFile);
    }
    catch (Exception e) {
      LOG.warn("cannot parse " + configFile.getPath() + ": " + e.getMessage());
    }
    parsedByPath.put(configFile.getPath(), new CachedConfig(configFile.getModificationStamp(), root));
    return root;
  }

  /** The file's JSON tree; loadText honors the file's detected charset/BOM. */
  @NotNull
  static JsonNode readJsonTree(@NotNull VirtualFile file) throws IOException {
    return MAPPER.readTree(VfsUtilCore.loadText(file));
  }

  private record CachedConfig(long stamp, @Nullable JsonNode root) {
  }
}
