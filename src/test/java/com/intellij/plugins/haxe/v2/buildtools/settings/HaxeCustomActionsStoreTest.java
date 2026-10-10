package com.intellij.plugins.haxe.v2.buildtools.settings;

import com.intellij.plugins.haxe.v2.buildtools.settings.HaxeCustomActionsStore.CustomAction;
import com.intellij.plugins.haxe.v2.buildtools.settings.HaxeCustomActionsStore.Launch;
import com.intellij.util.xmlb.XmlSerializer;
import org.jdom.Element;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Tool window: custom actions store")
public class HaxeCustomActionsStoreTest {

  private static final String FILE = "/p/build.hxml";

  @Test
  @DisplayName("defaults are empty")
  public void defaultsAreEmpty() {
    HaxeCustomActionsStore store = new HaxeCustomActionsStore();
    assertTrue(store.getActions(FILE).isEmpty());
  }

  @Test
  @DisplayName("actions keep creation order and same name replaces")
  public void actionsKeepCreationOrderAndSameNameReplaces() {
    HaxeCustomActionsStore store = new HaxeCustomActionsStore();
    store.addAction(FILE, new CustomAction("docs", "haxe doc.hxml", ""));
    store.addAction(FILE, new CustomAction("serve", "nekotools server", ""));
    store.addAction(FILE, new CustomAction("docs", "haxe docs2.hxml", ""));

    assertEquals(List.of(new CustomAction("serve", "nekotools server", ""),
                         new CustomAction("docs", "haxe docs2.hxml", "")),
                 store.getActions(FILE));
  }

  @Test
  @DisplayName("update keeps position and rename works")
  public void updateKeepsPositionAndRenameWorks() {
    HaxeCustomActionsStore store = new HaxeCustomActionsStore();
    store.addAction(FILE, new CustomAction("a", "cmd-a", ""));
    store.addAction(FILE, new CustomAction("b", "cmd-b", ""));

    store.updateAction(FILE, "a", new CustomAction("a2", "cmd-a2", ""));

    assertEquals(List.of(new CustomAction("a2", "cmd-a2", ""), new CustomAction("b", "cmd-b", "")),
                 store.getActions(FILE));
  }

  @Test
  @DisplayName("remove deletes by name")
  public void removeDeletesByName() {
    HaxeCustomActionsStore store = new HaxeCustomActionsStore();
    store.addAction(FILE, new CustomAction("a", "cmd-a", ""));
    store.removeAction(FILE, "a");
    assertTrue(store.getActions(FILE).isEmpty());
  }

  @Test
  @DisplayName("state survives xml serialization round trip")
  public void stateSurvivesXmlSerializationRoundTrip() {
    HaxeCustomActionsStore store = new HaxeCustomActionsStore();
    store.addAction(FILE, new CustomAction("docs", "haxe doc.hxml", "${moduleRoot}/docs"));

    Element serialized = XmlSerializer.serialize(store.getState());
    HaxeCustomActionsStore.State deserialized = XmlSerializer.deserialize(serialized, HaxeCustomActionsStore.State.class);

    HaxeCustomActionsStore reloaded = new HaxeCustomActionsStore();
    reloaded.loadState(deserialized);
    assertEquals(List.of(new CustomAction("docs", "haxe doc.hxml", "${moduleRoot}/docs")), reloaded.getActions(FILE));
  }

  @Test
  @DisplayName("launching actions survive the xml round trip, created and existing alike")
  public void aLaunchingActionSurvivesTheXmlRoundTripWithItsKindParametersAndDebugFlag() {
    HaxeCustomActionsStore store = new HaxeCustomActionsStore();
    Launch launch = new Launch.Create("HASHLINK", Map.of("hlFile", "dist/final.hl"), false);
    store.addAction(FILE, new CustomAction("final", "haxe final.hxml", "", launch));
    store.addAction(FILE, new CustomAction("deploy", "", "", new Launch.Existing("Deploy (Gradle)", true)));
    store.addAction(FILE, new CustomAction("docs", "haxe doc.hxml", ""));

    Element serialized = XmlSerializer.serialize(store.getState());
    HaxeCustomActionsStore reloaded = new HaxeCustomActionsStore();
    reloaded.loadState(XmlSerializer.deserialize(serialized, HaxeCustomActionsStore.State.class));

    assertEquals(List.of(new CustomAction("final", "haxe final.hxml", "", launch),
                         new CustomAction("deploy", "", "", new Launch.Existing("Deploy (Gradle)", true)),
                         new CustomAction("docs", "haxe doc.hxml", "", null)),
                 reloaded.getActions(FILE));
  }

