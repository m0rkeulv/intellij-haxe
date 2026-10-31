package com.intellij.plugins.haxe.v2.display;

import com.intellij.plugins.haxe.display.protocol.Location;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The compiler's go-to-declaration answers, so that a Ctrl-hover and the
 * click that follows it, or repeated hovers over one spot, cost a single
 * {@code display/definition}. An answer is keyed by display context, file
 * and offset, and stamped with the file's document stamp and the project's
 * PSI modification count: any edit retires it, since the targets' ranges
 * may have moved. Only the most recently used answers are kept.
 */
final class HaxeDefinitionCache {

  /** Where the request was made: display context, file and offset. */
  record Key(@NotNull String contextKey, @NotNull String filePath, int offset) {
  }

  /** The state an answer holds for: the asked file's document stamp and the project's PSI modification count. */
  record Stamps(long documentStamp, long psiModificationCount) {
  }

  private record Answer(@NotNull Stamps stamps, @NotNull List<Location> locations) {
  }

  static final int CAPACITY = 64;

  private final Map<Key, Answer> answers = new LinkedHashMap<>(CAPACITY, 0.75f, true) {
    @Override
    protected boolean removeEldestEntry(Map.Entry<Key, Answer> eldest) {
      return size() > CAPACITY;
    }
  };

  /** The answer for the position at these stamps, or null when there is none or it holds for other stamps. */
  @Nullable
  synchronized List<Location> get(@NotNull Key key, @NotNull Stamps stamps) {
    Answer answer = answers.get(key);
    return answer != null && answer.stamps().equals(stamps) ? answer.locations() : null;
  }

  synchronized void put(@NotNull Key key, @NotNull Stamps stamps, @NotNull List<Location> locations) {
    answers.put(key, new Answer(stamps, List.copyOf(locations)));
  }

  synchronized void clear() {
    answers.clear();
  }
}
