/**
 * Copyright 2013-2014 PayPal
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
package com.paypal.horizon

import sbtrelease.ReleaseStep
import sbtrelease.Utilities._
import sbt._
import sbt.Keys._
import com.typesafe.sbt.SbtGhPages.GhPagesKeys._
import java.io.PrintWriter
import com.typesafe.sbt.SbtSite.SiteKeys._
import scala.io.Source
import sbtrelease.ReleasePlugin.ReleaseKeys._
import java.text.SimpleDateFormat
import java.util.Calendar
import com.typesafe.sbt.pgp.PgpKeys._

/**
 * Common methods used amongst custom release step implementations.
 */
trait CommonContext {

  def getReleasedVersion(st: State): String = st.get(versions).getOrElse(
    sys.error("No versions are set! Was this release part executed before inquireVersions?"))._1

  def executeTask(task: TaskKey[_], info: String): State => State = (st: State) => {
    st.log.info(info)
    val extracted = st.extract
    val ref: ProjectRef = extracted.get(thisProjectRef)
    val (newState, _) = extracted.runTask(task in ref, st)
    newState
  }

}

/**
 * Adds [[https://github.com/sbt/sbt-release sbtrelease]] steps for checking and updating Changelog.
 *
 * Changelog file is called `CHANGELOG.md` by default, can be overridden using the `changelog` setting in CustomReleaseStepsKeys.
 *
 * Import if defining custom release process and need access to changelog steps.
 */
object ChangelogReleaseSteps extends CommonContext {
  import BuildUtilitiesKeys._

  private case class ChangelogInfo(msg: String, author: String)

  private val ChangelogInfoMissingMessage = "You must provide a changelog message and author"
  private val ChangelogUpdateMessage = "There was an error writing to the changelog: "
  private val ChangelogCommitMessage = "There was an error committing the changelog: "

  private class ChangelogInfoMissingException(e: Throwable) extends Exception(e)
  private class ChangelogUpdateException(e: Throwable) extends Exception(e)
  private class ChangelogCommitException(e: Throwable) extends Exception(e)

  /**
   * Checks to see if mandatory author and message arguments are specified during release.
   */
  lazy val checkForChangelog: ReleaseStep = { st: State =>
    try {
      getChangelogInfo
      st
    } catch {
      case info: ChangelogInfoMissingException => sys.error("You must provide a changelog message and author")
      case e: Throwable => sys.error("There was an error getting the changelog info: " + e.getMessage)
    }
  }

  /**
   * Updates changelog with author and message arguments, then commits changes.
   */
  lazy val updateChangelog: ReleaseStep = { st: State =>
    try {
      val info = getChangelogInfo
      updateChangelog(info, st)
      commitChangelog(st)
      st
    } catch {
      case info: ChangelogInfoMissingException => sys.error(ChangelogInfoMissingMessage)
      case update: ChangelogUpdateException=> sys.error(ChangelogUpdateMessage + update.getMessage)
      case commit: ChangelogCommitException => sys.error(ChangelogCommitMessage + commit.getMessage)
      case e: Throwable => sys.error("There was an error updating the changelog: " + e.getMessage)
    }
  }

  private def getChangelogInfo: ChangelogInfo = {
    try {
      val msg = System.getProperty("changelog.msg")
      val msgExists = Option(msg).exists(_.length > 1)
      val author = System.getProperty("changelog.author")
      val authorExists = Option(author).exists(_.length > 1)
      if (msgExists & authorExists) {
        new ChangelogInfo(msg, author)
      } else {
        throw new Exception("msg or author too short")
      }
    } catch {
      case e: Throwable => throw new ChangelogInfoMissingException(e)
    }
  }

  private def updateChangelog(info: ChangelogInfo, st: State): Unit = {
    try {
      val changelogFile = st.extract.get(changelog)
      val oldChangelog = Source.fromFile(changelogFile).mkString
      val theVersion = getReleasedVersion(st)
      val dateFormat = new SimpleDateFormat("MM/dd/yy")
      val theDate = dateFormat.format(Calendar.getInstance().getTime)

      val out = new PrintWriter( changelogFile, "UTF-8")
      try {
        out.write("\n# " + theVersion + " " + theDate + " released by " + info.author + "\n")
        if (!info.msg.trim.startsWith("*")) out.write("* ")
        out.write(info.msg + "\n")
        oldChangelog.foreach(out.write(_))
      } finally {
        out.close()
      }
    } catch {
      case e: Throwable => throw new ChangelogUpdateException(e)
    }
  }

  private def commitChangelog(st: State): Unit = {
    try {
      val changelogFile = st.extract.get(changelog)
      val vcs = st.extract.get(versionControlSystem).getOrElse(
        sys.error("Aborting release. Working directory is not a repository of a recognized VCS."))
      vcs.add(changelogFile) !! st.log
      vcs.commit("Changelog updated for " + getReleasedVersion(st)) ! st.log
    } catch {
      case e: Throwable  => throw new ChangelogCommitException(e)
    }
  }

}

