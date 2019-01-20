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
import com.intellij.openapi.command.undo.UndoManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class As3SearchReplaceAction extends AnAction implements Runnable {

  private Document document;

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
      //editor.getCaretModel().getPrimaryCaret().getSelectionStart()
      CommandProcessor.getInstance().runUndoTransparentAction(this);
    }
  }

  @Override
  public void run() {
    String text = document.getText();
    text = simpleReplacements(text);
    text = fixPackageBrackets(text);
    text = fixSpesialCases(text);
    text = fixMultiImplements(text);
    text = fixMultiExtends(text);
    //experimental
    //--------------------------------
    text = fixForEach(text);
    text = fixCasting(text);
    text = fixIsInstance(text);
    text = fixConstructors(text);

    // My custom rules /
    text = fixLogCallAndWarn(text);
    text = fixLogRemoveThis(text);
    text = fixCatchErrors(text);

    // logger waring -> warn
    // logger call -> debug

    // logger remove this:
      //LOGGER.warn(this

    // fix for in loops:
      //for (var i:String in _animations) {
      //to
    // for (i in _animations) {

    //
    // error fixing
    //  catch (e:Error) {
    // add wrapper here

    //
    try {
      document.replaceString(0, document.getTextLength(), text);
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


  private static String fixMultiImplements(String multiLine) {
    //MULTIPLE IMPLEMENTS;
    String regex = "(?m)((\\s+)implements(\\s+)([\\w\\._]+)*)(,)";
    Pattern compile = Pattern.compile(regex);
    while (compile.matcher(multiLine).find()) {
      multiLine = multiLine.replaceFirst(regex, "$1 implements ");
    }
    return multiLine;
  }

  private static String fixMultiExtends(String multiLine) {
    //MULTIPLE EXTENDS;
    String regex = "(?m)((\\s+)extends(\\s+)([\\w\\._]+)*)(,)";
    Pattern compile = Pattern.compile(regex);
    while (compile.matcher(multiLine).find()) {
      multiLine = multiLine.replaceFirst(regex, "$1 extends");
    }
    return multiLine;
  }

  private static String fixSpesialCases(String multiLine) {
    //GetTimer
    multiLine = multiLine.replaceAll("import (flash|openfl).utils.getTimer([\\s;])*", "");
    multiLine = multiLine.replaceAll("getTimer\\(\\)", "Lib.getTimer()");

    return multiLine;
  }

  private static String fixPackageBrackets(String multiLine) {
    //PACKAGE STUFF
    //(?m) = multi line
    //(?s) = dotall
    if (Pattern.compile("(?m)package([\\s\\w\\.]*)(\\s)\\{").matcher(multiLine).find()) {
      multiLine = multiLine.replaceFirst("(?m)package([\\s\\w\\.]*)(\\s)\\{", "package $1;");
      multiLine = replaceLast(multiLine, "(?m)}(\\s)*$", ""); // replace last {
    }
    return multiLine;
  }

  private static String fixConstructors(String lines) {
    String multiLine = "(?m)";
    Matcher matcher = Pattern.compile("(?m)class(\\s)+(?<className>(\\w)+)").matcher(lines);
    if (matcher.find()) {
      String className = matcher.group("className");
      lines = lines.replaceAll(multiLine + "((((private|internal|protected|public|static)(\\s+)?)*)function(\\s+))"+className+"(\\s+|\\()", "$1new$7");
    }
    return lines;
  }

  private static String fixLogCallAndWarn(String lines) {
    String multiLineIgnoreCase = "(?m)(?i)";
    lines = lines.replaceAll(multiLineIgnoreCase + "(logger|log)\\.call\\(", "$1.debug(");
    lines = lines.replaceAll(multiLineIgnoreCase + "(logger|log)\\.warning\\(", "$1.warn(");
    return lines;
  }
  private static String fixLogRemoveThis(String lines) {
    String multiLineIgnoreCase = "(?m)(?i)";
    lines = lines.replaceAll(multiLineIgnoreCase + "((logger|log)\\.(debug|info|warn|error|fatal)\\()this,\\s*", "$1");
    return lines;
  }
  private static String fixCatchErrors(String lines) {
    String multiLineIgnoreCase = "(?m)(?i)";
    lines = lines.replaceAll(multiLineIgnoreCase + "(((\\s)*)catch(\\s)*\\((\\w+))(([:\\w]*))(\\)(\\s*)\\{)", "$1:Any$8$2 var $5w = new ExceptionWrapper($5);");


    return lines;
  }

  private static String fixForEach(String lines) {
    String multiLineIgnoreCase = "(?m)(?i)";
    lines = lines.replaceAll(multiLineIgnoreCase + "for\\s+each\\s*\\(\\s*var\\s+([\\w\\.\\(\\)\\[\\]\\\"]*)(\\:([\\w\\*]*))?\\s*in\\s+([\\w\\.\\(\\)\\[\\]\\\"]+)","for($1 in $4");
    return lines;
  }
  
  private static String fixCasting(String lines) {
    String multiLineIgnoreCase = "(?m)(?i)";
    lines = lines.replaceAll(multiLineIgnoreCase + "([\\w\\.\\(\\)\\[\\]\\\"]+)\\s+as\\s+(\\w*)","cast($1, $2)");
    return lines;
  }

  private static String fixIsInstance(String lines) {
    String multiLineIgnoreCase = "(?m)(?i)";
    lines = lines.replaceAll(multiLineIgnoreCase + "([\\w\\.\\(\\)\\[\\]\\\"]+)\\s+is\\s+(\\w*)","Std.is($1, $2)");
    return lines;
  }


  private static String simpleReplacements(String line) {
    String multiLine = "(?m)";
    String varTypeDeclarationEnd = "([^\\w])";
    //TYPES
    line = line.replaceAll(multiLine + ":\\*" + varTypeDeclarationEnd, ":Any$1");
    line = line.replaceAll(multiLine + ":int" + varTypeDeclarationEnd, ":Int$1");
    line = line.replaceAll(multiLine + ":uint" + varTypeDeclarationEnd, ":UInt$1");
    line = line.replaceAll(multiLine + ":void" + varTypeDeclarationEnd, ":Void$1");
    line = line.replaceAll(multiLine + ":Number" + varTypeDeclarationEnd, ":Float$1");
    line = line.replaceAll(multiLine + ":Boolean" + varTypeDeclarationEnd, ":Bool$1");
    line = line.replaceAll(multiLine + ":Vector.<int>" + varTypeDeclarationEnd, ":Vector<Int>$1");
    line = line.replaceAll(multiLine + ":Vector.<number>" + varTypeDeclarationEnd, ":Vector<Float>$1");
    line = line.replaceAll(multiLine + ":Vector.<Boolean>" + varTypeDeclarationEnd, ":Vector<Bool>$1");
    line = line.replaceAll(multiLine + ":Vector.<(\\w+)>", ":Vector<$1>");
    line = line.replaceAll(multiLine + ">\\[\\]", ">()");

    line = line.replaceAll(multiLine + ":Dictionary" + varTypeDeclarationEnd, ":Map<>$1");

    // VARIABLE INITS
    line = line.replaceAll(multiLine + "new\\s*(Vector.)?<int>" + varTypeDeclarationEnd, "new Vector<Int>$2");
    line = line.replaceAll(multiLine + "new\\s*(Vector.)?<number>" + varTypeDeclarationEnd, "new Vector<Float>$2");
    line = line.replaceAll(multiLine + "new\\s*(Vector.)?<Boolean>" + varTypeDeclarationEnd, "new Vector<Bool>$2");
    line = line.replaceAll(multiLine + "new\\s*(Vector.)?<(\\w+)>" + varTypeDeclarationEnd, "new Vector<$2>$3");

    //IMPORTS
    line = line.replaceAll(multiLine + "import flash\\.", "import openfl.");

    // GETTER SETTER METHODES
    line = line.replaceAll(multiLine + "function(\\s+)get(\\s+)", "function get_");
    line = line.replaceAll(multiLine + "function(\\s+)set(\\s+)", "function set_");

    //ACCESS MODIFIERS
    //https://haxe.org/manual/class-field-visibility.html
    line = line.replaceAll("public(\\s+)(class|interface)", "$2");
    //Haxe has no notion of a protected keyword ...
    // ... its private behavior is equal to those language's protected behavior
    line = line.replaceAll(multiLine + "protected(\\s+)(final|const|var|static|class|function)", "private $2");
    // replace const with final
    line = line.replaceAll(multiLine + "(((private|internal|protected|public|static)(\\s+)?)*)const(\\s+)", "$1final$5");


    return line;
  }

  private static String replaceLast(String text, String regex, String replacement) {
    return text.replaceFirst("(?s)" + regex + "(?!.*?" + regex + ")", replacement);
  }
}