  @Test
  @DisplayName("run before chains expand depth first and run every action once")
  public void runBeforeChainsExpandDepthFirstAndRunEveryActionOnce() {
    HaxeCustomActionsStore store = new HaxeCustomActionsStore();
    store.addAction(FILE, new CustomAction("prepare", "cmd-prepare", "", null, List.of("Clean")));
    store.addAction(FILE, new CustomAction("release", "cmd-release", "", null, List.of("prepare", "Clean")));

    assertEquals(List.of("Clean", "prepare"), store.runBeforeChain(FILE, "release"));
    assertEquals(List.of("Clean"), store.runBeforeChain(FILE, "prepare"));
    assertEquals(List.of(), store.runBeforeChain(FILE, "Build"), "a built-in runs nothing first");
  }

  @Test
  @DisplayName("a loop in the run before references has no chain")
  public void aLoopInTheRunBeforeReferencesHasNoChain() {
    HaxeCustomActionsStore store = new HaxeCustomActionsStore();
    store.addAction(FILE, new CustomAction("a", "cmd-a", "", null, List.of("b")));
    store.addAction(FILE, new CustomAction("b", "cmd-b", "", null, List.of("a")));

    assertNull(store.runBeforeChain(FILE, "a"));
    assertTrue(store.runsBefore(FILE, "a", "anything"), "a looping action is never offered");
  }

  @Test
  @DisplayName("references follow a rename and go with a removal")
  public void referencesFollowARenameAndGoWithARemoval() {
    HaxeCustomActionsStore store = new HaxeCustomActionsStore();
    store.addAction(FILE, new CustomAction("prepare", "cmd-prepare", ""));
    store.addAction(FILE, new CustomAction("release", "cmd-release", "", null, List.of("Clean", "prepare")));

    store.renameReferences(FILE, "prepare", "setup");
    assertEquals(List.of("Clean", "setup"), store.runBeforeChain(FILE, "release"));
    assertEquals(List.of("release"), store.dependents(FILE, "setup"));

    store.dropReferences(FILE, "setup");
    assertEquals(List.of("Clean"), store.runBeforeChain(FILE, "release"));
    assertTrue(store.dependents(FILE, "setup").isEmpty());
  }

  @Test
  @DisplayName("run before survives the xml round trip")
  public void runBeforeSurvivesTheXmlRoundTrip() {
    HaxeCustomActionsStore store = new HaxeCustomActionsStore();
    store.addAction(FILE, new CustomAction("release", "cmd-release", "", null, List.of("Clean", "prepare")));

    Element serialized = XmlSerializer.serialize(store.getState());
    HaxeCustomActionsStore reloaded = new HaxeCustomActionsStore();
    reloaded.loadState(XmlSerializer.deserialize(serialized, HaxeCustomActionsStore.State.class));

    assertEquals(List.of(new CustomAction("release", "cmd-release", "", null, List.of("Clean", "prepare"))),
                 reloaded.getActions(FILE));
  }

  @Test
  @SuppressWarnings({"unchecked", "rawtypes"})
  @DisplayName("foreign entries from format changes are dropped")
  public void foreignEntriesFromFormatChangesAreDropped() {
    HaxeCustomActionsStore.ContainerActions container = new HaxeCustomActionsStore.ContainerActions();
    container.ownerId = FILE;
    ((List)container.actions).add("legacy-entry");
    HaxeCustomActionsStore.State state = new HaxeCustomActionsStore.State();
    state.containers.add(container);

    HaxeCustomActionsStore store = new HaxeCustomActionsStore();
    store.loadState(state);
    assertTrue(store.getActions(FILE).isEmpty());
  }
}
