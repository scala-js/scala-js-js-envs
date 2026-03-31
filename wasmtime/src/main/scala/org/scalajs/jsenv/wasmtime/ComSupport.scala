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

import java.io._
import java.net._
import java.nio.file.{Files, Path, StandardCopyOption}

import scala.concurrent._
import scala.sys.process._
import scala.util.{Failure, Success}
import scala.util.control.NonFatal

import scala.concurrent.ExecutionContext.Implicits.global

import org.scalajs.jsenv._

private final class ComRun(run: JSRun, handleMessage: String => Unit,
    serverSocket: ServerSocket) extends JSComRun {
  import ComRun._

  /** Promise that completes once the receiver thread is completed. */
  private[this] val promise = Promise[Unit]()

  @volatile
  private[this] var state: State = AwaitingConnection(Nil)

  // If the run completes, make sure we also complete.
  run.future.onComplete {
    case Failure(t) => forceClose(t)
    case Success(_) => onJSTerminated()
  }

  private[this] val receiver = new Thread {
    setName("ComRun receiver")

    override def run(): Unit = {
      try {
        try {
          /* We need to await the connection unconditionally. Otherwise the JS end
           * might try to connect indefinitely.
           */
          awaitConnection()

          while (state != Closing) {
            state match {
              case s: AwaitingConnection =>
                throw new IllegalStateException(s"Unexpected state: $s")

              case Closing =>
                /* We can end up here if there is a race between the two read to
                 * state. Do nothing, loop will terminate.
                 */

              case Connected(_, _, js2jvm) =>
                try {
                  val len = js2jvm.readInt()
                  if (len < 0)
                    throw new IllegalArgumentException(s"negative frame length: $len")

                  val chars = Array.fill(len)(js2jvm.readChar())
                  handleMessage(String.valueOf(chars))
                } catch {
                  case _: EOFException =>
                    // JS end terminated gracefully. Close.
                    close()
                }
            }
          }
        } catch {
          case _: IOException if state == Closing =>
            // We got interrupted by a graceful close.
            // This is OK.
        }

        /* Everything got closed. We wait for the run to terminate.
         * We need to wait in order to make sure that closing the
         * underlying run does not fail it.
         */
        ComRun.this.run.future.foreach { _ =>
          ComRun.this.run.close()
          promise.trySuccess(())
        }
      } catch {
        case t: Throwable => handleThrowable(t)
      }
    }
  }

  receiver.start()

  def future: Future[Unit] = promise.future

  def send(msg: String): Unit = synchronized {
    state match {
      case AwaitingConnection(msgs) =>
        state = AwaitingConnection(msg :: msgs)

      case Connected(_, jvm2js, _) =>
        try {
          writeMsg(jvm2js, msg)
          jvm2js.flush()
        } catch {
          case t: Throwable => handleThrowable(t)
        }

      case Closing => // ignore msg.
    }
  }

  def close(): Unit = synchronized {
    val oldState = state

    // Signal receiver thread that it is OK if socket read fails.
    state = Closing

    oldState match {
      case c: Connected =>
        // Interrupts the receiver thread and signals the VM to terminate.
        closeAll(c)

      case Closing | _: AwaitingConnection =>
    }
  }

  private def onJSTerminated() = {
    close()

    /* Interrupt receiver if we are still waiting for connection.
     * Should only be relevant if we are still awaiting the connection.
     * Note: We cannot do this in close(), otherwise if the JVM side closes
     * before the JS side connected, the JS VM will fail instead of terminate
     * normally.
     */
    serverSocket.close()
  }

  private def forceClose(cause: Throwable) = {
    promise.tryFailure(cause)
    close()
    run.close()
    serverSocket.close()
  }

  private def handleThrowable(cause: Throwable) = {
    forceClose(cause)
    if (!NonFatal(cause))
      throw cause
  }

  private def awaitConnection(): Unit = {
    var comSocket: Socket = null
    var jvm2js: DataOutputStream = null
    var js2jvm: DataInputStream = null

    try {
      comSocket = serverSocket.accept()
      serverSocket.close()
      jvm2js = new DataOutputStream(
          new BufferedOutputStream(comSocket.getOutputStream()))
      js2jvm = new DataInputStream(
          new BufferedInputStream(comSocket.getInputStream()))

      onConnected(Connected(comSocket, jvm2js, js2jvm))
    } catch {
      case t: Throwable =>
        closeAll(comSocket, jvm2js, js2jvm)
        throw t
    }
  }

  private def onConnected(c: Connected): Unit = synchronized {
    state match {
      case AwaitingConnection(msgs) =>
        msgs.reverse.foreach(writeMsg(c.jvm2js, _))
        c.jvm2js.flush()
        state = c

      case _: Connected =>
        throw new IllegalStateException(s"Unexpected state: $state")

      case Closing =>
        closeAll(c)
    }
  }

}

