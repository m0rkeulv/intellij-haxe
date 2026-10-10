package com.intellij.plugins.haxe.v2.buildtools.settings;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

/**
 * User-defined actions per build file (keyed by its path): a name plus a command
 * line executed in the build file's work directory
 * ({@link com.intellij.plugins.haxe.v2.buildtools.HaxeBuildWorkDirectories}),
 * optionally followed by starting a run configuration. Shares
 * {@code .idea/haxeBuildConfig.xml} with the other
 * build-config stores.
 */
@Service(Service.Level.PROJECT)
@State(name = "HaxeCustomActions", storages = @Storage("haxeBuildConfig.xml"))
public final class HaxeCustomActionsStore implements PersistentStateComponent<HaxeCustomActionsStore.State> {

  /**
   * The run configuration a launching action starts: a Haxe configuration
   * created for the action, which runs the build's output, or an existing
   * configuration of any type. Either way the action's command, when it has
   * one, is the configuration's build step; {@code injectDebugArguments} says
   * whether a Debug launch adds the debug compile arguments to that step.
   */
  public sealed interface Launch permits Launch.Create, Launch.Existing {
    boolean injectDebugArguments();

    /**
     * A configuration created for the action: {@code kindId} is a
     * {@code HaxeLaunchKind} id, {@code parameters} the field overrides by
     * parameter key; a blank or missing value keeps the default derived from
     * the build file.
     */
    record Create(@NotNull String kindId, @NotNull Map<String, String> parameters, boolean injectDebugArguments) implements Launch {
    }

    /** An existing configuration, by its name in the run dropdown. */
    record Existing(@NotNull String configurationName, boolean injectDebugArguments) implements Launch {
    }
  }

  /**
   * {@code workDirectory} may carry root variables; blank means the build file's
   * directory. A null {@code launch} is a plain action: its command runs in a
   * console. {@code runBefore} names the file's other actions that run first,
   * in order.
   */
  public record CustomAction(@NotNull String name, @NotNull String command, @NotNull String workDirectory,
                             @Nullable Launch launch, @NotNull List<String> runBefore) {
    public CustomAction {
      runBefore = List.copyOf(runBefore);
    }

    public CustomAction(@NotNull String name, @NotNull String command, @NotNull String workDirectory, @Nullable Launch launch) {
      this(name, command, workDirectory, launch, List.of());
    }

    public CustomAction(@NotNull String name, @NotNull String command, @NotNull String workDirectory) {
      this(name, command, workDirectory, null);
    }
  }

  public static final class State {
    public List<ContainerActions> containers = new ArrayList<>();
  }

  public static final class ContainerActions {
    public String ownerId;
    public List<ActionState> actions = new ArrayList<>();
  }

  public static final class ActionState {
    public String name;
    public String command = "";
    public String workDirectory = "";
    public String launchKind = "";
    public List<LaunchParameterState> launchParameters = new ArrayList<>();
    public String runConfiguration = "";
    public boolean injectDebugArguments = true;
    public List<String> runBefore = new ArrayList<>();
  }

  public static final class LaunchParameterState {
    public String key;
    public String value = "";
  }

  private State state = new State();

  @NotNull
  public static HaxeCustomActionsStore getInstance(@NotNull Project project) {
    return project.getService(HaxeCustomActionsStore.class);
  }

  @Override
  public @NotNull State getState() {
    return state;
  }

  @Override
  public void loadState(@NotNull State state) {
    if (state.containers == null) {
      state.containers = new ArrayList<>();
    }
    state.containers.forEach(HaxeCustomActionsStore::sanitizeActions);
    this.state = state;
  }

  // Stored lists can carry foreign objects after a format change (type erasure) - drop them.
  private static void sanitizeActions(@NotNull ContainerActions container) {
    if (container.actions == null) {
      container.actions = new ArrayList<>();
      return;
    }
    List<ActionState> cleaned = new ArrayList<>();
    for (Object entry : (List<?>)container.actions) {
      if (entry instanceof ActionState actionState && !StringUtil.isEmptyOrSpaces(actionState.name)) {
        if (actionState.launchParameters == null) {
          actionState.launchParameters = new ArrayList<>();
        }
        if (actionState.runBefore == null) {
          actionState.runBefore = new ArrayList<>();
        }
        cleaned.add(actionState);
      }
    }
    container.actions = cleaned;
  }

  /** The build file's custom actions in creation order. */
  @NotNull
  public List<CustomAction> getActions(@NotNull String ownerId) {
    ContainerActions container = find(ownerId);
    if (container == null) return List.of();
    return container.actions.stream()
      .filter(action -> !StringUtil.isEmptyOrSpaces(action.name))
      .map(HaxeCustomActionsStore::toAction)
      .toList();
  }

  public void addAction(@NotNull String ownerId, @NotNull CustomAction action) {
    ContainerActions container = getOrCreate(ownerId);
    container.actions.removeIf(existing -> action.name().equals(existing.name));
    container.actions.add(toState(action));
  }

  /** Replaces the action named {@code oldName}, keeping its position. */
  public void updateAction(@NotNull String ownerId, @NotNull String oldName, @NotNull CustomAction action) {
    ContainerActions container = getOrCreate(ownerId);
    for (int i = 0; i < container.actions.size(); i++) {
      if (oldName.equals(container.actions.get(i).name)) {
        container.actions.set(i, toState(action));
        return;
      }
    }
    container.actions.add(toState(action));
  }

  public void removeAction(@NotNull String ownerId, @NotNull String name) {
    ContainerActions container = find(ownerId);
    if (container != null) {
      container.actions.removeIf(action -> name.equals(action.name));
    }
  }

