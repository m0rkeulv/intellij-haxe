package com.intellij.plugins.haxe.v2.buildtools

import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.backend.workspace.WorkspaceModel
import com.intellij.platform.workspace.jps.entities.ContentRootEntity
import com.intellij.platform.workspace.jps.entities.ExcludeUrlEntity
import com.intellij.platform.workspace.jps.entities.ModuleEntity
import com.intellij.platform.workspace.jps.entities.SourceRootEntity
import com.intellij.platform.workspace.jps.entities.SourceRootTypeId
import com.intellij.platform.workspace.jps.entities.modifyContentRootEntity
import com.intellij.plugins.haxe.v2.buildsystem.*
import java.io.File

/**
 * First-open setup for plain modules that contain haxe build files but have no
 * source roots configured yet (a folder opened directly, without a wizard):
 * the build files' classpaths become source roots and their output locations
 * become excluded folders. Modules that already have source roots are left
 * alone — the user's (or the migration's) configuration wins.
 */
object HaxeSourceRootsInitializer {

  // matches how the platform serializes java-like source roots; the v2 wizard's src/ uses the same
  private val SOURCE_ROOT_TYPE = SourceRootTypeId("java-source")

  suspend fun initialize(project: Project) {
    val plans = smartReadAction(project) { plan(project) }
    if (plans.isEmpty()) return
    val workspaceModel = WorkspaceModel.getInstance(project)
    val urlManager = workspaceModel.getVirtualFileUrlManager()
    workspaceModel.update("Haxe initial source roots") { builder ->
      for (plan in plans) {
        val module = builder.entities(ModuleEntity::class.java).firstOrNull { it.name == plan.moduleName } ?: continue
        for (contentRoot in module.contentRoots) {
          applyToContentRoot(builder, contentRoot, plan, urlManager)
        }
      }
    }
  }

  private data class RootsPlan(val moduleName: String, val sourceDirs: List<String>, val excludeDirs: List<String>)

  private fun plan(project: Project): List<RootsPlan> {
    val plans = mutableListOf<RootsPlan>()
    for (module in ModuleManager.getInstance(project).modules) {
      val snapshot = WorkspaceModel.getInstance(project).currentSnapshot
      val entity = snapshot.entities(ModuleEntity::class.java).firstOrNull { it.name == module.name } ?: continue
      if (entity.contentRoots.any { it.sourceRoots.isNotEmpty() }) continue

      val buildFiles = HaxeBuildFileScanner.scan(module)
      if (buildFiles.isEmpty()) continue

      val sourceDirs = linkedSetOf<String>()
      val excludeDirs = linkedSetOf<String>()
      for (buildFile in buildFiles) {
        val directory = buildFile.file().parent ?: continue
        val info = HaxeBuildFileInspector.inspect(buildFile)
        for (classpath in info.classpaths()) {
          resolveDirectory(directory, classpath)?.let { sourceDirs += it }
        }
        when (buildFile.type()) {
          HaxeBuildFileType.HXML ->
            info.targetOutput()?.let { output ->
              // the output FILE's directory, whether or not it exists yet
              val parent = File(FileUtil.toSystemIndependentName(output)).parent
              excludeDirs += resolvePath(directory, parent ?: output)
            }
          HaxeBuildFileType.OPENFL, HaxeBuildFileType.LIME, HaxeBuildFileType.NMML ->
            ProjectXmlParser.parseAppPath(VfsUtilCore.loadText(buildFile.file()))?.let {
              excludeDirs += resolvePath(directory, it)
            }
          else -> {}
        }
      }
      if (sourceDirs.isNotEmpty() || excludeDirs.isNotEmpty()) {
        plans += RootsPlan(module.name, sourceDirs.toList(), excludeDirs.toList())
      }
    }
    return plans
  }

  /** An existing directory resolved against the build file's directory, or null. */
  private fun resolveDirectory(base: VirtualFile, relative: String): String? {
    val resolved = base.findFileByRelativePath(FileUtil.toSystemIndependentName(relative.trim()))
    return if (resolved != null && resolved.isDirectory) resolved.path else null
  }

  /** A path resolved against the build file's directory; output dirs may not exist yet. */
  private fun resolvePath(base: VirtualFile, relative: String): String {
    val clean = FileUtil.toSystemIndependentName(relative.trim())
    return if (FileUtil.isAbsolute(clean)) clean else base.path + "/" + clean
  }

  private fun applyToContentRoot(
    builder: com.intellij.platform.workspace.storage.MutableEntityStorage,
    contentRoot: ContentRootEntity,
    plan: RootsPlan,
    urlManager: com.intellij.platform.workspace.storage.url.VirtualFileUrlManager,
  ) {
    val rootPath = contentRoot.url.url.removePrefix("file://")
    val existingSources = contentRoot.sourceRoots.map { it.url.url }.toSet()
    val existingExcludes = contentRoot.excludedUrls.map { it.url.url }.toSet()
    val source = contentRoot.entitySource

    val newSources = plan.sourceDirs
      .filter { FileUtil.isAncestor(rootPath, it, false) }
      .map { urlManager.getOrCreateFromUrl(VfsUtilCore.pathToUrl(it)) }
      .filter { it.url !in existingSources }
    val newExcludes = plan.excludeDirs
      .filter { FileUtil.isAncestor(rootPath, it, true) }
      .map { urlManager.getOrCreateFromUrl(VfsUtilCore.pathToUrl(it)) }
      .filter { it.url !in existingExcludes }
    if (newSources.isEmpty() && newExcludes.isEmpty()) return

    builder.modifyContentRootEntity(contentRoot) {
      sourceRoots = sourceRoots + newSources.map { SourceRootEntity(it, SOURCE_ROOT_TYPE, source) }
      excludedUrls = excludedUrls + newExcludes.map { ExcludeUrlEntity(it, source) }
    }
  }
}
