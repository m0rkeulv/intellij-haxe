package com.intellij.plugins.haxe.matrix;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/** Console + progress-file logging (append; a tailing reader is harmless). */
final class Log {
  private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
  private final Path file;

  Log(Path file) throws IOException {
    this.file = file;
    Files.createDirectories(file.getParent());
  }

  void line(String message) {
    String stamped = "[" + LocalTime.now().format(TIME) + "] " + message;
    System.out.println(stamped);
    try {
      Files.writeString(file, stamped + System.lineSeparator(),
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    } catch (IOException ignored) {
      // the console line already happened; a locked progress file is not fatal
    }
  }
}
