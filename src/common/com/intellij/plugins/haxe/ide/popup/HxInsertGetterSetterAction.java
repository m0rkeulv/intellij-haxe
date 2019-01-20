/*
 * Copyright 2000-2013 JetBrains s.r.o.
 * Copyright 2014-2018 AS3Boyan
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
package com.intellij.plugins.haxe.ide.popup;

import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.command.CommandProcessor;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class HxInsertGetterSetterAction extends AnAction implements Runnable {

  private Document document;
  private int caretPositionStart;
  private int caretPositionEnd;

  @Override
  public void update(AnActionEvent e) {
    super.update(e);
    Presentation p = e.getPresentation();
    p.setEnabled(isAvailable(e));
    p.setVisible(isVisible(e));
  }

  @Override
  public void actionPerformed(AnActionEvent e) {
    DataContext context = e.getDataContext();
    Editor editor = CommonDataKeys.EDITOR.getData(context);
    if (editor != null) {
      document = editor.getDocument();
      caretPositionStart = editor.getCaretModel().getPrimaryCaret().getSelectionStart();
      caretPositionEnd = editor.getCaretModel().getPrimaryCaret().getSelectionEnd();
      CommandProcessor.getInstance().runUndoTransparentAction(this);
    }
  }

  @Override
  public void run() {
    String text = document.getText();
   String getterSetters = generateGetterSetters(text);

    try {
      document.replaceString(caretPositionStart, caretPositionEnd, getterSetters);
    }
    catch (Exception ex) {
      int i = 0;
    }
  }

  protected boolean isAvailable(AnActionEvent e) {
    DataContext context = e.getDataContext();
    Editor editor = CommonDataKeys.EDITOR.getData(context);
    return editor != null;
  }

  protected boolean isVisible(AnActionEvent e) {
    return true;
  }


  private static String generateGetterSetters(String multiLine) {
    Map<String,String> getMap = new HashMap<>();
    Map<String,String> setMap = new HashMap<>();
    Map<String,String> typeMap = new HashMap<>();
//        List<String> lines = new ArrayList<>();
    StringJoiner lines = new StringJoiner("\n");
    String regex = "(?m)((?<type>private|internal|protected|public|)|(static))(\\s+)function(\\s+)get_(?<varName>\\w+)(\\s*)\\([:\\w\\s<>]*\\)(\\s*)(:(?<varType>[\\w<>]*))?";
    Pattern compile = Pattern.compile(regex);
    Matcher matcher = compile.matcher(multiLine);
    while (matcher.find()) {
      getMap.put(matcher.group("varName"), matcher.group("type") );
      if (matcher.group("varType")!= null) {
        typeMap.put(matcher.group("varName"), matcher.group("varType") );
      }
    }
    regex = "(?m)((?<type>private|internal|protected|public|)|(static))(\\s+)function(\\s+)set_(?<varName>\\w+)(\\s*)\\([\\w-_]*:(?<varType>[\\w\\s<>]*)\\)(\\s*)(:([\\w<>]*))?";
    compile = Pattern.compile(regex);
    matcher = compile.matcher(multiLine);
    while (matcher.find()) {
      setMap.put(matcher.group("varName"), matcher.group("type") );
      if (matcher.group("varType")!= null && !typeMap.containsKey(matcher.group("varName")) ) {
        typeMap.put(matcher.group("varName"), matcher.group("varType") );
      }
    }

    Set<Map.Entry<String, String>> allSet = new HashSet<>();
    allSet.addAll(getMap.entrySet());
    allSet.addAll(setMap.entrySet());


    for( Map.Entry<String,String> entry : allSet) {
      String setVal = "null";
      String getVal = "null";

      if (getMap.containsKey(entry.getKey())) {
        if (entry.getValue().matches("private|internal|protected")) {
          getVal = "default";
        } else {
          getVal = "get";
        }
      }

      if (setMap.containsKey(entry.getKey())) {
        if (setMap.get(entry.getKey()).matches("private|internal|protected")) {
          setVal = "default";
        } else {
          setVal = "set";
        }

      }
      String varType = typeMap.containsKey(entry.getKey()) ? typeMap.get(entry.getKey()) : "?";
      String line = "  public var " + entry.getKey() + "(" + getVal + ", " + setVal + "):" + varType + ";";
      lines.add(line);
    }

    return lines.toString();
  }
}
