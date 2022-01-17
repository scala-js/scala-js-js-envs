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

package org.scalajs.jsenv.nodejs

import org.scalajs.jsenv.test._

import org.junit.runner.RunWith

@RunWith(classOf[JSEnvSuiteRunner])
class NodeJSSuite extends JSEnvSuite(
  JSEnvSuiteConfig(new NodeJSEnv)
    .withSupportsESModules(false)  // #17
    .withExitJSStatement("process.exit(0);")
)
