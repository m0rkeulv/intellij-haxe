package com.intellij.plugins.haxe.hxcpp;

import com.intellij.plugins.haxe.runner.debugger.dap.protocol.DapThread;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.StackFrame;
import com.intellij.xdebugger.frame.XExecutionStack;
import com.intellij.xdebugger.frame.XSuspendContext;
import java.util.List;
import org.jetbrains.annotations.Nullable;

/**
 * One stop of the (suspend-all) HXCPP debuggee: every live thread as an
 * execution stack, with the stopped thread active and its frames pre-fetched.
 * Selecting another thread lazily walks its stack.
 */
final class HxcppSuspendContext extends XSuspendContext {
  private final HxcppExecutionStack[] stacks;
  private final HxcppExecutionStack active;

  HxcppSuspendContext(HxcppDebugProcess process, List<DapThread> threads,
                      int activeThreadId, List<StackFrame> activeFrames,
                      @Nullable String exceptionText) {
    // fall back to a single synthetic thread if the list is somehow empty
    if (threads.isEmpty()) {
      this.active = new HxcppExecutionStack(process, activeThreadId, "main", activeFrames, exceptionText);
      this.stacks = new HxcppExecutionStack[]{active};
      return;
    }
    this.stacks = new HxcppExecutionStack[threads.size()];
    HxcppExecutionStack activeStack = null;
    for (int i = 0; i < threads.size(); i++) {
      DapThread thread = threads.get(i);
      boolean isActive = thread.getId() == activeThreadId;
      HxcppExecutionStack stack = new HxcppExecutionStack(
        process, thread.getId(), threadLabel(thread), isActive ? activeFrames : null,
        // the exception gutter marker belongs only on the thread that threw
        isActive ? exceptionText : null);
      stacks[i] = stack;
      if (isActive) {
        activeStack = stack;
      }
    }
    // the stopped thread should always be in the list; guard just in case
    this.active = activeStack != null ? activeStack : stacks[0];
  }

  private static String threadLabel(DapThread thread) {
    String name = thread.getName();
    return (name != null ? name : "thread") + " [" + thread.getId() + "]";
  }

  @Override
  public @Nullable XExecutionStack getActiveExecutionStack() {
    return active;
  }

  @Override
  public XExecutionStack[] getExecutionStacks() {
    return stacks;
  }
}
