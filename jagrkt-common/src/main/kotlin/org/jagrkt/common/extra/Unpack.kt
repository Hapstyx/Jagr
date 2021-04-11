/*
 *   JagrKt - JagrKt.org
 *   Copyright (C) 2021 Alexander Staeding
 *   Copyright (C) 2021 Contributors
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU Lesser General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU Lesser General Public License for more details.
 *
 *     You should have received a copy of the GNU Lesser General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.jagrkt.common.extra

import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jagrkt.common.Config
import org.jagrkt.common.testing.SubmissionInfoImpl
import org.slf4j.Logger
import java.io.File
import java.nio.file.FileSystems
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.bufferedReader
import kotlin.io.path.bufferedWriter

abstract class Unpack : Extra {

  protected abstract val config: Config
  protected abstract val logger: Logger

  @OptIn(ExperimentalPathApi::class)
  private fun SubmissionInfoVerification.verify() {
    if (assignmentId == null && studentId == null && firstName == null && lastName == null) return
    FileSystems.newFileSystem(file.toPath(), null).use { fs ->
      val submissionInfoPath = fs.getPath("submission-info.json")
      val replacedSubmissionInfo = submissionInfoPath.bufferedReader().use { reader ->
        val submissionInfo = Json.decodeFromString<SubmissionInfoImpl>(reader.readText())
        val replaceAssignmentId = assignmentId != null && assignmentId != submissionInfo.assignmentId
        val replaceStudentId = studentId != null && studentId != submissionInfo.studentId
        val replaceFirstName = firstName != null && firstName != submissionInfo.firstName
        val replaceLastName = lastName != null && lastName != submissionInfo.lastName
        if (replaceAssignmentId || replaceStudentId || replaceFirstName || replaceLastName) {
          logger.warn(
            "$submissionInfo has incorrect submission-info! Replacing:"
              + if (replaceAssignmentId) " assignmentId(${submissionInfo.assignmentId} -> $assignmentId)" else ""
              + if (replaceStudentId) " studentId(${submissionInfo.studentId} -> $studentId)" else ""
              + if (replaceFirstName) " firstName(${submissionInfo.firstName} -> $firstName)" else ""
              + if (replaceLastName) " lastName(${submissionInfo.lastName} -> $lastName)" else ""
          )
          SubmissionInfoImpl(
            if (replaceAssignmentId) assignmentId!! else submissionInfo.assignmentId,
            if (replaceStudentId) studentId!! else submissionInfo.studentId,
            if (replaceFirstName) firstName!! else submissionInfo.firstName,
            if (replaceLastName) lastName!! else submissionInfo.lastName,
          )
        } else null
      } ?: return
      submissionInfoPath.bufferedWriter().use { writer ->
        writer.write(Json.encodeToString(replacedSubmissionInfo))
      }
    }
  }

  fun List<SubmissionInfoVerification>.verify() {
    runBlocking {
      with(GlobalScope) {
        asSequence().map {
          async { it.verify() }
        }
      }.forEach { it.await() }
    }
  }

  data class SubmissionInfoVerification(
    val file: File,
    val assignmentId: String? = null,
    val studentId: String? = null,
    val firstName: String? = null,
    val lastName: String? = null,
  )
}
