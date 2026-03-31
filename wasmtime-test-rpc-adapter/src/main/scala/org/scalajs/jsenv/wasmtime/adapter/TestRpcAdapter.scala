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

package org.scalajs.jsenv.wasmtime.adapter

import java.nio.ByteBuffer
import java.util.Optional

import scala.scalajs.wasi.cli.environment
import scala.scalajs.wasi.io.streams.{InputStream, OutputStream, StreamError}
import scala.scalajs.wasi.sockets.instance_network
import scala.scalajs.wasi.sockets.network
import scala.scalajs.wasi.sockets.tcp
import scala.scalajs.wasi.sockets.tcp_create_socket
import scala.scalajs.wit.annotation._
import scala.scalajs.wit.{Err, Ok, Tuple2}
import scala.scalajs.wit.unsigned.{UByte, UShort}

@WitExportInterface
trait Rpc {
  @WitExport("scalajs:test-rpc/rpc", "init")
  def init(): Unit

  @WitExport("scalajs:test-rpc/rpc", "send")
  def send(msg: String): Unit

  @WitExport("scalajs:test-rpc/rpc", "poll")
  def poll(): Optional[String]
}

private object TestRpcTransport {
  private object Protocol {
    final val RpcHostEnv = "SCALAJS_TEST_RPC_HOST"
    final val RpcPortEnv = "SCALAJS_TEST_RPC_PORT"
  }

  // TODO(nit): initialize with null instead of optional
  private[this] var inputStream: Option[InputStream] = None
  private[this] var outputStream: Option[OutputStream] = None
  private[this] val envVars: Map[String, String] =
    environment.getEnvironment().iterator.map(e => e._1 -> e._2).toMap

  def init(): Unit = {
    if (inputStream.isEmpty || outputStream.isEmpty) {
      val Array(a, b, c, d) = envVars.get(Protocol.RpcHostEnv).get.split("\\.")
      val port = envVars.get(Protocol.RpcPortEnv).get.toInt.toShort.asInstanceOf[UShort]

      val networkHandle = instance_network.instanceNetwork()
      try {
        val socket =
          tcp_create_socket.createTcpSocket(network.IpAddressFamily.Ipv4) match {
            case ok: Ok[tcp.TcpSocket] =>
              ok.value
            case err: Err[network.ErrorCode] =>
              throw new IllegalStateException(s"create-tcp-socket failed: ${err.value}")
        }

        val remoteAddress = network.IpSocketAddress.Ipv4(
            network.Ipv4SocketAddress(port, (
              a.toByte.asInstanceOf[UByte],
              b.toByte.asInstanceOf[UByte],
              c.toByte.asInstanceOf[UByte],
              d.toByte.asInstanceOf[UByte]
            )))

        socket.startConnect(networkHandle, remoteAddress) match {
          case _: Ok[Unit] =>
          case err: Err[network.ErrorCode] =>
            throw new IllegalStateException(s"start-connect failed: ${err.value}")
        }

        val streams = finishConnect(socket)
        val in = streams._1
        val out = streams._2
        inputStream = Some(in)
        outputStream = Some(out)
      } finally {
        networkHandle.close()
      }
    }
  }

  def send(msg: String): Unit = {
    val out = currentOutputStream()
    val payload = ByteBuffer.allocate(4 + msg.length * 2)
    payload.putInt(msg.length)
    var i = 0
    while (i < msg.length) {
      payload.putChar(msg.charAt(i))
      i += 1
    }

    writeAll(out, payload.array())
  }

  def poll(): Optional[String] = {
    val in = currentInputStream()
    val message: Optional[String] =
      readNBytes(in, 4) match {
        case None =>
          Optional.empty()
        case Some(headerBytes) =>
          val msgLen = ByteBuffer.wrap(headerBytes).getInt()
          val payload = readNBytes(in, msgLen * 2).getOrElse {
            throw new IllegalStateException("Unexpected EOF while reading framed payload")
          }

          val chars = new Array[Char](msgLen)
          val payloadBuffer = ByteBuffer.wrap(payload)
          var i = 0
          while (i < msgLen) {
            chars(i) = payloadBuffer.getChar()
            i += 1
          }

          Optional.of(String.valueOf(chars))
      }

    if (message.isEmpty)
      closeState()
    message
  }

  private def currentInputStream(): InputStream =
    inputStream.getOrElse(throw new IllegalStateException("RPC input stream is not initialized"))

  private def currentOutputStream(): OutputStream =
    outputStream.getOrElse(throw new IllegalStateException("RPC output stream is not initialized"))

  private def finishConnect(socket: tcp.TcpSocket): Tuple2[InputStream, OutputStream] = {
    while (true) {
      socket.finishConnect() match {
        case ok: Ok[Tuple2[InputStream, OutputStream]] =>
          return ok.value

        // Returns would-block if it's in progress
        // https://github.com/WebAssembly/WASI/blob/50b674e4349aca006cccd699c1a5b6925cae6e88/proposals/sockets/README.md?plain=1#L71-L125
        case err: Err[network.ErrorCode] if err.value == network.ErrorCode.WouldBlock =>
          val pollable = socket.subscribe()
          try pollable.block()
          finally pollable.close()

        case err: Err[network.ErrorCode] =>
          throw new IllegalStateException(s"finish connect failed: ${err.value}")
      }
    }

    throw new AssertionError("unreachable")
  }

  private def readNBytes(in: InputStream, len: Int): Option[Array[Byte]] = {
    val result = new Array[Byte](len)
    var offset = 0

    while (offset < len) {
      val chunk = in.blockingRead((len - offset).toLong) match {
        case ok: Ok[Array[Byte]] =>
          ok.value
        case err: Err[StreamError] if err.value == StreamError.Closed =>
          if (offset == 0) return None
          else throw new IllegalStateException(s"Unexpected EOF after $offset/$len bytes")
        case err: Err[_] =>
          err.value match {
            case last: StreamError.LastOperationFailed =>
              throw new IllegalStateException(last.value.toDebugString())
          }
      }

      java.lang.System.arraycopy(chunk, 0, result, offset, chunk.length)
      offset += chunk.length
    }

    Some(result)
  }

  private def writeAll(out: OutputStream, bytes: Array[Byte]): Unit = {
    var offset = 0
    // blocking-write-and-flush writes up to 4096 bytes
    while (offset < bytes.length) {
      val end = Math.min(offset + 4096, bytes.length)
      val chunk = java.util.Arrays.copyOfRange(bytes, offset, end)

      out.blockingWriteAndFlush(chunk) match {
        case _: Ok[Unit] =>
        case err: Err[StreamError] if err.value == StreamError.Closed =>
          throw new IllegalStateException("test RPC socket closed while writing")
        case err: Err[_] =>
          err.value match {
            case last: StreamError.LastOperationFailed =>
              throw new IllegalStateException(last.value.toDebugString())
          }
      }

      offset = end
    }
  }

  private def closeState(): Unit = {
    try inputStream.foreach(_.close())
    finally inputStream = None

    try outputStream.foreach(_.close())
    finally outputStream = None
  }

}

@WitImplementation
object TestRpcAdapter extends Rpc {
  override def init(): Unit =
    TestRpcTransport.init()

  override def send(msg: String): Unit =
    TestRpcTransport.send(msg)

  override def poll(): Optional[String] =
    TestRpcTransport.poll()
}
