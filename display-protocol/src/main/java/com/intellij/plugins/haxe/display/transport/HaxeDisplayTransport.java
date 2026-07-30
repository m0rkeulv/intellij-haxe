package com.intellij.plugins.haxe.display.transport;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The null-terminated request form of a {@code haxe --wait <port>} server:
 * connect, write every argument followed by {@code \n} and a single trailing
 * {@code \0}, read until the server closes, close.
 *
 * Strictly one socket per request, closed immediately: the server processes
 * one connection at a time and an idle open connection stalls every other
 * client (including builds) until a server-side read timeout. Never pool.
 */
public final class HaxeDisplayTransport {

  private static final int CONNECT_TIMEOUT_MS = 3_000;

  private HaxeDisplayTransport() {
  }

  public static DisplayResponse request(String host, int port, List<String> args, int readTimeoutMs)
    throws DisplayRequestException {
    byte[] raw = exchange(host, port, encode(args), readTimeoutMs);
    return classify(raw);
  }

  private static byte[] encode(List<String> args) {
    StringBuilder body = new StringBuilder();
    for (String arg : args) {
      body.append(arg).append('\n');
    }
    byte[] text = body.toString().getBytes(StandardCharsets.UTF_8);
    byte[] message = new byte[text.length + 1];
    System.arraycopy(text, 0, message, 0, text.length);
    message[text.length] = 0;
    return message;
  }

  private static byte[] exchange(String host, int port, byte[] message, int readTimeoutMs)
    throws DisplayRequestException {
    try (Socket socket = new Socket()) {
      socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
      socket.setSoTimeout(readTimeoutMs);
      OutputStream out = socket.getOutputStream();
      out.write(message);
      out.flush();
      return readAll(socket.getInputStream());
    } catch (IOException e) {
      throw new DisplayRequestException("Haxe server request to " + host + ":" + port + " failed: " + e.getMessage(), e);
    }
  }

  private static byte[] readAll(InputStream in) throws IOException {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    byte[] chunk = new byte[65536];
    int read;
    while ((read = in.read(chunk)) >= 0) {
      buffer.write(chunk, 0, read);
    }
    return buffer.toByteArray();
  }

  /**
   * Splits the response into lines and classifies by first byte: 0x01 = log
   * line (embedded newlines arrive as further 0x01 bytes), 0x02 = fatal-error
   * marker, anything else is payload.
   */
  static DisplayResponse classify(byte[] raw) {
    String text = new String(raw, StandardCharsets.UTF_8);
    List<String> logs = new ArrayList<>();
    StringBuilder payload = new StringBuilder();
    boolean hasError = false;
    for (String line : text.split("\n", -1)) {
      if (line.isEmpty()) continue;
      if (line.charAt(0) == 0x01) {
        logs.add(line.substring(1).replace('\u0001', '\n'));
      } else if (line.charAt(0) == 0x02) {
        hasError = true;
      } else {
        if (payload.length() > 0) payload.append('\n');
        payload.append(line);
      }
    }
    return new DisplayResponse(payload.toString(), List.copyOf(logs), hasError);
  }
}
