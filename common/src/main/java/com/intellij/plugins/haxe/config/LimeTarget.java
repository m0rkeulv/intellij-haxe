package com.intellij.plugins.haxe.config;

/**
 * Default Lime Targets
 * <a href="https://lime.openfl.org/docs/getting-started/targets/">...</a>
 */
public enum LimeTarget {

  AIR("Adobe AIR", HaxeTarget.FLASH, "air"),
  ANDROID("Android", HaxeTarget.CPP, "android"),
  WEBASSEMBLY("WebAssembly", HaxeTarget.CPP, "webassembly"),
  FLASH("Flash", HaxeTarget.FLASH, "flash"),
  HTML5("HTML5", HaxeTarget.JAVA_SCRIPT, "html5"),
  IOS("iOS", HaxeTarget.CPP, "ios"),
  LINUX("Linux", HaxeTarget.CPP, "linux"),
  MAC("Mac OS", HaxeTarget.CPP, "mac"),
  TVOS("tvOS", HaxeTarget.CPP, "tvos"),
  WINDOWS("Windows", HaxeTarget.CPP, "windows"),
  ELECTRON("Electron", HaxeTarget.JAVA_SCRIPT, "electron"),
  HL("HashLink", HaxeTarget.HL, "hl"),
  NEKO("Neko", HaxeTarget.NEKO, "neko"),
  RPI("Raspberry Pi", HaxeTarget.CPP, "rpi");

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
