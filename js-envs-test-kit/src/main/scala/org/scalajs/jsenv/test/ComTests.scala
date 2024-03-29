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

import org.junit.{Test, AssumptionViolatedException}
import org.junit.Assume._

import org.scalajs.jsenv._
import org.scalajs.jsenv.test.kit.TestKit

private[test] class ComTests(config: JSEnvSuiteConfig,
    defaultInputKind: TestKit.InputKind) {
  private val kit = new TestKit(config.jsEnv, config.awaitTimeout, defaultInputKind)

  @Test
  def basicTest: Unit = {
    kit.withComRun("""
      scalajsCom.init(function(msg) { scalajsCom.send("received: " + msg); });
      scalajsCom.send("Hello World");
    """) { run =>

      run.expectMsg("Hello World")

      for (i <- 0 to 10) {
        run
          .send(i.toString)
          .expectMsg(s"received: $i")
      }

      run.expectNoMsgs()
        .closeRun()
    }
  }

  @Test
  def jsExitsOnMessageTest: Unit = {
    val exitStat = config.exitJSStatement.getOrElse(
        throw new AssumptionViolatedException("JSEnv needs exitJSStatement"))

    kit.withComRun(s"""
      scalajsCom.init(function(msg) { $exitStat });
      for (var i = 0; i < 10; ++i)
        scalajsCom.send("msg: " + i);
      """) { run =>

      for (i <- 0 until 10)
        run.expectMsg(s"msg: $i")

      run
        .send("quit")
        .expectNoMsgs()
        .succeeds()
    }
  }

  @Test
  def multiEnvTest: Unit = {
    val n = 10
    val runs = List.fill(5) {
      kit.startWithCom("""
      scalajsCom.init(function(msg) {
        scalajsCom.send("pong");
      });
      """)
    }

    try {
      for (_ <- 0 until n) {
        runs.foreach(_.send("ping"))
        runs.foreach(_.expectMsg("pong"))
      }

      runs.foreach {
        _.expectNoMsgs()
          .closeRun()
      }
    } finally {
      runs.foreach(_.close())
    }
  }

  private def replyTest(msg: String) = {
    kit.withComRun("scalajsCom.init(scalajsCom.send);") {
      _.send(msg)
        .expectMsg(msg)
        .expectNoMsgs()
        .closeRun()
    }
  }

  @Test
  def largeMessageTest: Unit = {
    /* 1MB data.
     * (i & 0x7f) limits the input to the ASCII repertoire, which will use
     * exactly 1 byte per Char in UTF-8. This restriction also ensures that we
     * do not introduce surrogate characters and therefore no invalid UTF-16
     * strings.
     */
    replyTest(new String(Array.tabulate(1024 * 1024)(i => (i & 0x7f).toChar)))
  }

  @Test
  def highCharTest: Unit = { // #1536
    replyTest("\uC421\u8F10\u0112\uFF32")
  }

  @Test
  def noInitTest: Unit = {
    kit.withComRun("") {
      _.send("Dummy")
        .expectNoMsgs()
        .closeRun()
    }
  }

  @Test
  def separateComStdoutTest: Unit = {
    // Make sure that com and stdout do not interfere with each other.
    kit.withComRun("""
      scalajsCom.init(function (msg) {
        console.log("got: " + msg)
      });
      console.log("a");
      scalajsCom.send("b");
      scalajsCom.send("c");
      console.log("d");
    """) {
      _.expectOut("a\n")
        .expectMsg("b")
        .expectMsg("c")
        .expectOut("d\n")
        .send("foo")
        .expectOut("got: foo\n")
        .closeRun()
    }
  }
}
