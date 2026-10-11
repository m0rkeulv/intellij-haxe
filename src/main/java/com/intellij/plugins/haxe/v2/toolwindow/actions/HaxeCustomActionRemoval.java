package com.intellij.plugins.haxe.v2.toolwindow.actions;

import com.intellij.plugins.haxe.v2.buildtools.settings.HaxeCustomActionsStore;
import com.intellij.plugins.haxe.v2.buildtools.settings.HaxeCustomActionsStore.ActionReference;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * The removal of a custom action in two decisions: the action itself and,
 * only when other actions run it first, what becomes of their references -
 * dropped with it, or kept to show as missing until the user re-points them.
 */
final class HaxeCustomActionRemoval {

  /** What becomes of the run-before references other actions hold to the removed one. */
  enum References { REMOVE, KEEP, CANCEL }

  interface Decisions {
    boolean confirmRemoval(@NotNull String name);

    /** Asked only when {@code users} is non-empty. */
    @NotNull
    References references(@NotNull String name, @NotNull List<ActionReference> users);
  }

  private HaxeCustomActionRemoval() {
  }

  /** Removes the action as decided; false when the user backed out, and the store is untouched then. */
  static boolean remove(@NotNull HaxeCustomActionsStore store,
                        @NotNull String ownerId,
                        @NotNull String name,
                        @NotNull Decisions decisions) {
    if (!decisions.confirmRemoval(name)) return false;
    List<ActionReference> users = store.dependents(ownerId, name);
    References references = users.isEmpty() ? References.KEEP : decisions.references(name, users);
    if (references == References.CANCEL) return false;
    store.removeAction(ownerId, name);
    if (references == References.REMOVE) store.dropReferences(ownerId, name);
    return true;
  }
}
