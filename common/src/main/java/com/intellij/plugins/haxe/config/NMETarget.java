package com.intellij.plugins.haxe.config;

/**
 * NME targets based on commandline output from "NME help"
 */
public enum NMETarget {

  CPP("Desktop (C++)", HaxeTarget.CPP, "cpp"),
  CPPIA("Cppia", HaxeTarget.CPPIA, "cppia"),
  ANDROID("Android", HaxeTarget.CPP, "android"),
  ANDROIDVIEW("Android (library view)", HaxeTarget.CPP, "androidview"),
  ANDROIDSIM("Android (simulator)", HaxeTarget.CPP, "androidsim"),
  IOS("iOS", HaxeTarget.CPP, "ios"),
  IPHONE("iOS (device debugging)", HaxeTarget.CPP, "iphone"),
  IPHONESIM("iOS (simulator)", HaxeTarget.CPP, "iphonesim"),
  IOSVIEW("iOS (library view)", HaxeTarget.CPP, "iosview"),
  WATCHOS("watchOS", HaxeTarget.CPP, "watchos"),
  WATCHSIMULATOR("watchOS (simulator)", HaxeTarget.CPP, "watchsimulator"),
  FLASH("Flash", HaxeTarget.FLASH, "flash"),
  WINDOWS("Windows", HaxeTarget.CPP, "windows"),
  ARM64("Windows Arm64", HaxeTarget.CPP, "arm64"),
  WINRT("WinRT / UWP", HaxeTarget.CPP, "winrt"),
  MAC("Mac OS", HaxeTarget.CPP, "mac"),
  LINUX("Linux", HaxeTarget.CPP, "linux"),
  RPI("Raspberry Pi", HaxeTarget.CPP, "rpi"),
  RG350("RG350 console", HaxeTarget.CPP, "rg350"),
  NEKO("Neko", HaxeTarget.NEKO, "neko"),
  HTML5("HTML5 (jsprime)", HaxeTarget.JAVA_SCRIPT, "html5");

  /**
   * The default target for new projects and unset selections — the tool's own
   * no-target default. avoid HTML5, nmes html5/jsprime target needs Emscripten
   */
  public static final NMETarget DEFAULT = CPP;

  private final String[] flags;
  private final String description;
  private final HaxeTarget outputTarget;

  NMETarget(String description, HaxeTarget target, String... flags) {
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
