package com.intellij.plugins.haxe.runner.debugger.hashlink;

import com.intellij.execution.configurations.GeneralCommandLine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("HashLink debugger: backend")
public class HashLinkBackendTest {

  @Test
  @DisplayName("the debuggee command line holds the program on the backends debug port followed by its arguments")
  public void testTheDebuggeeCommandLineHoldsTheProgramOnTheBackendsDebugPortFollowedByItsArguments() {
    HashLinkBackend backend = new HashLinkBackend(Path.of("hl"), Path.of("out", "app.hl"), 6111, List.of());

    GeneralCommandLine commandLine = backend.debuggeeCommandLine("work", List.of("--level", "two words"));

    assertEquals(Path.of("hl").toString(), commandLine.getExePath());
    assertEquals(List.of("--debug", "6111", "--debug-wait", Path.of("out", "app.hl").toString(), "--level", "two words"),
                 commandLine.getParametersList().getList());
    assertEquals("work", commandLine.getWorkDirectory().getPath());
  }
}
