package com.intellij.plugins.haxe.v2.wizard

import com.intellij.ide.wizard.AbstractNewProjectWizardStep
import com.intellij.ide.wizard.NewProjectWizardStep
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.roots.ui.configuration.SdkComboBox
import com.intellij.openapi.roots.ui.configuration.SdkComboBoxModel
import com.intellij.openapi.roots.ui.configuration.projectRoot.ProjectSdksModel
import com.intellij.plugins.haxe.HaxeWizardBundle
import com.intellij.plugins.haxe.config.sdk.HaxeSdkType
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Panel

/**
 * The Haxe SDK selector, mirroring what other generator pages offer. The
 * combo lists configured Haxe SDKs (with the platform's built-in
 * "Add SDK..." affordances); the choice becomes the PROJECT SDK on create.
 * Left empty, the existing {@code ensureSdk} auto-detection still applies.
 */
class HaxeProjectSdkStep(parent: NewProjectWizardStep) : AbstractNewProjectWizardStep(parent) {

  private val sdksModel = ProjectSdksModel()
  private var comboBox: SdkComboBox? = null

  override fun setupUI(builder: Panel) {
    val project = context.project ?: ProjectManager.getInstance().defaultProject
    sdksModel.reset(project)
    val model = SdkComboBoxModel.createSdkComboBoxModel(project, sdksModel, { it is HaxeSdkType }, { it is HaxeSdkType })
    val combo = SdkComboBox(model)
    comboBox = combo
    builder.row(HaxeWizardBundle.message("haxe.wizard.sdk.label")) {
      cell(combo).align(AlignX.FILL)
    }
  }

  override fun setupProject(project: Project) {
    val selected = comboBox?.getSelectedSdk() ?: return
    runCatching { sdksModel.apply() }
    WriteAction.runAndWait<RuntimeException> {
      ProjectRootManager.getInstance(project).projectSdk = selected
    }
  }
}
