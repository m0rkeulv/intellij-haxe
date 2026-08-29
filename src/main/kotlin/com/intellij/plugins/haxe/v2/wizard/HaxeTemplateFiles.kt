package com.intellij.plugins.haxe.v2.wizard

import com.intellij.ide.fileTemplates.FileTemplateManager
import com.intellij.openapi.project.Project

/**
 * Renders the project-generator files from the platform file templates in
 * {@code fileTemplates/j2ee/Haxe Project *.ft} (the "Haxe project" group under
 * Settings | File and Code Templates | Other — user-customizable). Code owns
 * the CONDITIONAL assembly (which lines a target needs, escaping); the
 * templates own the file shapes. See doc/project-templates-wizard.md.
 */
object HaxeTemplateFiles {

  const val SOURCE_DIR: String = HaxeModuleBuilder.SOURCE_DIR

  /**
   * The hxml targets the plain-project template offers, with the output flag
   * and default output each target's manual page prescribes. HashLink is TWO
   * entries because the output EXTENSION selects the compilation mode: a
   * {@code .hl} file is VM bytecode, a {@code .c} file switches the
   * generator to HL/C sources. The legacy source-generating targets carry
   * the support library the compiler requires for them.
   */
  enum class HxmlTargetOption(val label: String, private val outputFlag: String, val defaultOutput: String, val lib: String? = null) {
    HASHLINK_VM("HashLink (VM bytecode)", "--hl", "out/app.hl"),
    HASHLINK_C("HashLink (HL/C sources)", "--hl", "out/c/main.c"),
    JAVASCRIPT("JavaScript", "--js", "out/app.js"),
    NEKO("Neko", "--neko", "out/app.n"),
    CPP("C++ (hxcpp)", "--cpp", "out/cpp"),
    JVM("JVM (.jar)", "--jvm", "out/app.jar"),
    JAVA_LEGACY("Java (generated sources)", "--java", "out/java", "hxjava"),
    CSHARP("C# (generated sources)", "--cs", "out/cs", "hxcs"),
    PHP("PHP", "--php", "out/php"),
    PYTHON("Python", "--python", "out/app.py"),
    LUA("Lua", "--lua", "out/app.lua"),
    FLASH("Flash", "--swf", "out/app.swf"),
    INTERP("Eval (interpreter)", "--interp", "");

    /** Whether the target writes anything — the interpreter runs the program instead. */
    val hasOutput: Boolean get() = defaultOutput.isNotEmpty()

    fun outputLine(output: String): String = if (hasOutput) "$outputFlag $output" else outputFlag

    override fun toString(): String = label
  }

  /**
   * Everything the HXML template form collects. {@code swfHeader} arrives
   * preassembled ({@code width:height:fps:color}) — the form owns the four
   * fields, the assembly here only places the line.
   */
  data class HxmlSpec(
    val target: HxmlTargetOption,
    val mainClass: String = "Main",
    val output: String = target.defaultOutput,
    val dce: String = "std",
    val jsSourceMap: Boolean = false,
    val swfVersion: String = "",
    val swfHeader: String = "")

  fun starterMainHx(project: Project, className: String): String =
    render(project, "Haxe Project Main", mapOf("MAIN_CLASS" to className))

  fun hxml(project: Project, spec: HxmlSpec): String {
    val libLine = spec.target.lib?.let { "-lib $it\n" } ?: ""
    val extras = buildString {
      if (spec.target == HxmlTargetOption.JAVASCRIPT && spec.jsSourceMap) append("\n-D source-map")
      if (spec.target == HxmlTargetOption.FLASH) {
        if (spec.swfVersion.isNotBlank()) append("\n--swf-version ${spec.swfVersion}")
        if (spec.swfHeader.isNotBlank()) append("\n--swf-header ${spec.swfHeader}")
      }
    }
    return render(project, "Haxe Project Build", mapOf(
      "MAIN_CLASS" to spec.mainClass,
      "LIB_LINE" to libLine,
      "DCE" to spec.dce,
      "OUTPUT_LINE" to spec.target.outputLine(spec.output),
      "EXTRA_LINES" to extras))
  }

