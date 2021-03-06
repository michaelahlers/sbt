/*
 * sbt
 * Copyright 2011 - 2018, Lightbend, Inc.
 * Copyright 2008 - 2010, Mark Harrah
 * Licensed under Apache License 2.0 (see LICENSE)
 */

package sbt

import java.io.PrintWriter
import java.util.Properties

import jline.TerminalFactory

import scala.annotation.tailrec
import scala.util.control.NonFatal

import sbt.io.{ IO, Using }
import sbt.internal.util.{ ErrorHandling, GlobalLogBacking }
import sbt.internal.langserver.ErrorCodes
import sbt.util.Logger
import sbt.protocol._

object MainLoop {

  /** Entry point to run the remaining commands in State with managed global logging.*/
  def runLogged(state: State): xsbti.MainResult = {
    // We've disabled jline shutdown hooks to prevent classloader leaks, and have been careful to always restore
    // the jline terminal in finally blocks, but hitting ctrl+c prevents finally blocks from being executed, in that
    // case the only way to restore the terminal is in a shutdown hook.
    val shutdownHook = new Thread(() => TerminalFactory.get().restore())

    try {
      Runtime.getRuntime.addShutdownHook(shutdownHook)
      runLoggedLoop(state, state.globalLogging.backing)
    } finally {
      Runtime.getRuntime.removeShutdownHook(shutdownHook)
      ()
    }
  }

  /** Run loop that evaluates remaining commands and manages changes to global logging configuration.*/
  @tailrec def runLoggedLoop(state: State, logBacking: GlobalLogBacking): xsbti.MainResult =
    runAndClearLast(state, logBacking) match {
      case ret: Return => // delete current and last log files when exiting normally
        logBacking.file.delete()
        deleteLastLog(logBacking)
        ret.result
      case clear: ClearGlobalLog => // delete previous log file, move current to previous, and start writing to a new file
        deleteLastLog(logBacking)
        runLoggedLoop(clear.state, logBacking.shiftNew())
      case keep: KeepGlobalLog => // make previous log file the current log file
        logBacking.file.delete
        runLoggedLoop(keep.state, logBacking.unshift)
    }

  /** Runs the next sequence of commands, cleaning up global logging after any exceptions. */
  def runAndClearLast(state: State, logBacking: GlobalLogBacking): RunNext =
    try runWithNewLog(state, logBacking)
    catch {
      case e: xsbti.FullReload =>
        deleteLastLog(logBacking)
        throw e // pass along a reboot request
      case e: RebootCurrent =>
        deleteLastLog(logBacking)
        deleteCurrentArtifacts(state)
        throw new xsbti.FullReload(e.arguments.toArray, false)
      case NonFatal(e) =>
        System.err.println(
          "sbt appears to be exiting abnormally.\n  The log file for this session is at " + logBacking.file
        )
        deleteLastLog(logBacking)
        throw e
    }

  /** Deletes the previous global log file. */
  def deleteLastLog(logBacking: GlobalLogBacking): Unit =
    logBacking.last.foreach(_.delete())

  /** Deletes the current sbt artifacts from boot. */
  private[sbt] def deleteCurrentArtifacts(state: State): Unit = {
    import sbt.io.syntax._
    val provider = state.configuration.provider
    val appId = provider.id
    // If we can obtain boot directory more accurately it'd be better.
    val defaultBoot = BuildPaths.defaultGlobalBase / "boot"
    val buildProps = state.baseDir / "project" / "build.properties"
    // First try reading the sbt version from build.properties file.
    val sbtVersionOpt = if (buildProps.exists) {
      val buildProperties = new Properties()
      IO.load(buildProperties, buildProps)
      Option(buildProperties.getProperty("sbt.version"))
    } else None
    val sbtVersion = sbtVersionOpt.getOrElse(appId.version)
    val currentArtDirs = defaultBoot * "*" / appId.groupID / appId.name / sbtVersion
    currentArtDirs.get foreach { dir =>
      state.log.info(s"Deleting $dir")
      IO.delete(dir)
    }
  }

  /** Runs the next sequence of commands with global logging in place. */
  def runWithNewLog(state: State, logBacking: GlobalLogBacking): RunNext =
    Using.fileWriter(append = true)(logBacking.file) { writer =>
      val out = new PrintWriter(writer)
      val full = state.globalLogging.full
      val newLogging = state.globalLogging.newAppender(full, out, logBacking)
      // transferLevels(state, newLogging)
      val loggedState = state.copy(globalLogging = newLogging)
      try run(loggedState)
      finally out.close()
    }

