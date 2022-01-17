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

import scala.reflect.ClassTag

import org.junit.runner.Runner
import org.junit.runners.Suite
import org.junit.runners.parameterized.{TestWithParameters, BlockJUnit4ClassRunnerWithParameters}
import org.junit.runners.model.TestClass

/** Conformance test suite for any [[JSEnv]] implementation.
 *
 *  Use with the [[JSEnvSuiteRunner]].
 *
 *  Example:
 *  {{{
 *  import org.junit.runner.RunWith
 *
 *  @RunWith(classOf[JSEnvSuiteRunner])
 *  class MyJSEnvSuite extends JSEnvSuite(JSEnvSuiteConfig(new MyJSEnv))
 *  }}}
 *
 *  @see [[JSEnvSuiteConfig]] for details on the configuration.
 */
abstract class JSEnvSuite(private[test] val config: JSEnvSuiteConfig)

/** Runner for a [[JSEnvSuite]]. May only be used on subclasses of [[JSEnvSuite]]. */
final class JSEnvSuiteRunner(root: Class[_], config: JSEnvSuiteConfig)
    extends Suite(root, JSEnvSuiteRunner.getRunners(config)) {

  /** Constructor for reflective instantiation via `@RunWith`. */
  def this(suite: Class[_ <: JSEnvSuite]) =
    this(suite, suite.getDeclaredConstructor().newInstance().config)

  /** Constructor for instantiation in a user defined Runner. */
  def this(config: JSEnvSuiteConfig) = this(null, config)
}

private object JSEnvSuiteRunner {
  private def r[T](config: JSEnvSuiteConfig, params: (String, AnyRef)*)(implicit t: ClassTag[T]) = {
    val name = (("config" -> config.description) +: params)
      .map { case (name, value) => s"$name = $value" }
      .mkString("[", ", ", "]")

    val paramValues = new java.util.LinkedList[AnyRef]
    paramValues.add(config)
    for (param <- params)
      paramValues.add(param._2)

    new BlockJUnit4ClassRunnerWithParameters(
        new TestWithParameters(name, new TestClass(t.runtimeClass), paramValues))
  }

  private def getRunners(config: JSEnvSuiteConfig): java.util.List[Runner] = {
    import java.lang.Boolean.{TRUE, FALSE}

    val runners = new java.util.ArrayList[Runner]

    val withComValues =
      if (config.supportsCom) List(TRUE, FALSE)
      else List(FALSE)

    for (withCom <- withComValues)
      runners.add(r[RunTests](config, "withCom" -> withCom))

    if (config.supportsTimeout) {
      for (withCom <- withComValues)
        runners.add(r[TimeoutRunTests](config, "withCom" -> withCom))
    }

    if (config.supportsCom)
      runners.add(r[ComTests](config))

    if (config.supportsCom && config.supportsTimeout)
      runners.add(r[TimeoutComTests](config))

    runners
  }
}
