package com.intellij.plugins.haxe.v2.wizard

import com.intellij.ide.highlighter.ModuleFileType
import com.intellij.ide.wizard.AbstractNewProjectWizardStep
import com.intellij.ide.wizard.NewProjectWizardBaseData.Companion.baseData
import com.intellij.ide.wizard.NewProjectWizardStep
import com.intellij.ide.wizard.NewProjectWizardStep.Companion.ADD_SAMPLE_CODE_PROPERTY_NAME
import com.intellij.ide.wizard.language.LanguageGeneratorNewProjectWizard
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.observable.util.bindBooleanStorage
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.plugins.haxe.HaxeBundle
import com.intellij.plugins.haxe.config.sdk.HaxeSdkType
import com.intellij.plugins.haxe.v2.toolwindow.HaxeActiveBuildFileStore
import com.intellij.ui.UIBundle
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.bindSelected
import icons.HaxeIcons
import java.io.IOException
import javax.swing.Icon

/**
 * "Haxe" entry in the New Project / New Module wizard (v2). Creates a plain
 * general-type module with a src root; with "Add sample code" checked (the
 * default) also a starter Main.hx and a build.hxml that is registered as the
 * module's active build file.
 */
class HaxeNewProjectWizard : LanguageGeneratorNewProjectWizard {

  override val name: String = "Haxe"

  override val icon: Icon
    get() = HaxeIcons.HAXE_LOGO

  override val ordinal: Int = 600

  override fun createStep(parent: NewProjectWizardStep): NewProjectWizardStep = Step(parent)

  private class Step(parent: NewProjectWizardStep) : AbstractNewProjectWizardStep(parent) {

    // the platform-wide persisted "Add sample code" toggle other wizards share
    private val addSampleCodeProperty = propertyGraph.property(true)
      .bindBooleanStorage(ADD_SAMPLE_CODE_PROPERTY_NAME)

    override fun setupUI(builder: Panel) {
      builder.row {
        checkBox(UIBundle.message("label.project.wizard.new.project.add.sample.code"))
          .bindSelected(addSampleCodeProperty)
          .comment(HaxeBundle.message("haxe.wizard.add.sample.comment"))
      }
    }

    override fun setupProject(project: Project) {
      val base = baseData ?: return
      val contentRoot = "${base.path}/${base.name}"

      HaxeSdkType.getInstance().ensureSdk()

      val builder = HaxeModuleBuilder()
      builder.name = base.name
      builder.contentEntryPath = contentRoot
      builder.moduleFilePath = "$contentRoot/${base.name}${ModuleFileType.DOT_DEFAULT_EXTENSION}"

      builder.commit(project).firstOrNull() ?: return
      if (addSampleCodeProperty.get()) {
        generateStarterFiles(project, contentRoot)
      }
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
