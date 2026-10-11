package com.intellij.plugins.haxe.v2.buildtools.context;

import com.intellij.openapi.roots.ModuleRootModificationUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.plugins.haxe.HaxeCodeInsightFixtureTestCase;
import com.intellij.plugins.haxe.v2.buildtools.settings.HaxeActiveBuildFileStore;
import com.intellij.plugins.haxe.v2.buildtools.settings.HaxeResolveContextStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

@DisplayName("Resolve context: ownership")
public class HaxeResolveContextsTest extends HaxeCodeInsightFixtureTestCase {
  private static final String WEB_HXML_SOURCE = """
    -cp src
    -main WebMain
    -js bin/web.js
    """;
  private static final String NODE_HXML_SOURCE = """
    -cp src
    -main NodeMain
    -js bin/node.js
    """;
  private static final String TOOLS_HXML_SOURCE = """
    -cp src/tools
    -main Tool
    --interp
    """;

  @TempDir
  Path libraryDirectory;

  @Override
  protected String getBasePath() {
    return "";
  }

  @Test
  @DisplayName("the most specific source directory owns a file")
  public void testTheMostSpecificSourceDirectoryOwnsAFile() {
    VirtualFile web = hxml("web.hxml", WEB_HXML_SOURCE);
    VirtualFile tools = hxml("tools.hxml", TOOLS_HXML_SOURCE);
    VirtualFile tool = source("src/tools/Tool.hx", "class Tool {}");
    VirtualFile shared = source("src/Shared.hx", "class Shared {}");

    assertEquals(tools.getPath(), contextPath(tool), "src/tools is more specific than src");
    assertEquals(web.getPath(), contextPath(shared), "only the web build's -cp src contains it");
  }

  @Test
  @DisplayName("shared sources go to the build whose main class the file is")
  public void testSharedSourcesGoToTheBuildWhoseMainClassTheFileIs() {
    VirtualFile web = hxml("web.hxml", WEB_HXML_SOURCE);
    VirtualFile node = hxml("node.hxml", NODE_HXML_SOURCE);
    VirtualFile webMain = source("src/WebMain.hx", "class WebMain {}");
    VirtualFile nodeMain = source("src/NodeMain.hx", "class NodeMain {}");
    HaxeActiveBuildFileStore.getInstance(getProject()).setActiveFile(web.getPath());

    assertEquals(node.getPath(), contextPath(nodeMain), "the -main tie-break beats the active build file");
    assertEquals(web.getPath(), contextPath(webMain));
  }

  @Test
  @DisplayName("shared sources fall to the active build file, then to the first known build")
  public void testSharedSourcesFallToTheActiveBuildFileThenToTheFirstKnownBuild() {
    VirtualFile web = hxml("web.hxml", WEB_HXML_SOURCE);
    VirtualFile node = hxml("node.hxml", NODE_HXML_SOURCE);
    VirtualFile shared = source("src/Shared.hx", "class Shared {}");
    HaxeActiveBuildFileStore store = HaxeActiveBuildFileStore.getInstance(getProject());

    store.setActiveFile(node.getPath());
    assertEquals(node.getPath(), contextPath(shared));

    store.setActiveFile(web.getPath());
    assertEquals(web.getPath(), contextPath(shared));

    store.loadState(new HaxeActiveBuildFileStore.State());
    List<HaxeResolveContext> reaching = contexts().contextsReaching(shared);
    assertEquals(2, reaching.size(), "both builds contain the file");
    assertEquals(reaching.getFirst().path(), contextPath(shared), "without an active build the first known one wins");
  }

  @Test
  @DisplayName("a pin wins over the automatic choice while its build contains the file")
  public void testAPinWinsOverTheAutomaticChoiceWhileItsBuildContainsTheFile() {
    VirtualFile web = hxml("web.hxml", WEB_HXML_SOURCE);
    hxml("node.hxml", NODE_HXML_SOURCE);
    VirtualFile nodeMain = source("src/NodeMain.hx", "class NodeMain {}");
    HaxeResolveContextStore store = HaxeResolveContextStore.getInstance(getProject());

    store.pin(nodeMain, web.getPath());
    assertEquals(web.getPath(), contextPath(nodeMain), "the pin beats the -main rule");
    assertTrue(contexts().isPinned(nodeMain));

    store.pin(nodeMain, "/elsewhere/gone.hxml");
    assertEquals(hxmlPath("node.hxml"), contextPath(nodeMain), "a pin to a build that does not contain the file is ignored");
    assertFalse(contexts().isPinned(nodeMain));

    store.unpin(nodeMain);
    assertFalse(contexts().isPinned(nodeMain));
  }

  @Test
  @DisplayName("contexts reaching a file list every owner, the most specific first")
  public void testContextsReachingAFileListEveryOwnerTheMostSpecificFirst() {
    VirtualFile web = hxml("web.hxml", WEB_HXML_SOURCE);
    VirtualFile tools = hxml("tools.hxml", TOOLS_HXML_SOURCE);
    VirtualFile tool = source("src/tools/Tool.hx", "class Tool {}");

    List<String> reaching = contexts().contextsReaching(tool).stream()
      .map(HaxeResolveContext::path)
      .toList();

    assertEquals(List.of(tools.getPath(), web.getPath()), reaching);
  }

  @Test
  @DisplayName("a library file is reached by every build, the active one first")
  public void testALibraryFileIsReachedByEveryBuildTheActiveOneFirst() throws IOException {
    VirtualFile web = hxml("web.hxml", WEB_HXML_SOURCE);
    VirtualFile node = hxml("node.hxml", NODE_HXML_SOURCE);
    VirtualFile libraryFile = libraryFile("Lib.hx");
    HaxeActiveBuildFileStore.getInstance(getProject()).setActiveFile(node.getPath());

    List<String> reaching = contexts().contextsReaching(libraryFile).stream()
      .map(HaxeResolveContext::path)
      .toList();

    assertEquals(Set.of(web.getPath(), node.getPath()), Set.copyOf(reaching), "no build's sources contain it, so every build reaches it");
    assertEquals(node.getPath(), contextPath(libraryFile), "the active build is the default for a library file");
  }

  @Test
  @DisplayName("a file of a project without build files has no context")
  public void testAFileOfAProjectWithoutBuildFilesHasNoContext() {
    VirtualFile lone = source("src/Lone.hx", "class Lone {}");

    assertNull(contexts().contextFor(lone));
  }

  /** A file under a library root OUTSIDE the content root: in library scope, in no build's sources. */
  private VirtualFile libraryFile(String name) throws IOException {
    Path root = Files.createDirectories(libraryDirectory.resolve("lib"));
    Path file = Files.writeString(root.resolve(name), "class Lib {}");
    VirtualFile rootFile = Objects.requireNonNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root));
    ModuleRootModificationUtil.addModuleLibrary(myFixture.getModule(), "haxelib: fake", List.of(rootFile.getUrl()), List.of());
    return Objects.requireNonNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(file));
  }

  private VirtualFile hxml(String name, String content) {
    return myFixture.addFileToProject(name, content).getVirtualFile();
  }

  private String hxmlPath(String name) {
    return myFixture.findFileInTempDir(name).getPath();
  }

  private VirtualFile source(String relativePath, String content) {
    return myFixture.addFileToProject(relativePath, content).getVirtualFile();
  }

  private HaxeResolveContexts contexts() {
    return HaxeResolveContexts.getInstance(getProject());
  }

  private String contextPath(VirtualFile file) {
    HaxeResolveContext context = contexts().contextFor(file);
    assertNotNull(context, "a build contains " + file.getName());
    return context.path();
  }
}
