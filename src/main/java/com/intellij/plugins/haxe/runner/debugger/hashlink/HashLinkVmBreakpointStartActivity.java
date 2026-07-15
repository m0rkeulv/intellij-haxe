package com.intellij.plugins.haxe.runner.debugger.hashlink;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.ProjectActivity;
import com.intellij.xdebugger.XDebuggerManager;
import com.intellij.xdebugger.XDebuggerUtil;
import com.intellij.xdebugger.breakpoints.XBreakpoint;
import com.intellij.xdebugger.breakpoints.XBreakpointManager;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Installs the "HashLink VM exceptions" breakpoint as a second row of the
 * "HashLink Uncaught Exceptions" category. The platform supports only ONE
 * default breakpoint per type ({@code XBreakpointType.createDefaultBreakpoint}),
 * so this sibling — distinguished by {@link HashLinkUncaughtExceptionProperties#vmErrors}
 * — is added programmatically on project start when missing. It persists with
 * the project's other breakpoints afterwards; if the user deletes the row, it
 * reappears (disabled) on the next open, mirroring default-breakpoint behavior.
 */
public class HashLinkVmBreakpointStartActivity implements ProjectActivity {

  @Override
  public @Nullable Object execute(@NotNull Project project, @NotNull Continuation<? super Unit> continuation) {
    ApplicationManager.getApplication().invokeLater(() -> {
      if (!project.isDisposed()) {
        ApplicationManager.getApplication().runWriteAction(() -> installVmBreakpointIfMissing(project));
      }
    });
    return null;
  }

  private static void installVmBreakpointIfMissing(Project project) {
    HashLinkUncaughtExceptionBreakpointType type =
      XDebuggerUtil.getInstance().findBreakpointType(HashLinkUncaughtExceptionBreakpointType.class);
    if (type == null) {
      return;
    }
    XBreakpointManager manager = XDebuggerManager.getInstance(project).getBreakpointManager();
    for (XBreakpoint<HashLinkUncaughtExceptionProperties> breakpoint : manager.getBreakpoints(type)) {
      if (HashLinkUncaughtExceptionBreakpointType.isVmErrors(breakpoint)) {
        return;
      }
    }
    HashLinkUncaughtExceptionProperties properties = new HashLinkUncaughtExceptionProperties();
    properties.vmErrors = true;
    manager.addBreakpoint(type, properties).setEnabled(false); // off by default; the user opts in
  }
}
