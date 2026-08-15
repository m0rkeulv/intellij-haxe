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
package com.intellij.plugins.haxe.config.sdk.impl;

import com.intellij.plugins.haxe.config.sdk.HaxeSdkAdditionalDataBase;

/**
 * @author: Fedor.Korotkov
 */
public class HaxeSdkAdditionalDataBaseImpl implements HaxeSdkAdditionalDataBase {
  private String homePath = "";
  private String version = "";

  private String nekoBinPath = "";

  // HashLink executable, used by the (experimental) HashLink run/debug support
  private String hlBinPath = "";

  // NodeJS executable for js-target runs (tests and programs); empty = PATH
  private String nodeBinPath = "";

  // Standalone Flash player (projector) launching plain swf runs and debug
  // sessions; the projector has no canonical executable name, so there is no
  // PATH fallback - empty means not configured
  private String flashPlayerPath = "";

  // Name of a Flex/AIR SDK entry in the IDE's SDK table, for the flash-family
  // run/debug lanes; resolvable only while the platform's Flash plugin is
  // installed, stored as a plain name so this data never depends on it
  private String flexSdkName = "";

  private String haxelibPath = "";

  // TODO the v1 compiler-completion feature this toggled is gone and the flag
  //  has no UI anymore; drop it (incl. the JPS mirror) with the V1 removal
  private boolean useCompilerCompletionFlag = false;
  private boolean removeCompletionDuplicatesFlag = true;

  public HaxeSdkAdditionalDataBaseImpl() {
  }

  public HaxeSdkAdditionalDataBaseImpl(String homePath, String version) {
    this.homePath = null == homePath ? "" : homePath;
    this.version  = null == version  ? "" : version;
  }

  public String getHomePath() {
    return homePath;
  }

  public String getVersion() {
    return version;
  }

  public String getNekoBinPath() {
    return nekoBinPath;
  }

  public void setNekoBinPath(String nekoBinPath) {
    this.nekoBinPath = null == nekoBinPath ? "" : nekoBinPath;
  }

  public String getHlBinPath() {
    return hlBinPath;
  }

  public void setHlBinPath(String hlBinPath) {
    this.hlBinPath = null == hlBinPath ? "" : hlBinPath;
  }

  public String getNodeBinPath() {
    return nodeBinPath;
  }

  public void setNodeBinPath(String nodeBinPath) {
    this.nodeBinPath = null == nodeBinPath ? "" : nodeBinPath;
  }

  public String getFlashPlayerPath() {
    return flashPlayerPath;
  }

  public void setFlashPlayerPath(String flashPlayerPath) {
    this.flashPlayerPath = null == flashPlayerPath ? "" : flashPlayerPath;
  }

  public String getFlexSdkName() {
    return flexSdkName;
  }

  public void setFlexSdkName(String flexSdkName) {
    this.flexSdkName = null == flexSdkName ? "" : flexSdkName;
  }

  public String getHaxelibPath() {
    return haxelibPath;
  }

  public void setHaxelibPath(String haxelibPath) {
    this.haxelibPath = null == haxelibPath ? "" : haxelibPath;
  }

  public boolean getUseCompilerCompletionFlag() {
    return useCompilerCompletionFlag;
  }

  public void setUseCompilerCompletionFlag(boolean newState) {
    useCompilerCompletionFlag = newState;
  }

  public boolean getRemoveCompletionDuplicatesFlag() {
    return removeCompletionDuplicatesFlag;
  }

  public void setRemoveCompletionDuplicatesFlag(boolean newState) {
    removeCompletionDuplicatesFlag = newState;
  }
}
