package com.intellij.plugins.haxe.v2

import com.intellij.openapi.externalSystem.autoimport.ExternalSystemProjectTracker
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.plugins.haxe.v2.buildtools.HaxeBuildFilesProjectAware

/**
 * Registers the v2 build file watcher so changed hxml/project.xml files show the
 * platform's floating "reload" icon, wired to the v2 sync pipeline.
 */
class HaxeV2ProjectActivity : ProjectActivity {

  override suspend fun execute(project: Project) {
    val projectAware = HaxeBuildFilesProjectAware(project)
    val tracker = ExternalSystemProjectTracker.getInstance(project)
    tracker.register(projectAware)
    tracker.activate(projectAware.projectId)
  }
}
