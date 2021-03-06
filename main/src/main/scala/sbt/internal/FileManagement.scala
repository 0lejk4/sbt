/*
 * sbt
 * Copyright 2011 - 2018, Lightbend, Inc.
 * Copyright 2008 - 2010, Mark Harrah
 * Licensed under Apache License 2.0 (see LICENSE)
 */

package sbt.internal

import java.io.IOException
import java.nio.file.Path

import sbt.BasicCommandStrings.ContinuousExecutePrefix
import sbt.Keys._
import sbt._
import sbt.io.FileTreeDataView.Entry
import sbt.io.syntax.File
import sbt.io.{ FileFilter, FileTreeDataView, FileTreeRepository }

private[sbt] object FileManagement {
  private[sbt] def defaultFileTreeView: Def.Initialize[Task[FileTreeViewConfig]] = Def.task {
    val remaining = state.value.remainingCommands.map(_.commandLine.trim)
    // If the session is interactive or if the commands include a continuous build, then use
    // the default configuration. Otherwise, use the sbt1_2_compat config, which does not cache
    // anything, which makes it less likely to cause issues with CI.
    val interactive = remaining.contains("shell") || remaining.lastOption.contains("iflast shell")
    val scripted = remaining.contains("setUpScripted")

    val continuous = remaining.lastOption.exists(_.startsWith(ContinuousExecutePrefix))
    if (!scripted && (interactive || continuous)) {
      FileTreeViewConfig
        .default(watchAntiEntropy.value, pollInterval.value, pollingDirectories.value)
    } else FileTreeViewConfig.sbt1_2_compat(pollInterval.value, watchAntiEntropy.value)
  }
  private[sbt] implicit class FileTreeDataViewOps[+T](val fileTreeDataView: FileTreeDataView[T]) {
    def register(path: Path, maxDepth: Int): Either[IOException, Boolean] = {
      fileTreeDataView match {
        case r: FileTreeRepository[T] => r.register(path, maxDepth)
        case _                        => Right(false)
      }
    }
  }

  private[sbt] def collectFiles(
      dirs: ScopedTaskable[Seq[File]],
      filter: ScopedTaskable[FileFilter],
      excludes: ScopedTaskable[FileFilter]
  ): Def.Initialize[Task[Seq[File]]] =
    Def.task {
      val sourceDirs = dirs.toTask.value
      val view = fileTreeView.value
      val include = filter.toTask.value
      val ex = excludes.toTask.value
      val sourceFilter: Entry[FileCacheEntry] => Boolean = (entry: Entry[FileCacheEntry]) => {
        val typedPath = entry.typedPath
        val file = new java.io.File(typedPath.toPath.toString) {
          override def isDirectory: Boolean = typedPath.isDirectory
          override def isFile: Boolean = typedPath.isFile
        }
        include.accept(file) && !ex.accept(file)
      }
      sourceDirs.flatMap { dir =>
        view.register(dir.toPath, maxDepth = Integer.MAX_VALUE)
        view
          .listEntries(dir.toPath, maxDepth = Integer.MAX_VALUE, sourceFilter)
          .flatMap(e => e.value.toOption.map(Stamped.file(e.typedPath, _)))
      }
    }

  private[sbt] def appendBaseSources: Seq[Def.Setting[Task[Seq[File]]]] = Seq(
    unmanagedSources := {
      val sources = unmanagedSources.value
      val f = (includeFilter in unmanagedSources).value
      val excl = (excludeFilter in unmanagedSources).value
      val baseDir = baseDirectory.value
      val view = fileTreeView.value
      if (sourcesInBase.value) {
        view.register(baseDir.toPath, maxDepth = 0)
        sources ++
          view
            .listEntries(
              baseDir.toPath,
              maxDepth = 0,
              e => {
                val tp = e.typedPath
                /*
                 * The TypedPath has the isDirectory and isFile properties embedded. By overriding
                 * these methods in java.io.File, FileFilters may be applied without needing to
                 * stat the file (which is expensive) for isDirectory and isFile checks.
                 */
                val file = new java.io.File(tp.toPath.toString) {
                  override def isDirectory: Boolean = tp.isDirectory
                  override def isFile: Boolean = tp.isFile
                }
                f.accept(file) && !excl.accept(file)
              }
            )
            .flatMap(e => e.value.toOption.map(Stamped.file(e.typedPath, _)))
      } else sources
    }
  )
}
