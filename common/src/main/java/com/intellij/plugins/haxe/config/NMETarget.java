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
 * The nme tool's project targets (its accepted target words; the tool also
 * takes "jsprime" as the canonical name of what "html5" aliases). "cpp" builds
 * for the host desktop and is the tool's own default; the explicit desktop
 * names select the same C++ build. The HaxeTarget is the haxe compilation
 * backend each target uses - informational, never passed to the tool.
 */
public enum NMETarget {

  CPP("Desktop (C++)", HaxeTarget.CPP, "cpp"),
  WINDOWS("Windows", HaxeTarget.CPP, "windows"),
  MAC("Mac OS", HaxeTarget.CPP, "mac"),
  LINUX("Linux", HaxeTarget.CPP, "linux"),
  NEKO("Neko", HaxeTarget.NEKO, "neko"),
  CPPIA("Cppia (acadnme host)", HaxeTarget.CPPIA, "cppia"),
  FLASH("Flash", HaxeTarget.FLASH, "flash"),
  // needs an nme with the Emscripten runtime; the stock haxelib release ships without it
  HTML5("HTML5 (jsprime)", HaxeTarget.JAVA_SCRIPT, "html5"),
  ANDROID("Android", HaxeTarget.CPP, "android"),
  IOS("iOS", HaxeTarget.CPP, "ios"),
  RPI("Raspberry Pi", HaxeTarget.CPP, "rpi"),
  WINRT("WinRT / UWP", HaxeTarget.CPP, "winrt");

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