  // /** Transfers logging and trace levels from the old global loggers to the new ones. */
  // private[this] def transferLevels(state: State, logging: GlobalLogging): Unit = {
  //   val old = state.globalLogging
  //   Logger.transferLevels(old.backed, logging.backed)
  //   (old.full, logging.full) match { // well, this is a hack
  //     case (oldLog: AbstractLogger, newLog: AbstractLogger) => Logger.transferLevels(oldLog, newLog)
  //     case _                                                => ()
  //   }
  // }

  sealed trait RunNext
  final class ClearGlobalLog(val state: State) extends RunNext
  final class KeepGlobalLog(val state: State) extends RunNext
  final class Return(val result: xsbti.MainResult) extends RunNext

  /** Runs the next sequence of commands that doesn't require global logging changes. */
  @tailrec def run(state: State): RunNext =
    state.next match {
      case State.Continue       => run(next(state))
      case State.ClearGlobalLog => new ClearGlobalLog(state.continue)
      case State.KeepLastLog    => new KeepGlobalLog(state.continue)
      case ret: State.Return    => new Return(ret.result)
    }

  def next(state: State): State =
    ErrorHandling.wideConvert { state.process(processCommand) } match {
      case Right(s)                  => s
      case Left(t: xsbti.FullReload) => throw t
      case Left(t: RebootCurrent)    => throw t
      case Left(t)                   => state.handleError(t)
    }

  /** This is the main function State transfer function of the sbt command processing. */
  def processCommand(exec: Exec, state: State): State = {
    val channelName = exec.source map (_.channelName)
    StandardMain.exchange publishEventMessage
      ExecStatusEvent("Processing", channelName, exec.execId, Vector())

    try {
      val newState = Command.process(exec.commandLine, state)
      val doneEvent = ExecStatusEvent(
        "Done",
        channelName,
        exec.execId,
        newState.remainingCommands.toVector map (_.commandLine),
        exitCode(newState, state),
      )
      if (doneEvent.execId.isDefined) { // send back a response or error
        import sbt.protocol.codec.JsonProtocol._
        StandardMain.exchange publishEvent doneEvent
      } else { // send back a notification
        StandardMain.exchange publishEventMessage doneEvent
      }
      newState
    } catch {
      case err: Throwable =>
        val errorEvent = ExecStatusEvent(
          "Error",
          channelName,
          exec.execId,
          Vector(),
          ExitCode(ErrorCodes.UnknownError),
        )
        import sbt.protocol.codec.JsonProtocol._
        StandardMain.exchange publishEvent errorEvent
        throw err
    }
  }

  def logFullException(e: Throwable, log: Logger): Unit = State.logFullException(e, log)

  private[this] type ExitCode = Option[Long]
  private[this] object ExitCode {
    def apply(n: Long): ExitCode = Option(n)
    val Success: ExitCode = ExitCode(0)
    val Unknown: ExitCode = None
  }

  private[this] def exitCode(state: State, prevState: State): ExitCode = {
    exitCodeFromStateNext(state) match {
      case ExitCode.Success => exitCodeFromStateOnFailure(state, prevState)
      case x                => x
    }
  }

  // State's "next" field indicates the next action for the command processor to take
  // we'll use that to determine if the command failed
  private[this] def exitCodeFromStateNext(state: State): ExitCode = {
    state.next match {
      case State.Continue       => ExitCode.Success
      case State.ClearGlobalLog => ExitCode.Success
      case State.KeepLastLog    => ExitCode.Success
      case ret: State.Return =>
        ret.result match {
          case exit: xsbti.Exit  => ExitCode(exit.code().toLong)
          case _: xsbti.Continue => ExitCode.Success
          case _: xsbti.Reboot   => ExitCode.Success
          case x =>
            val clazz = if (x eq null) "" else " (class: " + x.getClass + ")"
            state.log debug s"Unknown main result: $x$clazz"
            ExitCode.Unknown
        }
    }
  }

  // the shell command specifies an onFailure so that if an exception is thrown
  // it's handled by executing the shell again, instead of the state failing
  // so we also use that to indicate that the execution failed
  private[this] def exitCodeFromStateOnFailure(state: State, prevState: State): ExitCode =
    if (prevState.onFailure.isDefined && state.onFailure.isEmpty) ExitCode(ErrorCodes.UnknownError)
    else ExitCode.Success

}
