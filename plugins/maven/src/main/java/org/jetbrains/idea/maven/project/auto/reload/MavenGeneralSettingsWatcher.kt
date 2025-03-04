// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.idea.maven.project.auto.reload

import com.intellij.openapi.Disposable
import com.intellij.openapi.externalSystem.autoimport.changes.AsyncFilesChangesListener.Companion.subscribeOnVirtualFilesChanges
import com.intellij.openapi.externalSystem.autoimport.changes.FilesChangesListener
import com.intellij.openapi.externalSystem.autoimport.settings.ReadAsyncSupplier
import com.intellij.openapi.util.io.toCanonicalPath
import kotlinx.coroutines.launch
import org.jetbrains.annotations.ApiStatus
import org.jetbrains.idea.maven.buildtool.MavenImportSpec
import org.jetbrains.idea.maven.project.MavenProjectsManager
import org.jetbrains.idea.maven.server.MavenDistributionsCache
import org.jetbrains.idea.maven.utils.MavenCoroutineScopeProvider
import org.jetbrains.idea.maven.utils.MavenUtil
import java.util.concurrent.ExecutorService

@ApiStatus.Internal
class MavenGeneralSettingsWatcher(
  private val manager: MavenProjectsManager,
  private val backgroundExecutor: ExecutorService
) {

  private val generalSettings get() = manager.generalSettings
  private val embeddersManager get() = manager.embeddersManager

  private fun collectSettingsFiles(): Set<String> {
    val result = LinkedHashSet<String>()
    val userSettingsFile = MavenUtil.resolveUserSettingsFile(generalSettings.userSettingsFile)
    result.add(userSettingsFile.toPath().toCanonicalPath())
    return result
  }

  private fun fireSettingsChange() {
    embeddersManager.reset()
    val project = manager.project
    MavenDistributionsCache.getInstance(project).cleanCaches()
    val cs = MavenCoroutineScopeProvider.getCoroutineScope(project)
    cs.launch {
      manager.updateAllMavenProjects(MavenImportSpec.IMPLICIT_IMPORT)
    }
  }

  private fun fireSettingsXmlChange() {
    generalSettings.changed()
    // fireSettingsChange() will be called indirectly by pathsChanged listener on GeneralSettings object
  }

  fun subscribeOnSettingsChanges(parentDisposable: Disposable) {
    generalSettings.addListener(::fireSettingsChange, parentDisposable)
  }

  fun subscribeOnSettingsFileChanges(parentDisposable: Disposable) {
    val filesProvider = ReadAsyncSupplier.Builder(::collectSettingsFiles)
      .coalesceBy(this)
      .build(backgroundExecutor)
    subscribeOnVirtualFilesChanges(false, filesProvider, object : FilesChangesListener {
      override fun apply() = fireSettingsXmlChange()
    }, parentDisposable)
  }
}
