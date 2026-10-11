package com.intellij.plugins.haxe.v2.buildtools.context;

import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.plugins.haxe.HaxeCodeInsightFixtureTestCase;
import com.intellij.plugins.haxe.v2.buildtools.settings.HaxeActiveBuildFileStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
  @DisplayName("a file of a project without build files has no context")
  public void testAFileOfAProjectWithoutBuildFilesHasNoContext() {
    VirtualFile lone = source("src/Lone.hx", "class Lone {}");

    assertNull(contexts().contextFor(lone));
  }

  private VirtualFile hxml(String name, String content) {
    return myFixture.addFileToProject(name, content).getVirtualFile();
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
