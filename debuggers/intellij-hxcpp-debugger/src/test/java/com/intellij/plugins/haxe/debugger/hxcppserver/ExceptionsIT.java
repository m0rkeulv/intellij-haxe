package com.intellij.plugins.haxe.debugger.hxcppserver;

import com.intellij.plugins.haxe.runner.debugger.dap.protocol.events.StoppedEvent;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.ExceptionInfoArguments;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.ExceptionInfoRequest;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.responses.ExceptionInfoResponse;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * The M6 exception matrix against the scenario fixture: an uncatchable throw
 * stops AT the throw site before unwinding; a caught throw never stops; null
 * access stops as a critical error EVEN inside try/catch (a runtime property
 * under an attached debugger); disabled filters resume silently.
 */
public class ExceptionsIT {

  @Test
  public void anUncaughtThrowStopsAtTheThrowSite() throws Exception {
    try (FixtureSession session = FixtureSession.launchScenario("uncaught")) {
      session.initialize("uncaught", "critical");
      session.configurationDone();

      StoppedEvent stopped = session.awaitStopped();
      assertEquals("exception", stopped.getBody().getReason());
      assertEquals("Uncaught exception", stopped.getBody().getDescription());
      assertTrue("the runtime message names the thrown value",
                 stopped.getBody().getText().contains("boom-uncaught"));
      int threadId = session.stoppedThread(stopped);
      assertEquals("stopped AT the throw, before unwinding",
                   FixtureSession.EX_THROW_LINE, session.topFrame(threadId).getLine());

      // locals at the throw site are inspectable
      assertEquals("42", session.evaluate("marker", session.topFrame(threadId).getId()));

      ExceptionInfoRequest info = new ExceptionInfoRequest();
      ExceptionInfoArguments arguments = new ExceptionInfoArguments();
      arguments.setThreadId(threadId);
      info.setArguments(arguments);
      ExceptionInfoResponse response = (ExceptionInfoResponse)session.request(info);
      assertTrue("exceptionInfo", response.isSuccess());
      assertEquals("unhandled", response.getBody().getBreakMode());
      assertTrue(response.getBody().getDescription().contains("boom-uncaught"));

      // resuming an uncatchable throw unwinds and terminates normally
      session.resume(threadId);
      assertNotEquals("the program terminated with the error", 0, session.awaitExit());
    }
  }

  @Test
  public void aCaughtThrowNeverStops() throws Exception {
    try (FixtureSession session = FixtureSession.launchScenario("caught")) {
      session.initialize("uncaught", "critical");
      session.configurationDone();
      assertEquals(0, session.awaitExit());
      assertTrue("the catch ran", session.outputSnapshot().contains("caught:boom-caught"));
      assertTrue("the program completed", session.outputSnapshot().contains("ex-end"));
    }
  }

  @Test
  public void aNullAccessStopsAsACriticalErrorEvenInsideTryCatch() throws Exception {
    try (FixtureSession session = FixtureSession.launchScenario("caught-null")) {
      session.initialize("uncaught", "critical");
      session.configurationDone();

      StoppedEvent stopped = session.awaitStopped();
      assertEquals("exception", stopped.getBody().getReason());
      assertEquals("Critical error", stopped.getBody().getDescription());
      assertTrue(stopped.getBody().getText().contains("Null"));
      assertEquals(FixtureSession.EX_CAUGHT_NULL_LINE,
                   session.topFrame(session.stoppedThread(stopped)).getLine());
    }
  }

  @Test
  public void disabledFiltersResumeAnUncaughtThrowSilently() throws Exception {
    try (FixtureSession session = FixtureSession.launchScenario("uncaught")) {
      session.initialize(/* all filters off */);
      session.configurationDone();
      // no stop: the program unwinds and dies on its own
      assertNotEquals(0, session.awaitExit());
      assertTrue("it reached the throw", session.outputSnapshot().contains("ex-start:uncaught"));
    }
  }
}
