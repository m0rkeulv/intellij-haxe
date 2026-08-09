package com.intellij.plugins.haxe.v2.buildtools;

import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The last compiler-request failure per container. A build context that fails
 * to compile (e.g. a define override making a library uncompilable) silently
 * disables every compiler-backed feature — this store makes the failure
 * visible: the display service records request outcomes, the tool window's
 * Compilation server row shows the failure.
 */
@Service(Service.Level.PROJECT)
public final class HaxeContextHealth {

  private final Project project;
  private final Map<String, String> failures = new ConcurrentHashMap<>();

  public HaxeContextHealth(@NotNull Project project) {
    this.project = project;
  }

  @NotNull
  public static HaxeContextHealth getInstance(@NotNull Project project) {
    return project.getService(HaxeContextHealth.class);
  }

  /** The container's last failed compiler request, or null while requests succeed. */
  @Nullable
  public String lastFailure(@NotNull String containerId) {
    return failures.get(containerId);
  }

  /** Current failures per container (a copy). */
  @NotNull
  public Map<String, String> snapshot() {
    return Map.copyOf(failures);
  }

  /** Drops the failures of every container the given server serves — a stopped server's failures describe a dead process. */
  public void clearForServer(@NotNull String serverId) {
    for (String containerId : List.copyOf(failures.keySet())) {
      String sdkName = HaxeToolPathResolver.effectiveSdkName(project, containerId);
      if (serverId.equals(HaxeToolPathResolver.resolveHaxeExecutable(project, sdkName))) {
        record(containerId, null);
      }
    }
  }

  /** Records a request outcome (null failure = success); repaints the tool window on state transitions. */
  public void record(@NotNull String containerId, @Nullable String failure) {
    boolean changed = failure != null
                      ? !failure.equals(failures.put(containerId, failure))
                      : failures.remove(containerId) != null;
    if (changed && !project.isDisposed()) {
      project.getMessageBus().syncPublisher(HaxeBuildConfigListener.TOPIC).buildConfigurationChanged();
    }
  }
}
