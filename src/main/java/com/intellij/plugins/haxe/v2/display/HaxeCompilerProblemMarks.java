package com.intellij.plugins.haxe.v2.display;

import java.util.HashSet;
import java.util.Set;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Which files carry a compiler problem mark, decided from two kinds of
 * evidence that must not overrule each other. A file's OWN diagnostics say
 * whether it is broken, and only they clear that verdict: a file marked
 * from its own pass keeps the mark through sweeps that do not reach it.
 * The whole-project sweep says which OTHER files are broken; a file absent
 * from a sweep is clean or unreached, and either way its sweep mark goes.
 * Paths are the compiler's, compared as given.
 */
final class HaxeCompilerProblemMarks {

  /** The marks to add and to remove after one update. */
  record Update(@NotNull Set<String> report, @NotNull Set<String> clear) {
  }

  private final Set<String> markedByOwnPass = new HashSet<>();
  private final Set<String> markedBySweep = new HashSet<>();

  /**
   * Applies one pass: the edited file's own verdict, and the sweep's broken
   * files when the sweep answered (null leaves the sweep marks as they are).
   */
  @NotNull
  synchronized Update apply(@NotNull String editedPath, boolean editedBroken, @Nullable Set<String> sweepBroken) {
    Set<String> before = marked();
    if (editedBroken) {
      markedByOwnPass.add(editedPath);
    } else {
      markedByOwnPass.remove(editedPath);
    }
    if (sweepBroken != null) {
      markedBySweep.clear();
      markedBySweep.addAll(sweepBroken);
      markedBySweep.remove(editedPath);
    }
    Set<String> after = marked();
    Set<String> clear = new HashSet<>(before);
    clear.removeAll(after);
    return new Update(after, clear);
  }

  @NotNull
  private Set<String> marked() {
    Set<String> all = new HashSet<>(markedByOwnPass);
    all.addAll(markedBySweep);
    return all;
  }
}
