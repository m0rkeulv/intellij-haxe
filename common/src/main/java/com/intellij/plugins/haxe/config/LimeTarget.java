package com.intellij.plugins.haxe.config;

/**
 * The lime tool's project targets (its primary targets plus the host-platform
 * aliases worth offering), as listed by {@code lime help build}. The single
 * source of the lime target list: the tool window's target selector and the
 * project wizard both offer exactly these. The HaxeTarget is the haxe
 * compilation backend each target uses - informational, never passed to the
 * tool. OpenFL has its own list ({@link OpenFLTarget}), NME too
 * ({@link NMETarget}) - identical today, maintained per build system.
 */
public enum LimeTarget {

  HTML5("HTML5", HaxeTarget.JAVA_SCRIPT, "html5"),
  WINDOWS("Windows", HaxeTarget.CPP, "windows"),
  MAC("Mac OS", HaxeTarget.CPP, "mac"),
  LINUX("Linux", HaxeTarget.CPP, "linux"),
  HL("HashLink", HaxeTarget.HL, "hl"),
  NEKO("Neko", HaxeTarget.NEKO, "neko"),
  FLASH("Flash", HaxeTarget.FLASH, "flash"),
  AIR("Adobe AIR", HaxeTarget.FLASH, "air"),
  ANDROID("Android", HaxeTarget.CPP, "android"),
  IOS("iOS", HaxeTarget.CPP, "ios"),
  TVOS("tvOS", HaxeTarget.CPP, "tvos"),
  WEBASSEMBLY("WebAssembly", HaxeTarget.CPP, "webassembly"),
  ELECTRON("Electron", HaxeTarget.JAVA_SCRIPT, "electron");

  private final String[] flags;
  private final String description;
  private final HaxeTarget outputTarget;

  LimeTarget(String description, HaxeTarget target, String... flags) {
    this.flags = flags;
    this.description = description;
    this.outputTarget = target;
  }

  public String getTargetFlag() {
    return flags.length > 0 ? flags[0] : "";
  }

  public String[] getFlags() {
    return flags;
  }

  public HaxeTarget getOutputTarget() { return outputTarget; }

  @Override
  public String toString() {
    return description;
  }
}
