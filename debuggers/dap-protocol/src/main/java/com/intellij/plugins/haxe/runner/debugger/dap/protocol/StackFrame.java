package com.intellij.plugins.haxe.runner.debugger.dap.protocol;

import lombok.Data;

/**
 * One frame in a "stackTrace" response.
 */
@Data
public class StackFrame {
  private int id;
  private String name;
  private int line;
  private int column;
  // exact end of the frame's current expression (eval target); null elsewhere
  private Integer endLine;
  private Integer endColumn;
  private Source source;
}
