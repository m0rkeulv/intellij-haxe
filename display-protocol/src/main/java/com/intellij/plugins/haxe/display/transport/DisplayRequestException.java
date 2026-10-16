package com.intellij.plugins.haxe.display.transport;

/**
 * A display request failed. The connection failed, the compiler rejected the
 * arguments (the response carried the 0x02 error marker), or the JSON-RPC
 * envelope carried an error. {@link #isCompilerAnswered} tells the last two,
 * which repeat until the code or the arguments change, from the first, which
 * may pass on retry.
 */
public class DisplayRequestException extends Exception {
  private final boolean compilerAnswered;

  public DisplayRequestException(String message) {
    this(message, false);
  }

  public DisplayRequestException(String message, boolean compilerAnswered) {
    super(message);
    this.compilerAnswered = compilerAnswered;
  }

  public DisplayRequestException(String message, Throwable cause) {
    this(message, false, cause);
  }

  public DisplayRequestException(String message, boolean compilerAnswered, Throwable cause) {
    super(message, cause);
    this.compilerAnswered = compilerAnswered;
  }

  /** Whether the compiler itself answered and rejected the request, as opposed to a connection or framing failure. */
  public boolean isCompilerAnswered() {
    return compilerAnswered;
  }
}
