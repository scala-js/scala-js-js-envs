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

package org.scalajs.jsenv.test

import org.scalajs.jsenv.JSEnv

import scala.concurrent.duration._

/** Configuration for a [[JSEnvSuite]].
 *
 *  @see [[JSEnvSuite]] for usage.
 *
 *  @param jsEnv [[JSEnv]] under test.
 *  @param terminateVMJSCode A JavaScript expression that terminates the VM.
 *      If set, proper handling of VM termination is tested.
 *  @param supportsCom Whether the [[JSEnv]] under test supports
 *      [[JSEnv#startWithCom]].
 *  @param supportsTimeout Whether the [[JSEnv]] under test supports the
 *      JavaScript timeout methods (as defined in
 *      [[http://www.scala-js.org/api/scalajs-library/latest/#scala.scalajs.js.timers.RawTimers$ RawTimers]]).
 *  @param supportsScripts Whether the [[JSEnv]] under test supports [[JSEnv.Input.Script]].
 *  @param supportsCommonJSModules Whether the [[JSEnv]] under test supports [[JSEnv.Input.CommonJSModule]].
 *  @param supportsESModules Whether the [[JSEnv]] under test supports [[JSEnv.Input.ESModule]].
 *  @param awaitTimeout Amount of time test cases wait for "things". This is
 *      deliberately not very well specified. Leave this as the default and
 *      increase it if your tests fail spuriously due to timeouts.
 *  @param description A human readable description of this configuration;
 *      defaults to [[JSEnv#name]]. This is only ever used in the parametrized
 *      JUnit test name. Can be customized if the same [[JSEnv]] is used with
 *      different configurations (e.g. Selenium with different browsers).
 */
final class JSEnvSuiteConfig private (
    val jsEnv: JSEnv,
    val supportsCom: Boolean,
    val supportsTimeout: Boolean,
    val supportsScripts: Boolean,
    val supportsCommonJSModules: Boolean,
    val supportsESModules: Boolean,
    val exitJSStatement: Option[String],
    val awaitTimeout: FiniteDuration,
    val description: String
) {
  private def this(jsEnv: JSEnv) = this(
      jsEnv = jsEnv,
      supportsCom = true,
      supportsTimeout = true,
      supportsScripts = true,
      supportsCommonJSModules = true,
      supportsESModules = true,
      exitJSStatement = None,
      awaitTimeout = 1.minute,
      description = jsEnv.name
  )

  def withSupportsCom(supportsCom: Boolean): JSEnvSuiteConfig =
    copy(supportsCom = supportsCom)

  def withSupportsTimeout(supportsTimeout: Boolean): JSEnvSuiteConfig =
    copy(supportsTimeout = supportsTimeout)

  def withSupportsScripts(supportsScripts: Boolean): JSEnvSuiteConfig =
    copy(supportsScripts = supportsScripts)

  def withSupportsCommonJSModules(supportsCommonJSModules: Boolean): JSEnvSuiteConfig =
    copy(supportsCommonJSModules = supportsCommonJSModules)

  def withSupportsESModules(supportsESModules: Boolean): JSEnvSuiteConfig =
    copy(supportsESModules = supportsESModules)

  def withExitJSStatement(code: String): JSEnvSuiteConfig =
    copy(exitJSStatement = Some(code))

  def withAwaitTimeout(awaitTimeout: FiniteDuration): JSEnvSuiteConfig =
    copy(awaitTimeout = awaitTimeout)

  def withDescription(description: String): JSEnvSuiteConfig =
    copy(description = description)

  private def copy(
      supportsCom: Boolean = supportsCom,
      supportsTimeout: Boolean = supportsTimeout,
      supportsScripts: Boolean = supportsScripts,
      supportsCommonJSModules: Boolean = supportsCommonJSModules,
      supportsESModules: Boolean = supportsESModules,
      exitJSStatement: Option[String] = exitJSStatement,
      awaitTimeout: FiniteDuration = awaitTimeout,
      description: String = description) = {
    new JSEnvSuiteConfig(jsEnv, supportsCom, supportsTimeout, supportsScripts,
        supportsCommonJSModules, supportsESModules,
        exitJSStatement, awaitTimeout, description)
  }
}

object JSEnvSuiteConfig {
  def apply(jsEnv: JSEnv): JSEnvSuiteConfig = new JSEnvSuiteConfig(jsEnv)
}
