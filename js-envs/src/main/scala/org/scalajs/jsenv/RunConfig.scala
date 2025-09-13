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

package org.scalajs.jsenv

import java.io.InputStream

import org.scalajs.logging._

/** Configuration provided when starting a [[JSEnv]].
 *
 *  @param onOutputStream Callback once output streams of the JS VM run become available.
 *
 *      The callback receives the output and the error stream of the VM if they
 *      are available. If [[inheritOutput]] or [[inheritError]] are set to true, the
 *      respective streams must be `None`, in the invocation of
 *      [[onOutputStream]]. Note however, that if [[onOutputStream]] is present,
 *      it must be invoked by the JS VM.
 *
 *  @param inheritOutput Whether the output stream of the VM should be inherited.
 *
 *      The implementation may chose to redirect to the actual output stream of
 *      the parent JVM or simply [[scala.Console#out]].
 *
 *      If you set this value to `false` you must set [[onOutputStream]].
 *
 *  @param inheritError Whether the error stream of the VM should be inherited.
 *
 *      The implementation may chose to redirect to the actual error stream of the
 *      parent JVM or simply [[scala.Console#err]].
 *
 *      If you set this value to `false` you must set [[onOutputStream]].
 *
 *  @param logger The logger to use in the run. A [[JSEnv]] is not required to
 *      log anything.
 *
 *  @param env Additional environment variables for this run.
 *
 *      How these are retrieved in the JS code run inside the [[JSEnv]] is
 *      completely up to the implementation, including whether:
 *      - they are implemented with system environment variables,
 *      - they share the same namespace than the system environment variables.
 *
 *      However, in any case, the variables in [[env]] take precedence
 *      over any (explicitly or implicitly) ambiant environment vars.
 *
 *      This is an optional feature; but [[JSEnv]]s are required to support an
 *      empty [[env]].
 */
final class RunConfig private (
  val onOutputStream: Option[RunConfig.OnOutputStream],
  val inheritOutput: Boolean,
  val inheritError: Boolean,
  val logger: Logger,
  val env: Map[String, String],
  /** An option that will never be supported by anything because it is not exposed.
   *
   *  This is used to test that [[JSEnv]]s properly validate their configuration.
   */
  private[jsenv] val eternallyUnsupportedOption: Boolean
) {
  import RunConfig.OnOutputStream

  private def this() = {
    this(
        onOutputStream = None,
        inheritOutput = true,
        inheritError = true,
        logger = NullLogger,
        env = Map.empty,
        eternallyUnsupportedOption = false)
  }

  def withOnOutputStream(onOutputStream: OnOutputStream): RunConfig =
    copy(onOutputStream = Some(onOutputStream))

  def withInheritOut(inheritOutput: Boolean): RunConfig =
    copy(inheritOutput = inheritOutput)

  def withInheritErr(inheritError: Boolean): RunConfig =
    copy(inheritError = inheritError)

  def withLogger(logger: Logger): RunConfig =
    copy(logger = logger)

  def withEnv(env: Map[String, String]): RunConfig =
    copy(env = env)

  private[jsenv] def withEternallyUnsupportedOption(
      eternallyUnsupportedOption: Boolean): RunConfig =
    copy(eternallyUnsupportedOption = eternallyUnsupportedOption)

  private def copy(onOutputStream: Option[OnOutputStream] = onOutputStream,
      inheritOutput: Boolean = inheritOutput,
      inheritError: Boolean = inheritError,
      logger: Logger = logger,
      env: Map[String, String] = env,
      eternallyUnsupportedOption: Boolean = eternallyUnsupportedOption
  ): RunConfig = {
    new RunConfig(onOutputStream, inheritOutput, inheritError, logger,
        env, eternallyUnsupportedOption)
  }

  /** Validates constraints on the config itself. */
  private def validate(): Unit = {
    if (onOutputStream.isEmpty && (!inheritOutput || !inheritError)) {
      throw new IllegalArgumentException("You may not set inheritOutput or " +
          "inheritError to false without setting onOutputStream.")
    }
  }
}

object RunConfig {
  type OnOutputStream = (Option[InputStream], Option[InputStream]) => Unit
  def apply(): RunConfig = new RunConfig()

  /** Support validator for [[RunConfig]].
   *
   *  Validators allow us to add options to [[RunConfig]] in a forward
   *  compatible manner.
   *
   *  Every [[JSEnv]] must
   *
   *  1. create a [[Validator]]
   *  1. inform it of the [[JSEnv]]'s capabilities
   *  1. invoke [[validate]] with every received [[RunConfig]]
   *
   *  This ensures that all set config options are supported by the [[JSEnv]].
   */
  final class Validator private (
      inheritIO: Boolean,
      onOutputStream: Boolean,
      env: Boolean
  ) {
    private def this() = this(false, false, false)

    /** The caller supports [[RunConfig#inheritOutput]] and
     *  [[RunConfig#inheritError]].
     */
    def supportsInheritIO(): Validator = copy(inheritIO = true)

    /** The caller supports [[RunConfig#onOutputStream]]. */
    def supportsOnOutputStream(): Validator = copy(onOutputStream = true)

    /** The caller supports [[RunConfig#env]]. */
    def supportsEnv(): Validator = copy(env = true)

    /** Validates that `config` is valid and only sets supported options.
     *
     *  @throws java.lang.IllegalArgumentException if there are unsupported options.
     */
    def validate(config: RunConfig): Unit = {
      def fail(msg: String) = throw new IllegalArgumentException(msg)

      config.validate()

      if (!inheritIO && (config.inheritOutput || config.inheritError))
        fail("inheritOutput / inheritError are not supported.")

      if (!onOutputStream && config.onOutputStream.isDefined)
        fail("onOutputStream is not supported.")

      if (!env && config.env.nonEmpty)
        fail("env is not supported.")

      if (config.eternallyUnsupportedOption)
        fail("eternallyUnsupportedOption is not supported.")
    }

    private def copy(
        inheritIO: Boolean = inheritIO,
        onOutputStream: Boolean = onOutputStream,
        env: Boolean = env
    ) = {
      new Validator(inheritIO, onOutputStream, env)
    }
  }

  object Validator {
    def apply(): Validator = new Validator()
  }
}
