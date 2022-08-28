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

import org.junit.Test
import org.junit.Assert._

class RunConfigTest {
  @Test
  def supportedInheritIO: Unit = {
    val cfg = RunConfig()
      .withInheritOut(true)
      .withInheritErr(true)
    RunConfig.Validator()
      .supportsInheritIO()
      .validate(cfg)
  }

  @Test(expected = classOf[IllegalArgumentException])
  def unsupportedInheritOut: Unit = {
    val cfg = RunConfig()
      .withInheritOut(true)
      .withInheritErr(false)
      .withOnOutputStream((_, _) => ())
    RunConfig.Validator()
      .supportsOnOutputStream()
      .validate(cfg)
  }

  @Test(expected = classOf[IllegalArgumentException])
  def unsupportedInheritErr: Unit = {
    val cfg = RunConfig()
      .withInheritOut(false)
      .withInheritErr(true)
      .withOnOutputStream((_, _) => ())
    RunConfig.Validator()
      .supportsOnOutputStream()
      .validate(cfg)
  }

  @Test
  def supportedOnOutputStream: Unit = {
    val cfg = RunConfig()
      .withInheritOut(false)
      .withInheritErr(false)
      .withOnOutputStream((_, _) => ())
    RunConfig.Validator()
      .supportsOnOutputStream()
      .validate(cfg)
  }

  @Test(expected = classOf[IllegalArgumentException])
  def unsupportedOnOutputStream: Unit = {
    val cfg = RunConfig()
      .withInheritOut(false)
      .withInheritErr(false)
      .withOnOutputStream((_, _) => ())
    RunConfig.Validator()
      .validate(cfg)
  }

  @Test(expected = classOf[IllegalArgumentException])
  def missingOnOutputStreamNoInheritOut: Unit = {
    val cfg = RunConfig()
      .withInheritOut(false)
      .withInheritErr(true)
    RunConfig.Validator()
      .supportsInheritIO()
      .supportsOnOutputStream()
      .validate(cfg)
  }

  @Test(expected = classOf[IllegalArgumentException])
  def missingOnOutputStreamNoInheritErr: Unit = {
    val cfg = RunConfig()
      .withInheritOut(true)
      .withInheritErr(false)
    RunConfig.Validator()
      .supportsInheritIO()
      .supportsOnOutputStream()
      .validate(cfg)
  }

  @Test
  def supportedEnv: Unit = {
    val cfg = RunConfig()
      .withEnv(Map("x" -> "y"))
    RunConfig.Validator()
      .supportsInheritIO()
      .supportsEnv()
      .validate(cfg)
  }

  @Test(expected = classOf[IllegalArgumentException])
  def unsupportedEnv: Unit = {
    val cfg = RunConfig()
      .withEnv(Map("x" -> "y"))
    RunConfig.Validator()
      .supportsInheritIO()
      .validate(cfg)
  }

  @Test(expected = classOf[IllegalArgumentException])
  def failValidationForTest: Unit = {
    val cfg = RunConfig()
      .withEternallyUnsupportedOption(true)
    RunConfig.Validator()
      .validate(cfg)
  }
}
