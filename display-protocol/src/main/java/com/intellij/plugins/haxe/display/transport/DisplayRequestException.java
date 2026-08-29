package com.intellij.plugins.haxe.display.transport;

/**
 * A display request failed: transport trouble, the compiler rejected the
 * arguments (0x02 marker), or the JSON-RPC envelope carried an error.
 */
public class DisplayRequestException extends Exception {

  public DisplayRequestException(String message) {
    super(message);
  }

  public DisplayRequestException(String message, Throwable cause) {
    super(message, cause);
  }
}
