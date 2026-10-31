package com.intellij.plugins.haxe.v2.display;

import com.intellij.plugins.haxe.display.protocol.Location;
import com.intellij.plugins.haxe.display.protocol.Position;
import com.intellij.plugins.haxe.display.protocol.Range;
import com.intellij.plugins.haxe.v2.display.HaxeDefinitionCache.Key;
import com.intellij.plugins.haxe.v2.display.HaxeDefinitionCache.Stamps;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

@DisplayName("Compiler services: definition cache")
public class HaxeDefinitionCacheTest {
  private static final Key KEY = new Key("context", "/src/Main.hx", 42);
  private static final Stamps STAMPS = new Stamps(7, 100);
  private static final List<Location> TARGET = List.of(new Location("file:///src/Foo.hx", new Range(new Position(0, 6), new Position(0, 9))));

  @Test
  @DisplayName("an answer is served at the stamps it was stored with")
  public void testAnAnswerIsServedAtTheStampsItWasStoredWith() {
    HaxeDefinitionCache cache = new HaxeDefinitionCache();

    cache.put(KEY, STAMPS, TARGET);
    Key elsewhere = new Key("context", "/src/Main.hx", 43);
    cache.put(elsewhere, STAMPS, List.of());

    assertEquals(TARGET, cache.get(KEY, STAMPS));
    assertEquals(List.of(), cache.get(elsewhere, STAMPS), "an empty answer is an answer too");
  }

  @Test
  @DisplayName("an edit of the file or of any psi retires the answer")
  public void testAnEditOfTheFileOrOfAnyPsiRetiresTheAnswer() {
    HaxeDefinitionCache cache = new HaxeDefinitionCache();
    cache.put(KEY, STAMPS, TARGET);

    assertNull(cache.get(KEY, new Stamps(8, 100)), "the file's document changed: offsets may have moved");
    assertNull(cache.get(KEY, new Stamps(7, 101)), "another file changed: the targets' ranges may have moved");
  }

  @Test
  @DisplayName("the cache keeps the most recently used answers")
  public void testTheCacheKeepsTheMostRecentlyUsedAnswers() {
    HaxeDefinitionCache cache = new HaxeDefinitionCache();
    for (int offset = 0; offset < HaxeDefinitionCache.CAPACITY; offset++) {
      cache.put(new Key("context", "/src/Main.hx", offset), STAMPS, TARGET);
    }
    Key oldest = new Key("context", "/src/Main.hx", 0);
    assertNotNull(cache.get(oldest, STAMPS), "touching the oldest answer makes it the most recent");

    cache.put(new Key("context", "/src/Main.hx", HaxeDefinitionCache.CAPACITY), STAMPS, TARGET);

    assertNotNull(cache.get(oldest, STAMPS), "the touched answer survives");
    assertNull(cache.get(new Key("context", "/src/Main.hx", 1), STAMPS), "the least recently used answer goes");
  }
}
