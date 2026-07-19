package com.intellij.plugins.haxe.matrix;

import java.util.List;

/**
 * The toolchain versions the matrix certifies, with their per-OS release
 * assets. Compile-checked on purpose: adding a version to certify is adding
 * one line here. A null asset means the project publishes no binary for that
 * OS (HashLink stopped shipping linux binaries after 1.6 — provide those by
 * building from source into debuggerResources/hashlink/<name>/, they are
 * DISCOVERED next to the downloaded ones).
 *
 * Support floor (haxe >= 4.1, HashLink >= 1.13) is simply "not listed here".
 */
final class VersionManifest {

  record Tool(String name, String url) {
    boolean downloadable() {
      return url != null;
    }
  }

  private static final String HAXE = "https://github.com/HaxeFoundation/haxe/releases/download/";
  private static final String HL = "https://github.com/HaxeFoundation/hashlink/releases/download/";

  private static Tool haxe(String name, String tag) {
    String asset = Platform.WINDOWS ? "haxe-" + tag + "-win64.zip" : "haxe-" + tag + "-linux64.tar.gz";
    return new Tool(name, HAXE + tag + "/" + asset);
  }

  private static Tool hashlink(String name, String tag, String windowsAsset) {
    return new Tool(name, Platform.WINDOWS ? HL + tag + "/" + windowsAsset : null);
  }

  static List<Tool> haxeVersions() {
    return List.of(
      haxe("haxe_4_1_5", "4.1.5"),
      haxe("haxe_4_2_5", "4.2.5"),
      haxe("haxe_4_3_0", "4.3.0"),
      haxe("haxe_4_3_7", "4.3.7"),
      haxe("haxe_5_preview_1", "5.0.0-preview.1"));
  }

  static List<Tool> hashlinkVersions() {
    return List.of(
      hashlink("hashlink-1.13.0", "1.13", "hashlink-1.13.0-win.zip"),
      hashlink("hashlink-1.14.0", "1.14", "hashlink-1.14.0-win.zip"),
      hashlink("hashlink-1.15.0", "1.15", "hashlink-1.15.0-win.zip"));
  }

  /**
   * haxe versions whose HL fixture failures were proven identical on every
   * runtime: unless --full, they run against the reference runtime only.
   */
  static final List<String> DEGRADED_HAXE_ON_HL = List.of("haxe_4_1_5", "haxe_4_2_5");
  static final String REFERENCE_RUNTIME = "hashlink-1.15.0";
}
