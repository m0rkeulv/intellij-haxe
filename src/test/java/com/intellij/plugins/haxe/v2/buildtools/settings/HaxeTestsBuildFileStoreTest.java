package com.intellij.plugins.haxe.v2.buildtools.settings;

import com.intellij.util.xmlb.XmlSerializer;
import org.jdom.Element;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Tool window: tests build file store")
public class HaxeTestsBuildFileStoreTest {

  private static final String MODULE = "app";
  private static final List<String> PLAIN_FILES = List.of("/p/build.hxml", "/p/project.xml");

  @Test
  @DisplayName("defaults are empty")
  public void defaultsAreEmpty() {
    HaxeTestsBuildFileStore store = new HaxeTestsBuildFileStore();
    assertNull(store.getTestsFilePath(MODULE));
  }

  @Test
  @DisplayName("tests file can be set and cleared")
  public void testsFileCanBeSetAndCleared() {
    HaxeTestsBuildFileStore store = new HaxeTestsBuildFileStore();
    store.setTestsFile(MODULE, "/p/tests.hxml");
    assertEquals("/p/tests.hxml", store.getTestsFilePath(MODULE));

    store.setTestsFile(MODULE, null);
    assertNull(store.getTestsFilePath(MODULE));
  }

  @Test
  @DisplayName("containers are independent")
  public void containersAreIndependent() {
    HaxeTestsBuildFileStore store = new HaxeTestsBuildFileStore();
    store.setTestsFile("app", "/app/test.hxml");
    store.setTestsFile("lib", "/lib/tests.hxml");

    assertEquals("/app/test.hxml", store.getTestsFilePath("app"));
    assertEquals("/lib/tests.hxml", store.getTestsFilePath("lib"));
  }

  @Test
  @DisplayName("stored choice wins when still present")
  public void storedChoiceWinsWhenStillPresent() {
    HaxeTestsBuildFileStore store = new HaxeTestsBuildFileStore();
    store.setTestsFile(MODULE, "/p/project.xml");
    assertEquals("/p/project.xml", store.resolveOrSuggest(MODULE, PLAIN_FILES));
  }

  @Test
  @DisplayName("stale stored path falls back to convention")
  public void staleStoredPathFallsBackToConvention() {
    HaxeTestsBuildFileStore store = new HaxeTestsBuildFileStore();
    store.setTestsFile(MODULE, "/p/deleted.hxml");
    assertNull(store.resolveOrSuggest(MODULE, PLAIN_FILES));
    assertEquals("/p/test.hxml", store.resolveOrSuggest(MODULE, List.of("/p/build.hxml", "/p/test.hxml")));
  }

  @Test
  @DisplayName("conventional file name is suggested")
  public void conventionalFileNameIsSuggested() {
    HaxeTestsBuildFileStore store = new HaxeTestsBuildFileStore();
    assertEquals("/p/test.hxml", store.resolveOrSuggest(MODULE, List.of("/p/build.hxml", "/p/test.hxml")));
    assertEquals("/p/tests.hxml", store.resolveOrSuggest(MODULE, List.of("/p/build.hxml", "/p/tests.hxml")));
  }

  @Test
  @DisplayName("conventional name beats tests directory")
  public void conventionalNameBeatsTestsDirectory() {
    List<String> candidates = List.of("/p/tests/compile-hl.hxml", "/p/test.hxml");
    HaxeTestsBuildFileStore store = new HaxeTestsBuildFileStore();
    assertEquals("/p/test.hxml", store.resolveOrSuggest(MODULE, candidates));
  }

  @Test
  @DisplayName("build file under tests directory is suggested")
  public void buildFileUnderTestsDirectoryIsSuggested() {
    List<String> candidates = List.of("/p/build.hxml", "/p/tests/project.xml");
    HaxeTestsBuildFileStore store = new HaxeTestsBuildFileStore();
    assertEquals("/p/tests/project.xml", store.resolveOrSuggest(MODULE, candidates));
  }

  @Test
  @DisplayName("no conventional candidate yields none")
  public void noConventionalCandidateYieldsNone() {
    HaxeTestsBuildFileStore store = new HaxeTestsBuildFileStore();
    assertNull(store.resolveOrSuggest(MODULE, PLAIN_FILES));
  }

  @Test
  @DisplayName("state survives xml serialization round trip")
  public void stateSurvivesXmlSerializationRoundTrip() {
    HaxeTestsBuildFileStore store = new HaxeTestsBuildFileStore();
    store.setTestsFile(MODULE, "/p/tests/compile-hl.hxml");

    Element serialized = XmlSerializer.serialize(store.getState());
    HaxeTestsBuildFileStore.State deserialized =
      XmlSerializer.deserialize(serialized, HaxeTestsBuildFileStore.State.class);

    HaxeTestsBuildFileStore reloaded = new HaxeTestsBuildFileStore();
    reloaded.loadState(deserialized);
    assertEquals("/p/tests/compile-hl.hxml", reloaded.getTestsFilePath(MODULE));
  }
}
