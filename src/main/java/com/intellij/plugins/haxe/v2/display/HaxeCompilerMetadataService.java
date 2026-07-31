package com.intellij.plugins.haxe.v2.display;

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.plugins.haxe.display.protocol.DisplayMethods;
import com.intellij.plugins.haxe.display.protocol.MetadataEntry;
import com.intellij.plugins.haxe.display.transport.DisplayRequestException;
import com.intellij.plugins.haxe.v2.buildtools.HaxeCompilationServerManager;
import com.intellij.plugins.haxe.v2.compiler.settings.HaxeCompilerSettings;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import lombok.CustomLog;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The compiler's metadata registry ({@code display/metadata}): built-ins plus
 * anything libraries registered via {@code Compiler.registerCustomMetadata}.
 * Consumers: metadata completion (merged entries) and the unused-member
 * keep-alive policy (a registry-known meta may be consumed invisibly; an
 * unknown one is likely a typo).
 *
 * Cache-only under the read lock, hydrated once per server (the registry is a
 * property of the compiler binary, so it is keyed by server port).
 */
@Service(Service.Level.PROJECT)
@CustomLog
public final class HaxeCompilerMetadataService {

  private record Registry(int port, @NotNull List<MetadataEntry> entries, @NotNull Set<String> bareNames) {
  }

  private final Project project;
  private final AtomicBoolean hydrating = new AtomicBoolean();
  // per-module SDKs mean several servers (and so several registries) at once
  private final Map<Integer, Registry> registries = new ConcurrentHashMap<>();

  public HaxeCompilerMetadataService(@NotNull Project project) {
    this.project = project;
  }

  @NotNull
  public static HaxeCompilerMetadataService getInstance(@NotNull Project project) {
    return project.getService(HaxeCompilerMetadataService.class);
  }

  /**
   * Registry entries, or null while unavailable/not hydrated. Cache-only;
   * call in a read action.
   */
  @Nullable
  public List<MetadataEntry> entries(@NotNull VirtualFile contextFile) {
    Registry known = lookup(contextFile);
    return known != null ? known.entries() : null;
  }

  /** Registry names without their leading colon, or null while unavailable. */
  @Nullable
  public Set<String> knownBareNames(@NotNull VirtualFile contextFile) {
    Registry known = lookup(contextFile);
    return known != null ? known.bareNames() : null;
  }

  public void clearCache() {
    registries.clear();
  }

  @Nullable
  private Registry lookup(@NotNull VirtualFile contextFile) {
    if (!HaxeCompilerSettings.getInstance(project).isCompilerDiagnosticsEnabled()) return null;
    if (DumbService.isDumb(project)) return null;
    HaxeCompilerDisplayService.DisplayContext context = HaxeCompilerDisplayService.getInstance(project).contextFor(contextFile);
    if (context == null) return null;
    // the registry is a property of the compiler binary, so it lives and dies
    // with that SDK's server instance; another SDK's registry is never served
    int currentPort = HaxeCompilationServerManager.getInstance(project).getRunningPort(context.sdkName());
    Registry known = currentPort > 0 ? registries.get(currentPort) : null;
    if (known != null) {
      return known;
    }
    scheduleHydration(context);
    return null;
  }

  private void scheduleHydration(@NotNull HaxeCompilerDisplayService.DisplayContext context) {
    if (!hydrating.compareAndSet(false, true)) return;
    ApplicationManager.getApplication().executeOnPooledThread(() -> {
      try {
        HaxeCompilerDisplayService.Connected connected =
          HaxeCompilerDisplayService.getInstance(project).connectFor(context, DisplayMethods.METADATA);
        if (connected == null) return;
        List<MetadataEntry> entries = connected.client().metadata(connected.args());
        Set<String> bareNames = entries.stream()
          .map(MetadataEntry::bareName)
          .collect(Collectors.toUnmodifiableSet());
        registries.put(connected.port(), new Registry(connected.port(), entries, bareNames));
        log.info("haxe metadata registry loaded: " + entries.size() + " entries");
        restartHighlighting();
      } catch (DisplayRequestException e) {
        log.info("display/metadata failed: " + e.getMessage());
      } finally {
        hydrating.set(false);
      }
    });
  }

  private void restartHighlighting() {
    ApplicationManager.getApplication().invokeLater(() -> {
      if (!project.isDisposed()) {
        DaemonCodeAnalyzer.getInstance(project).restart();
      }
    });
  }
}
