/*
 * Scala.js JS Envs (https://github.com/scala-js/scala-js-js-envs)
 *
 * Copyright EPFL.
 *
 * Licensed under Apache License 2.0
 * (https://www.apache.org/licenses/LICENSE-2.0).
 *
 * See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.
 */

package org.scalajs.jsenv.test.kit

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration

import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file._
import java.util.concurrent.Executors

import com.google.common.jimfs.Jimfs

import org.scalajs.jsenv._

/** TestKit is a utility class to simplify testing of [[JSEnv]]s.
 *
 *  It is mostly used by Scala.js' provided [[JSEnv]] test suite but it may be
 *  used for additional tests specific to a particular [[JSEnv]].
 *
 *  @example
 *  {{{
 *  import scala.concurrent.duration._
 *
 *  val kit = new TestKit(new MyEnv, 1.second)
 *  kit.withRun("""console.log("Hello World");""") {
 *    _.expectOut("Hello World\n")
 *      .closeRun()
 *  }
 *  }}}
 *
 *  @note Methods in [[TestKit]] allow to take a string instead of an [[Input]].
 *      The string is converted into an input via `defaultInputKind`.
 *
 *  @constructor Create a new [[TestKit]] for the given [[JSEnv]] and timeout.
 *  @param jsEnv The [[JSEnv]] to be tested.
 *  @param timeout Timeout for all `expect*` methods on [[Run]] / [[ComRun]].
 */
final class TestKit(jsEnv: JSEnv, timeout: FiniteDuration,
    defaultInputKind: TestKit.InputKind) {

  def this(jsEnv: JSEnv, timeout: FiniteDuration) =
    this(jsEnv, timeout, TestKit.InputKind.Script)

  /** Starts a [[Run]] for testing. */
  def start(code: String): Run =
    start(codeToInput(code))

  /** Starts a [[Run]] for testing. */
  def start(input: Seq[Input]): Run =
    start(input, RunConfig())

  /** Starts a [[Run]] for testing. */
  def start(code: String, config: RunConfig): Run =
    start(codeToInput(code), config)

  /** Starts a [[Run]] for testing. */
  def start(input: Seq[Input], config: RunConfig): Run = {
    val (run, out, err) = io(config)(jsEnv.start(input, _))
    new Run(run, out, err, timeout)
  }

  /** Starts a [[ComRun]] for testing. */
  def startWithCom(code: String): ComRun =
    startWithCom(codeToInput(code))

  /** Starts a [[ComRun]] for testing. */
  def startWithCom(input: Seq[Input]): ComRun =
    startWithCom(input, RunConfig())

  /** Starts a [[ComRun]] for testing. */
  def startWithCom(code: String, config: RunConfig): ComRun =
    startWithCom(codeToInput(code), config)

  /** Starts a [[ComRun]] for testing. */
  def startWithCom(input: Seq[Input], config: RunConfig): ComRun = {
    val msg = new MsgHandler
    val (run, out, err) = io(config)(jsEnv.startWithCom(input, _, msg.onMessage _))
    run.future.onComplete(msg.onRunComplete _)(TestKit.completer)

    new ComRun(run, out, err, msg, timeout)
  }

  /** Convenience method to start a [[Run]] and close it after usage. */
  def withRun[T](code: String)(body: Run => T): T =
    withRun(codeToInput(code))(body)

  /** Convenience method to start a [[Run]] and close it after usage. */
  def withRun[T](input: Seq[Input])(body: Run => T): T =
    withRun(input, RunConfig())(body)

  /** Convenience method to start a [[Run]] and close it after usage. */
  def withRun[T](code: String, config: RunConfig)(body: Run => T): T =
    withRun(codeToInput(code), config)(body)

  /** Convenience method to start a [[Run]] and close it after usage. */
  def withRun[T](input: Seq[Input], config: RunConfig)(body: Run => T): T = {
    val run = start(input, config)
    try body(run)
    finally run.close()
  }

  /** Convenience method to start a [[ComRun]] and close it after usage. */
  def withComRun[T](code: String)(body: ComRun => T): T = withComRun(codeToInput(code))(body)

  /** Convenience method to start a [[ComRun]] and close it after usage. */
  def withComRun[T](input: Seq[Input])(body: ComRun => T): T = withComRun(input, RunConfig())(body)

  /** Convenience method to start a [[ComRun]] and close it after usage. */
  def withComRun[T](code: String, config: RunConfig)(body: ComRun => T): T =
    withComRun(codeToInput(code), config)(body)

  /** Convenience method to start a [[ComRun]] and close it after usage. */
  def withComRun[T](input: Seq[Input], config: RunConfig)(body: ComRun => T): T = {
    val run = startWithCom(input, config)
    try body(run)
    finally run.close()
  }

  /** Converts a Path to an Input based on this Kit's defaultInputKind */
  def pathToInput(path: Path): Input =
    TestKit.pathToInput(path, defaultInputKind)

  private def io[T <: JSRun](config: RunConfig)(start: RunConfig => T): (T, IOReader, IOReader) = {
    val out = new IOReader
    val err = new IOReader

    def onOutputStream(o: Option[InputStream], e: Option[InputStream]) = {
      o.foreach(out.onInputStream _)
      e.foreach(err.onInputStream _)
    }

    val newConfig = config
      .withOnOutputStream(onOutputStream)
      .withInheritOut(false)
      .withInheritErr(false)

    val run = start(newConfig)

    run.future.onComplete(out.onRunComplete _)(TestKit.completer)
    run.future.onComplete(err.onRunComplete _)(TestKit.completer)

    (run, out, err)
  }

  private def codeToInput(code: String): Seq[Input] = {
    val p = Files.write(
        Jimfs.newFileSystem().getPath("test.js"),
        code.getBytes(StandardCharsets.UTF_8))
    List(pathToInput(p))
  }
}

object TestKit {
  /** Execution context to run completion callbacks from runs under test. */
  private val completer =
    ExecutionContext.fromExecutor(Executors.newSingleThreadExecutor())

  /** Converts a Path to an Input based on the inputKind. */
  def pathToInput(path: Path, inputKind: InputKind): Input = {
    import InputKind._

    inputKind match {
      case Script         => Input.Script(path)
      case CommonJSModule => Input.CommonJSModule(path)
      case ESModule       => Input.ESModule(path)
    }
  }

  sealed trait InputKind

  object InputKind {
    case object Script extends InputKind
    case object CommonJSModule extends InputKind
    case object ESModule extends InputKind
  }
}
