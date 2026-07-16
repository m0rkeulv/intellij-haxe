package com.intellij.plugins.haxe.runner.debugger.exceptions;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.xdebugger.XDebuggerManager;
import com.intellij.xdebugger.breakpoints.XBreakpoint;
import com.intellij.xdebugger.breakpoints.XBreakpointManager;
import com.intellij.xdebugger.breakpoints.XBreakpointType;
import javax.swing.JComponent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Per-class exception breakpoints (Java-style), shared by the HashLink and
 * HXCPP debuggers: the user clicks "+" in the Breakpoints dialog, names an
 * exception class, and gets a breakpoint that stops only on throws of that
 * class or a subclass. Each breakpoint carries its class name in
 * {@link HaxeExceptionBreakpointProperties}; the debug processes forward the
 * enabled class names as type filters. On HXCPP the match is against the
 * runtime class of the {@code haxe.Exception} under construction, so it also
 * catches subclasses with inherited constructors; raw-value throws are not
 * visible there.
 */
public class HaxeTypedExceptionBreakpointType
  extends XBreakpointType<XBreakpoint<HaxeExceptionBreakpointProperties>, HaxeExceptionBreakpointProperties> {

  public HaxeTypedExceptionBreakpointType() {
    super("haxe-typed-exception", "Haxe Exception Breakpoints");
  }

  @Override
  public @NotNull String getDisplayText(XBreakpoint<HaxeExceptionBreakpointProperties> breakpoint) {
    HaxeExceptionBreakpointProperties properties = breakpoint.getProperties();
    String className = properties == null ? null : properties.className;
    return (className == null || className.isBlank())
      ? "Any exception (typed)"
      : "Exception: " + className;
  }

  @Override
  public @Nullable HaxeExceptionBreakpointProperties createProperties() {
    return new HaxeExceptionBreakpointProperties();
  }

  @Override
  public boolean isAddBreakpointButtonVisible() {
    return true;
  }

  // the lightning-bolt exception icons, like the Java debugger's
  @Override
  public @NotNull javax.swing.Icon getEnabledIcon() {
    return AllIcons.Debugger.Db_exception_breakpoint;
  }

  @Override
  public @NotNull javax.swing.Icon getDisabledIcon() {
    return AllIcons.Debugger.Db_disabled_exception_breakpoint;
  }

  @Override
  public @Nullable XBreakpoint<HaxeExceptionBreakpointProperties> addBreakpoint(Project project, JComponent parentComponent) {
    String input = Messages.showInputDialog(project,
                                            "Exception class (fully-qualified or simple name):",
                                            "Add Haxe Exception Breakpoint",
                                            Messages.getQuestionIcon());
    if (input == null || input.isBlank()) {
      return null;
    }
    String className = input.trim();
    return WriteAction.compute(() -> {
      XBreakpointManager manager = XDebuggerManager.getInstance(project).getBreakpointManager();
      HaxeExceptionBreakpointProperties properties = new HaxeExceptionBreakpointProperties();
      properties.className = className;
      return manager.addBreakpoint(this, properties);
    });
  }
}
