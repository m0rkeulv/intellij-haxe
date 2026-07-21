package com.intellij.plugins.haxe.runner.debugger.browser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class ContentHttpServerTest {
  private Path root;
  private Path outside;
  private ContentHttpServer server;
  private final HttpClient http = HttpClient.newHttpClient();

  @Before
  public void serveFixture() throws IOException {
    Path parent = Files.createTempDirectory("content-server-test");
    root = Files.createDirectory(parent.resolve("www"));
    outside = Files.writeString(parent.resolve("secret.txt"), "not served");
    Files.writeString(root.resolve("index.html"), "<html>hello</html>");
    Files.writeString(root.resolve("app.js"), "console.log('x');");
    Files.writeString(root.resolve("app.js.map"), "{\"version\":3}");
    server = new ContentHttpServer(root);
  }

  @After
  public void tearDown() {
    if (server != null) {
      server.close();
    }
  }

  private HttpResponse<String> get(String path) throws Exception {
    return http.send(HttpRequest.newBuilder(URI.create(server.getBaseUrl().replaceAll("/$", "") + path)).build(),
                     HttpResponse.BodyHandlers.ofString());
  }

  @Test
  public void servesFilesWithTypesAndNoStore() throws Exception {
    HttpResponse<String> html = get("/index.html");
    assertEquals(200, html.statusCode());
    assertEquals("<html>hello</html>", html.body());
    assertTrue(html.headers().firstValue("Content-Type").orElse("").startsWith("text/html"));
    assertEquals("no-store", html.headers().firstValue("Cache-Control").orElse(""));

    assertTrue(get("/app.js").headers().firstValue("Content-Type").orElse("").startsWith("text/javascript"));
    // source maps as json so devtools/adapters parse them without sniffing
    assertTrue(get("/app.js.map").headers().firstValue("Content-Type").orElse("").startsWith("application/json"));
  }

  @Test
  public void directoryServesItsIndex() throws Exception {
    HttpResponse<String> response = get("/");
    assertEquals(200, response.statusCode());
    assertEquals("<html>hello</html>", response.body());
  }

  @Test
  public void missingFileIs404() throws Exception {
    assertEquals(404, get("/nope.js").statusCode());
  }

  @Test
  public void traversalOutsideTheRootIs404() throws Exception {
    assertTrue("precondition: the secret exists", Files.isRegularFile(outside));
    // raw and percent-encoded traversal must both fail (the JDK client
    // normalizes plain "..", so also test the encoded form end to end)
    assertEquals(404, get("/../secret.txt").statusCode());
    assertEquals(404, get("/%2e%2e/secret.txt").statusCode());
  }

  @Test
  public void writeMethodsAreRejected() throws Exception {
    HttpResponse<String> response = http.send(
      HttpRequest.newBuilder(URI.create(server.getBaseUrl() + "index.html"))
        .POST(HttpRequest.BodyPublishers.ofString("x")).build(),
      HttpResponse.BodyHandlers.ofString());
    assertEquals(405, response.statusCode());
  }

  @Test
  public void firstPageRefreshInjectsExactlyOnce() throws Exception {
    server.refreshFirstPage(2);
    String first = get("/index.html").body();
    assertTrue("meta refresh injected into the first response: " + first,
               first.contains("<meta http-equiv=\"refresh\" content=\"2\">"));
    assertTrue("original content preserved", first.contains("hello"));
    String second = get("/index.html").body();
    assertEquals("second response is served clean", "<html>hello</html>", second);
    // scripts are never touched by the injection
    assertEquals("console.log('x');", get("/app.js").body());
  }

  @Test
  public void headHasNoBody() throws Exception {
    HttpResponse<String> response = http.send(
      HttpRequest.newBuilder(URI.create(server.getBaseUrl() + "index.html"))
        .method("HEAD", HttpRequest.BodyPublishers.noBody()).build(),
      HttpResponse.BodyHandlers.ofString());
    assertEquals(200, response.statusCode());
    assertEquals("", response.body());
  }
}
