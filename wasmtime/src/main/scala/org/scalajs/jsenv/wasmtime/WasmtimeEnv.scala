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

package org.scalajs.jsenv.wasmtime

import java.nio.file.Path

import org.scalajs.jsenv._

final class WasmtimeEnv(config: WasmtimeEnv.Config) extends JSEnv {
  def this() = this(WasmtimeEnv.Config())

  val name: String = "wasmtime"

  def start(input: Seq[Input], runConfig: RunConfig): JSRun = {
    WasmtimeEnv.validator.validate(runConfig)
    val wasmPath = validateInput(input)
    internalStart(wasmPath, runConfig)
  }

  def startWithCom(input: Seq[Input], runConfig: RunConfig,
      onMessage: String => Unit): JSComRun = {
    JSComRun.failed(new UnsupportedOperationException(
        "WasmtimeEnv does not support startWithCom yet."))
  }

  private def validateInput(input: Seq[Input]): Path =
    WasmtimeEnv.resolveInput(input)

  private def internalStart(wasmPath: Path, runConfig: RunConfig): JSRun = {
    val command = baseCommand(wasmPath)
    val externalConfig = ExternalJSRun.Config()
      .withEnv(config.env)
      .withRunConfig(runConfig)

    ExternalJSRun.start(command, externalConfig)(_.close())
  }

  private def baseCommand(wasmPath: Path): List[String] =
    config.executable :: (config.args :+ wasmPath.toAbsolutePath.normalize.toString)
}

object WasmtimeEnv {
  private lazy val validator = ExternalJSRun.supports(RunConfig.Validator())

  private[wasmtime] def resolveInput(input: Seq[Input]): Path = {
    val wasmComponents = input.collect { case wasm: Input.WasmComponent => wasm.component }

    if (wasmComponents.size != input.size) {
      throw new UnsupportedInputException(
          "WasmtimeEnv only supports Input.WasmComponent. " +
          "Do not mix with Script/ESModule/CommonJSModule inputs.")
    }
    if (wasmComponents.size != 1) {
      throw new UnsupportedInputException(
          s"WasmtimeEnv requires exactly one Input.WasmComponent, got ${wasmComponents.size}.")
    }

    wasmComponents.head
  }

  final class Config private (
      val executable: String,
      val args: List[String],
      val env: Map[String, String]
  ) {
    private def this() = {
      this(
          executable = "wasmtime",
          args = List("-W", "gc,function-references,exceptions"),
          env = Map.empty
      )
    }

    def withExecutable(executable: String): Config =
      copy(executable = executable)

    def withArgs(args: List[String]): Config =
      copy(args = args)

    def withEnv(env: Map[String, String]): Config =
      copy(env = env)

    private def copy(
        executable: String = executable,
        args: List[String] = args,
        env: Map[String, String] = env
    ): Config = {
      new Config(executable, args, env)
    }
  }

  object Config {
    def apply(): Config = new Config()
  }
}
