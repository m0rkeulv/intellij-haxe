/*
 * Copyright 2000-2013 JetBrains s.r.o.
 * Copyright 2014-2014 AS3Boyan
 * Copyright 2014-2014 Elias Ku
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.plugins.haxe.config;

/**
 * Default openFL Targets (based on lime targets)<br/>
 * See <a href="https://lime.openfl.org/docs/getting-started/targets/">Lime targets docs</a>
 */
public enum OpenFLTarget {

  HTML5("HTML5", HaxeTarget.JAVA_SCRIPT, "html5"),
  WINDOWS("Windows", HaxeTarget.CPP, "windows"),
  MAC("Mac OS", HaxeTarget.CPP, "mac"),
  LINUX("Linux", HaxeTarget.CPP, "linux"),
  HL("HashLink", HaxeTarget.HL, "hl"),
  NEKO("Neko", HaxeTarget.NEKO, "neko"),
  RPI("Raspberry Pi", HaxeTarget.CPP, "rpi"),
  FLASH("Flash", HaxeTarget.FLASH, "flash"),
  AIR("Adobe AIR", HaxeTarget.FLASH, "air"),
  ANDROID("Android", HaxeTarget.CPP, "android"),
  IOS("iOS", HaxeTarget.CPP, "ios"),
  TVOS("tvOS", HaxeTarget.CPP, "tvos"),
  WEBASSEMBLY("WebAssembly", HaxeTarget.CPP, "webassembly"),
  ELECTRON("Electron", HaxeTarget.JAVA_SCRIPT, "electron");

  /** The default target for new projects and unset selections. */
  public static final OpenFLTarget DEFAULT = HTML5;

  private final String[] flags;
  private final String description;
  private final HaxeTarget outputTarget;

  OpenFLTarget(String description, HaxeTarget target, String... flags) {
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
