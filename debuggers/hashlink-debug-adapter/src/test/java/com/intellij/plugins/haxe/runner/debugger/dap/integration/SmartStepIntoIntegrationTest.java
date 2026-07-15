package com.intellij.plugins.haxe.runner.debugger.dap.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.intellij.plugins.haxe.runner.debugger.dap.protocol.StepInTarget;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.events.StoppedEvent;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.DisconnectRequest;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.StepInArguments;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.StepInRequest;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.StepInTargetsArguments;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.StepInTargetsRequest;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.responses.StepInTargetsResponse;
import java.util.List;
import org.junit.Test;

/**
 * Smart step into: on a line with several calls, stepInTargets lists them (in
 * execution order) and stepIn with a targetId enters exactly the chosen one —
 * running through the calls before it without stopping.
 *
 * Uses Main.hx line 20, the demo line with eight calls:
 * throwDemo(); inspectDemo(); Rich.demo(); Shadowed.demo(); ...
 */
public class SmartStepIntoIntegrationTest extends DapIntegrationTestBase {

  private static final int FIXTURE_DEMO_LINE = 20;

  @Test
  public void stepInTargetsListsTheCallsOnTheLineInExecutionOrder() throws Exception {
    StoppedEvent atDemo = runToBreakpoint(FIXTURE_MAIN, FIXTURE_DEMO_LINE);
    int frameId = newestFrameId(atDemo.getBody().getThreadId());

    List<StepInTarget> targets = requestStepInTargets(frameId);

    assertTrue("several call targets on the demo line (got " + targets + ")", targets.size() >= 4);
    assertTrue("first target is the first call on the line (was " + targets.get(0).getLabel() + ")",
               targets.get(0).getLabel().endsWith("Main.throwDemo"));
    assertTrue("second target follows source order (was " + targets.get(1).getLabel() + ")",
               targets.get(1).getLabel().endsWith("Main.inspectDemo"));
    assertTrue("Rich.demo is offered (targets: " + targets + ")",
               targets.stream().anyMatch(t -> t.getLabel().endsWith("Rich.demo")));

    request(new DisconnectRequest());
  }

  @Test
  public void stepInWithATargetIdEntersTheChosenCallSkippingTheOnesBefore() throws Exception {
    StoppedEvent atDemo = runToBreakpoint(FIXTURE_MAIN, FIXTURE_DEMO_LINE);
    int threadId = atDemo.getBody().getThreadId();
    List<StepInTarget> targets = requestStepInTargets(newestFrameId(threadId));
    StepInTarget richDemo = targets.stream()
      .filter(t -> t.getLabel().endsWith("Rich.demo")).findFirst().orElseThrow();

    StepInRequest stepIn = new StepInRequest();
    StepInArguments arguments = new StepInArguments();
    arguments.setThreadId(threadId);
    arguments.setTargetId(richDemo.getId());
    stepIn.setArguments(arguments);
    assertTrue("targeted stepIn accepted", request(stepIn).isSuccess());
    StoppedEvent landed = awaitStopped();

    assertEquals("step", landed.getBody().getReason());
    var frame = stackTrace(landed.getBody().getThreadId()).getBody().getStackFrames().get(0);
    assertTrue("landed in Rich.demo (was " + frame.getName() + ")", frame.getName().endsWith("Rich.demo"));
    assertFalse("did not stop in the earlier calls", frame.getName().contains("throwDemo"));

    request(new DisconnectRequest());
  }

  private int newestFrameId(int threadId) throws Exception {
    return stackTrace(threadId).getBody().getStackFrames().get(0).getId();
  }

  private List<StepInTarget> requestStepInTargets(int frameId) throws Exception {
    StepInTargetsRequest request = new StepInTargetsRequest();
    StepInTargetsArguments arguments = new StepInTargetsArguments();
    arguments.setFrameId(frameId);
    request.setArguments(arguments);
    var response = request(request);
    assertTrue("stepInTargets succeeds", response.isSuccess());
    assertTrue("typed response", response instanceof StepInTargetsResponse);
    return ((StepInTargetsResponse)response).getBody().getTargets();
  }
}