  /** project.xml for lime or openfl ("haxelib" decides which framework the file pulls in). */
  fun limeProjectXml(project: Project, haxelib: String, title: String, pkg: String, width: Int, height: Int, fps: Int): String =
    render(project, "Haxe Project Lime", mapOf(
      "TITLE" to xml(title),
      "PACKAGE" to xml(pkg),
      "FILE" to xml(fileNameOf(title)),
      "WIDTH" to width.toString(),
      "HEIGHT" to height.toString(),
      "FPS" to fps.toString(),
      "HAXELIB" to haxelib))

  /** project.nmml for NME. */
  fun nmmlProjectXml(project: Project, title: String, pkg: String, width: Int, height: Int, fps: Int): String =
    render(project, "Haxe Project NME", mapOf(
      "TITLE" to xml(title),
      "PACKAGE" to xml(pkg),
      "FILE" to xml(fileNameOf(title)),
      "WIDTH" to width.toString(),
      "HEIGHT" to height.toString(),
      "FPS" to fps.toString()))

  fun limeMainHx(project: Project): String = render(project, "Haxe Project Lime Main", emptyMap())

  fun openflMainHx(project: Project): String = render(project, "Haxe Project OpenFL Main", emptyMap())

  fun nmeMainHx(project: Project): String = render(project, "Haxe Project NME Main", emptyMap())

  /**
   * haxelib.json carrying every field the bundled schema REQUIRES (name,
   * license, releasenote, contributors, version) plus the useful optionals;
   * classPath is fixed to the template's source folder.
   */
  fun haxelibJson(project: Project,
                  name: String,
                  license: String,
                  version: String,
                  description: String,
                  url: String,
                  tags: List<String>,
                  contributors: List<String>,
                  releasenote: String): String =
    render(project, "Haxe Project Haxelib", mapOf(
      "NAME" to json(name),
      "URL" to json(url),
      "LICENSE" to json(license),
      "TAGS" to tags.joinToString(", ") { "\"${json(it)}\"" },
      "DESCRIPTION" to json(description),
      "VERSION" to json(version),
      "RELEASENOTE" to json(releasenote),
      "CONTRIBUTORS" to contributors.joinToString(", ") { "\"${json(it)}\"" }))

  /**
   * The library's development hxml: gives the IDE a build context (compiler
   * completion, diagnostics, the compilation server) without producing any
   * artifact. Libraries have no -main, so the starter class is listed as an
   * explicit compile root — a bare module name in hxml — which is what makes
   * the compiler actually type the library.
   */
  fun haxelibDevHxml(project: Project, rootClassName: String): String =
    render(project, "Haxe Project Dev", mapOf("ROOT_CLASS" to rootClassName))

  /** A starter class named after the library (capitalized, non-identifier chars stripped). */
  fun haxelibStarterClass(project: Project, libName: String): Pair<String, String> {
    val className = haxelibClassNameOf(libName)
    return className to render(project, "Haxe Project Haxelib Class", mapOf("CLASS_NAME" to className))
  }

  fun haxelibClassNameOf(libName: String): String = libName
    .filter { it.isLetterOrDigit() || it == '_' }
    .ifEmpty { "Lib" }
    .replaceFirstChar { it.uppercaseChar() }

  /**
   * Plain {@code ${VAR}} substitution over the template's raw text — the
   * project templates use no Velocity features, and skipping the engine
   * keeps hxml {@code #} comment lines literal (Velocity would parse them).
   */
  private fun render(project: Project, templateName: String, variables: Map<String, String>): String {
    var text = FileTemplateManager.getInstance(project).getJ2eeTemplate(templateName).text
    text = text.replace("\${SOURCE_DIR}", SOURCE_DIR)
    for ((key, value) in variables) {
      text = text.replace("\${$key}", value)
    }
    return text
  }

  private fun fileNameOf(title: String): String =
    title.filter { it.isLetterOrDigit() || it == '_' || it == '-' }.ifEmpty { "App" }

  private fun xml(value: String): String = value
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")

  private fun json(value: String): String = value
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")
}