  /** The build file's custom actions that run {@code name} before themselves. */
  @NotNull
  public List<String> dependents(@NotNull String ownerId, @NotNull String name) {
    return getActions(ownerId).stream()
      .filter(action -> action.runBefore().contains(name))
      .map(CustomAction::name)
      .toList();
  }

  /** Points every run-before reference to {@code oldName} at {@code newName}. */
  public void renameReferences(@NotNull String ownerId, @NotNull String oldName, @NotNull String newName) {
    ContainerActions container = find(ownerId);
    if (container == null) return;
    for (ActionState action : container.actions) {
      action.runBefore.replaceAll(name -> oldName.equals(name) ? newName : name);
    }
  }

  /** Drops every run-before reference to {@code name}. */
  public void dropReferences(@NotNull String ownerId, @NotNull String name) {
    ContainerActions container = find(ownerId);
    if (container == null) return;
    for (ActionState action : container.actions) {
      action.runBefore.removeIf(name::equals);
    }
  }

  /**
   * The actions that run before {@code actionName}, in order: each custom
   * action's run-before list expanded depth-first, every action at most once.
   * Built-in actions run nothing before themselves. Null when the references
   * loop back to an action already on the way.
   */
  @Nullable
  public List<String> runBeforeChain(@NotNull String ownerId, @NotNull String actionName) {
    Map<String, List<String>> references = new HashMap<>();
    getActions(ownerId).forEach(action -> references.put(action.name(), action.runBefore()));
    List<String> order = new ArrayList<>();
    if (!collectRunBefore(actionName, references, new HashSet<>(), order)) return null;
    return List.copyOf(order.subList(0, order.size() - 1));
  }

  /** Whether {@code action} runs {@code target} somewhere before itself, or its references loop. */
  public boolean runsBefore(@NotNull String ownerId, @NotNull String action, @NotNull String target) {
    List<String> chain = runBeforeChain(ownerId, action);
    return chain == null || chain.contains(target);
  }

  /** Adds {@code name}'s run-before actions, then {@code name}; false when a reference loops back onto {@code onPath}. */
  private static boolean collectRunBefore(@NotNull String name,
                                          @NotNull Map<String, List<String>> references,
                                          @NotNull Set<String> onPath,
                                          @NotNull List<String> order) {
    if (onPath.contains(name)) return false;
    if (order.contains(name)) return true;
    onPath.add(name);
    for (String before : references.getOrDefault(name, List.of())) {
      if (!collectRunBefore(before, references, onPath, order)) return false;
    }
    onPath.remove(name);
    order.add(name);
    return true;
  }

  @NotNull
  private static CustomAction toAction(@NotNull ActionState actionState) {
    String command = StringUtil.notNullize(actionState.command);
    String workDirectory = StringUtil.notNullize(actionState.workDirectory);
    return new CustomAction(actionState.name, command, workDirectory, toLaunch(actionState), runBeforeOf(actionState));
  }

  @NotNull
  private static List<String> runBeforeOf(@NotNull ActionState actionState) {
    List<String> names = new ArrayList<>();
    for (Object entry : (List<?>)actionState.runBefore) {
      if (entry instanceof String name && !StringUtil.isEmptyOrSpaces(name)) {
        names.add(name);
      }
    }
    return names;
  }

  @Nullable
  private static Launch toLaunch(@NotNull ActionState actionState) {
    if (!StringUtil.isEmptyOrSpaces(actionState.runConfiguration)) {
      return new Launch.Existing(actionState.runConfiguration, actionState.injectDebugArguments);
    }
    if (StringUtil.isEmptyOrSpaces(actionState.launchKind)) return null;
    Map<String, String> parameters = new LinkedHashMap<>();
    for (Object entry : (List<?>)actionState.launchParameters) {
      if (entry instanceof LaunchParameterState parameter && !StringUtil.isEmptyOrSpaces(parameter.key)) {
        parameters.put(parameter.key, StringUtil.notNullize(parameter.value));
      }
    }
    return new Launch.Create(actionState.launchKind, Map.copyOf(parameters), actionState.injectDebugArguments);
  }

  @NotNull
  private static ActionState toState(@NotNull CustomAction action) {
    ActionState actionState = new ActionState();
    actionState.name = action.name();
    actionState.command = action.command();
    actionState.workDirectory = action.workDirectory();
    actionState.runBefore = new ArrayList<>(action.runBefore());
    switch (action.launch()) {
      case Launch.Create create -> {
        actionState.launchKind = create.kindId();
        create.parameters().forEach((key, value) -> actionState.launchParameters.add(parameterState(key, value)));
        actionState.injectDebugArguments = create.injectDebugArguments();
      }
      case Launch.Existing existing -> {
        actionState.runConfiguration = existing.configurationName();
        actionState.injectDebugArguments = existing.injectDebugArguments();
      }
      case null -> { }
    }
    return actionState;
  }

  @NotNull
  private static LaunchParameterState parameterState(@NotNull String key, @NotNull String value) {
    LaunchParameterState parameter = new LaunchParameterState();
    parameter.key = key;
    parameter.value = value;
    return parameter;
  }

  @Nullable
  private ContainerActions find(@NotNull String ownerId) {
    return state.containers.stream()
      .filter(container -> ownerId.equals(container.ownerId))
      .findFirst()
      .orElse(null);
  }

  @NotNull
  private ContainerActions getOrCreate(@NotNull String ownerId) {
    ContainerActions container = find(ownerId);
    if (container == null) {
      container = new ContainerActions();
      container.ownerId = ownerId;
      state.containers.add(container);
    }
    return container;
  }
}