/**
 * Includes a release step `generateReadme` which creates a `README.md` from a template and commits the file.
 *
 * To manually generate the readme (no commit), a custom sbt task is available. Run via `sbt gen-readme`.
 *
 * By default, the template file is called `Readme-Template.md`, and it generates a file named `README.md`.
 * Customize these using the `readmeTemplate` and `readme` settings defined in CustomReleaseStepsKeys.
 *
 * The template can contain mapping keys in the form `{{key}}`, which will then be replaced with values specified in the `readmeTemplateMappings` setting key.
 *
 * By default, `readmeTemplateMappings` is defined:
 * {{{
 *   readmeTemplateMappings <<= (version in ThisBuild) { ver =>
 *      Map("version" -> ver)
 *   }
 * }}}
 *
 * Thus, any instance of `{{version}}` in the template will get replaced with the release version as part of the `generateReadme` release step.
 *
 * In addition, any instance of `{{auto-gen}}` in the template will get replaced with the following:
 *
 * THIS FILE WAS AUTO GENERATED BY THE README TEMPLATE. DO NOT EDIT DIRECTLY.
 *
 * This can be a useful thing to include so others changes are not overwritten if they edit the readme directly.
 *
 * Additional mappings can be added using `readmeTemplateMappings ++= ...`, or overridden completely using `readmeTemplateMappings := ...`.
 *
 */
object ReadmeReleaseSteps extends CommonContext {
  import BuildUtilitiesKeys._

  private val ReadmeGenerateMessage = "There was an error generating the readme: "
  private val ReadmeCommitMessage = "There was an error committing the readme: "

  private class ReadmeGenerateException(e: Throwable) extends Exception(e)
  private class ReadmeCommitException(e: Throwable) extends Exception(e)

  /**
   * Release step which generates the readme from the template followed by committing the changes.
   */
  lazy val generateReadme: ReleaseStep = { st: State =>
    try {
      val version = getReleasedVersion(st)
      val st2 = executeTask(genReadme, "Generating readme")(st)
      commitReadme(st2, version)
      st
    } catch {
      case generate: ReadmeGenerateException=> sys.error(ReadmeGenerateMessage + generate.getMessage)
      case commit: ReadmeCommitException => sys.error(ReadmeCommitMessage + commit.getMessage)
      case e: Throwable => sys.error("There was an error updating the readme: " + e.getMessage)
    }
  }

  /**
   * sbt task implementation (gen-readme) to generate the readme from the template.
   */
  def genReadmeTask: Def.Initialize[Task[Unit]] = (readme, readmeTemplate, readmeTemplateMappings).map { (readmeFile, templateFile, templateMappings) =>
    try {
      val template = Source.fromFile(templateFile).mkString
      val out = new PrintWriter(readmeFile, "UTF-8")
      try {
        val newReadme = templateMappings.foldLeft(template) { (currentReadme, mapping) =>
          val (regKey, replacement) = mapping
          // format will look like {{key}} in template
          val regex = s"\\{\\{$regKey\\}\\}".r
          regex.replaceAllIn(currentReadme, replacement)
        }
        newReadme.foreach(out.write(_))
      } finally {
        out.close()
      }
    } catch {
      case e: Throwable => throw new ReadmeGenerateException(e)
    }
  }

  private def commitReadme(st: State, newVersion: String): Unit = {
    try {
      val extracted = st.extract
      val vcs = extracted.get(versionControlSystem).getOrElse(sys.error("Unable to get version control system."))
      val readmeFile = extracted.get(readme)
      vcs.add(readmeFile) !! st.log
      vcs.commit(s"README.md updated to $newVersion") ! st.log
    } catch {
      case e: Throwable => throw new ReadmeCommitException(e)
    }
  }

}

/**
 * Includes a release step `generateAndPushDocs` for `sbtrelease` to generate ScalaDocs
 * using sbt-unidoc and sbt-site, followed by pushing them to the gh-pages branch of the current project's repository.
 *
 * Depends on adding `utilitySettings` to root project build settings.
 */
object ScaladocReleaseSteps extends CommonContext {

  /**
   * Executes the `makeSite` sbt-site task, followed by the `pushSite` sbt-ghpages task.
   */
  lazy val generateAndPushDocs: ReleaseStep = { st: State =>
    val st2 = executeTask(makeSite, "Making doc site")(st)
    executeTask(pushSite, "Publishing doc site")(st2)
  }

}

/**
 * Includes an action `publishSignedAction` for `sbtrelease` to publish signed artifacts.
 */
object PublishSignedReleaseSteps extends CommonContext {

  /**
   * Publishes signed artifacts using the PGP plugin.
   */
  lazy val publishSignedAction: State => State = { st: State =>
    val extracted = st.extract
    val ref = extracted.get(thisProjectRef)
    extracted.runAggregated(publishSigned in Global in ref, st)
  }

}
