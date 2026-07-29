package com.intellij.plugins.haxe.v2.wizard

import com.intellij.ide.highlighter.ModuleFileType
import com.intellij.ide.wizard.AbstractNewProjectWizardStep
import com.intellij.ide.wizard.NewProjectWizardBaseData.Companion.baseData
import com.intellij.ide.wizard.NewProjectWizardStep
import com.intellij.ide.wizard.language.LanguageGeneratorNewProjectWizard
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.plugins.haxe.config.sdk.HaxeSdkType
import com.intellij.plugins.haxe.v2.toolwindow.HaxeActiveBuildFileStore
import icons.HaxeIcons
import java.io.IOException
import javax.swing.Icon

/**
 * "Haxe" entry in the New Project / New Module wizard (v2). Creates a plain
 * general-type module with a src root, a starter Main.hx and a build.hxml that is
 * registered as the module's active build file.
 */
class HaxeNewProjectWizard : LanguageGeneratorNewProjectWizard {

  override val name: String = "Haxe"

  override val icon: Icon
    get() = HaxeIcons.HAXE_LOGO

  override val ordinal: Int = 600

  override fun createStep(parent: NewProjectWizardStep): NewProjectWizardStep = Step(parent)

  private class Step(parent: NewProjectWizardStep) : AbstractNewProjectWizardStep(parent) {

    override fun setupProject(project: Project) {
      val base = baseData ?: return
      val contentRoot = "${base.path}/${base.name}"

      HaxeSdkType.getInstance().ensureSdk()

      val builder = HaxeModuleBuilder()
      builder.name = base.name
      builder.contentEntryPath = contentRoot
      builder.moduleFilePath = "$contentRoot/${base.name}${ModuleFileType.DOT_DEFAULT_EXTENSION}"

      val module = builder.commit(project).firstOrNull() ?: return
      generateStarterFiles(project, contentRoot)
    }

    private fun generateStarterFiles(project: Project, contentRoot: String) {
      WriteAction.runAndWait<IOException> {
        val root = VfsUtil.createDirectoryIfMissing(contentRoot) ?: return@runAndWait
        val sourceDir = VfsUtil.createDirectoryIfMissing(root, HaxeModuleBuilder.SOURCE_DIR)

        val mainHx = sourceDir.findOrCreateChildData(this, "Main.hx")
        VfsUtil.saveText(mainHx, MAIN_HX)

        val buildFile = root.findOrCreateChildData(this, "build.hxml")
        VfsUtil.saveText(buildFile, BUILD_HXML)
        // Only claim the project-wide active slot when nothing else holds it.
        val activeStore = HaxeActiveBuildFileStore.getInstance(project)
        if (activeStore.activeFilePath == null) {
          activeStore.setActiveFile(buildFile.path)
        }
      }
    }
  }

  private companion object {
    val BUILD_HXML = """
      -cp ${HaxeModuleBuilder.SOURCE_DIR}
      -main Main
      --interp
    """.trimIndent() + "\n"

    val MAIN_HX = """
      class Main {
          static function main() {
              trace("Hello, Haxe!");
          }
      }
    """.trimIndent() + "\n"
  }
}
