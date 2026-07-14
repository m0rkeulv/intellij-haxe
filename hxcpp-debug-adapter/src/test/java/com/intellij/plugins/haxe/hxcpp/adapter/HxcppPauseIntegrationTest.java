package com.intellij.plugins.haxe.hxcpp.adapter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.intellij.plugins.haxe.runner.debugger.dap.protocol.events.StoppedEvent;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.ConfigurationDoneRequest;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.ContinueArguments;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.ContinueRequest;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.PauseRequest;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.StackTraceArguments;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.StackTraceRequest;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.responses.StackTraceResponse;
import org.junit.Before;
import org.junit.Test;

/**
 * Pause against a genuinely RUNNING debuggee (the spin fixture loops until
 * killed): pause must produce a stop with an inspectable stack, and continue
 * must let it run again.
 */
public class HxcppPauseIntegrationTest extends HxcppIntegrationTestBase {

  @Before
  public void setUp() throws Exception {
    launchFixture("hxcpp.fixture.spin.exe", "Spin.hx");
  }

  @Test
  public void pauseStopsARunningProgramAndContinueResumesIt() throws Exception {
    initializeAndLaunch();
    require(new ConfigurationDoneRequest());

    // let it actually run before interrupting
    Thread.sleep(500);
    assertTrue("spin fixture died prematurely:\n" + output(), debuggee.isAlive());

    require(new PauseRequest());
    StoppedEvent stopped = (StoppedEvent)awaitEvent(StoppedEvent.class);
    assertEquals("pause", stopped.getBody().getReason());

    StackTraceArguments stackArguments = new StackTraceArguments();
    stackArguments.setThreadId(stopped.getBody().getThreadId());
    StackTraceRequest stackRequest = new StackTraceRequest();
    stackRequest.setArguments(stackArguments);
    StackTraceResponse stack = require(stackRequest);
    assertFalse("paused stop has no frames", stack.getBody().getStackFrames().isEmpty());

    ContinueArguments continueArguments = new ContinueArguments();
    continueArguments.setThreadId(stopped.getBody().getThreadId());
    ContinueRequest continueRequest = new ContinueRequest();
    continueRequest.setArguments(continueArguments);
    require(continueRequest);

    Thread.sleep(300);
    assertTrue("debuggee should still be running after continue", debuggee.isAlive());
  }
}