object ComRun {
  private final val RpcHostEnv = "SCALAJS_TEST_RPC_HOST"
  private final val RpcPortEnv = "SCALAJS_TEST_RPC_PORT"

  def start(wasmPath: Path, config: RunConfig, onMessage: String => Unit)(
      startRun: ((Path, Map[String, String])) => JSRun): JSComRun = {
    try {
      // Compose with adapter component generated by `wasmtime-test-rpc-adapter`
      // that reads socket address through env variables.
      val serverSocket =
        new ServerSocket(0, 0, InetAddress.getByName("127.0.0.1")) // IPv4 loopback address

      try {
        val env = Map(
            RpcHostEnv -> "127.0.0.1",
            RpcPortEnv -> serverSocket.getLocalPort.toString)

        val composedWasmPath = ComponentSupport.composeWithAdapter(wasmPath)
        val run = startRun((composedWasmPath, env))
        new ComRun(run, onMessage, serverSocket)
      } catch {
        case NonFatal(t) =>
          closeAll(serverSocket)
          JSComRun.failed(t)
      }
    } catch {
      case NonFatal(t) => JSComRun.failed(t)
    }
  }

  private def closeAll(c: Closeable*): Unit =
    c.withFilter(_ != null).foreach(_.close())

  private def closeAll(c: Connected): Unit =
    closeAll(c.comSocket, c.jvm2js, c.js2jvm)

  private sealed trait State

  private final case class AwaitingConnection(
      sendQueue: List[String]) extends State

  private final case class Connected(
      comSocket: Socket,
      jvm2js: DataOutputStream,
      js2jvm: DataInputStream) extends State

  private case object Closing extends State

  private def writeMsg(out: DataOutputStream, msg: String): Unit = {
    out.writeInt(msg.length)
    out.writeChars(msg)
  }

  private object ComponentSupport {
    private final val AdapterResourcePath =
      "/org/scalajs/jsenv/wasmtime/test-rpc/adapter.wasm"

    private lazy val adapterComponentPath: Path = {
      val path = Files.createTempFile("scalajs-wasmtime-test-rpc-adapter-", ".wasm")
      val in = getClass.getResourceAsStream(AdapterResourcePath)
      if (in == null) {
        throw new IllegalStateException(
            s"Missing Wasmtime test RPC resource: $AdapterResourcePath")
      }
      try {
        Files.copy(in, path, StandardCopyOption.REPLACE_EXISTING)
      } finally {
        in.close()
      }
      path.toFile.deleteOnExit()
      path
    }

    def composeWithAdapter(component: Path): Path = {
      val composed = Files.createTempFile("scalajs-wasmtime-composed-", ".wasm")
      composed.toFile.deleteOnExit()
      val cmd = Seq(
          "wac", "plug",
          "--plug", adapterComponentPath.toAbsolutePath.normalize.toString,
          component.toAbsolutePath.normalize.toString,
          "-o", composed.toAbsolutePath.normalize.toString
      )

      runCommand(cmd, "wac plug")
      composed
    }

    private def runCommand(cmd: Seq[String], desc: String): Unit = {
      val err = new StringBuilder
      val exit = Process(cmd).!(ProcessLogger(_ => (), line => err.append(line).append('\n')))
      if (exit != 0) {
        throw new IllegalStateException(
            s"$desc failed with exit code $exit\n" +
            s"command: ${cmd.mkString(" ")}\n" +
            err.toString)
      }
    }
  }
}
