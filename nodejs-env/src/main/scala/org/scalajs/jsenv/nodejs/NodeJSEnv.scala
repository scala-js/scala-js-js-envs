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


import scala.annotation.tailrec

import java.io._
import java.nio.charset.StandardCharsets
import java.nio.file._

import org.scalajs.jsenv._
import org.scalajs.jsenv.JSUtils.escapeJS

import org.scalajs.logging._

import com.google.common.jimfs.Jimfs

final class NodeJSEnv(config: NodeJSEnv.Config) extends JSEnv {
  import NodeJSEnv._

  def this() = this(NodeJSEnv.Config())

  val name: String = "Node.js"

  def start(input: Seq[Input], runConfig: RunConfig): JSRun = {
    NodeJSEnv.validator.validate(runConfig)
    validateInput(input)
    internalStart(initFiles ++ input, runConfig)
  }

  def startWithCom(input: Seq[Input], runConfig: RunConfig,
      onMessage: String => Unit): JSComRun = {
    NodeJSEnv.validator.validate(runConfig)
    validateInput(input)
    ComRun.start(runConfig, onMessage) { comLoader =>
      internalStart(initFiles ++ (Input.Script(comLoader) +: input), runConfig)
    }
  }

  private def validateInput(input: Seq[Input]): Unit = input.foreach {
    case _:Input.Script | _:Input.ESModule | _:Input.CommonJSModule | _:Input.WasmModule =>
      // ok
    case _ =>
      throw new UnsupportedInputException(input)
  }

  private def internalStart(input: Seq[Input], runConfig: RunConfig): JSRun = {
    val command = config.executable :: config.args
    val externalConfig = ExternalJSRun.Config()
      .withEnv(env)
      .withRunConfig(runConfig)
    ExternalJSRun.start(command, externalConfig)(NodeJSEnv.write(input))
  }

  private def initFiles: Seq[Input] = config.sourceMap match {
    case SourceMap.Disable           => Nil
    case SourceMap.EnableIfAvailable => Input.Script(installSourceMapIfAvailable) :: Nil
    case SourceMap.Enable            => Input.Script(installSourceMap) :: Nil
  }

  private def env: Map[String, String] =
    Map("NODE_MODULE_CONTEXTS" -> "0") ++ config.env
}

object NodeJSEnv {
  private lazy val fs = Jimfs.newFileSystem()

  private lazy val validator = ExternalJSRun.supports(RunConfig.Validator())

  private lazy val installSourceMapIfAvailable = {
    Files.write(
        fs.getPath("optionalSourceMapSupport.js"),
        """
          |try {
          |  require('source-map-support').install();
          |} catch (e) {
          |};
        """.stripMargin.getBytes(StandardCharsets.UTF_8))
  }

  private lazy val installSourceMap = {
    Files.write(
        fs.getPath("sourceMapSupport.js"),
        "require('source-map-support').install();".getBytes(StandardCharsets.UTF_8))
  }

  private lazy val wasmModuleSupport: String = {
    val i16ArrayModuleBytes =
      java.util.Base64.getDecoder().decode(BuildInfo.i16ArrayModuleBytes)
    val i16ArrayModuleBytesContent =
      i16ArrayModuleBytes.map(java.lang.Byte.toUnsignedInt(_)).mkString(",")

    raw"""
      |const wasmI16ArrayBytes = new Uint8Array([$i16ArrayModuleBytesContent]);
      |const wasmI16ArrayPromise =
      |    WebAssembly.instantiate(wasmI16ArrayBytes).then(m => m.instance.exports);
      |
      |async function importWasmModule(path) {
      |  const wasmI16Array = await wasmI16ArrayPromise;
      |
      |  const wasmI16ArrayToJSString = function(array) {
      |    var len = wasmI16Array.length(array);
      |    var result = "";
      |    for (var i = 0; i !== len; i++)
      |      result += String.fromCharCode(wasmI16Array.get(array, i) & 0xffff);
      |    return result;
      |  }
      |
      |  const jsStringToWasmI16Array = function(str) {
      |    var len = str.length;
      |    var result = wasmI16Array.create(len);
      |    for (var i = 0; i !== len; i++)
      |      wasmI16Array.set(result, i, str.charCodeAt(i));
      |    return result;
      |  }
      |
      |  // a null prototype is required to isolate Wasm even from what's in Object.prototype
      |  const importsObj = Object.create(null);
      |
      |  if (typeof scalajsCom !== 'undefined') {
      |    const comImport = Object.create(null);
      |    comImport.send = function(msg) {
      |      scalajsCom.send(wasmI16ArrayToJSString(msg));
      |    };
      |    importsObj["scalajs:com"] = comImport;
      |  }
      |
      |  // Load and compile the Wasm module
      |  const fs = await import('node:fs/promises');
      |  const { resolve } = await import('node:path');
      |  const buffer = await fs.readFile(path);
      |  const module = await WebAssembly.compile(buffer);
      |
      |  // Look for additional imports through Node.js resolution
      |  for (const importDecl of WebAssembly.Module.imports(module)) {
      |    const moduleName = importDecl.module;
      |    if (!Object.hasOwn(importsObj, moduleName)) {
      |      const importedModule = await import(resolve(path, moduleName));
      |      importsObj[moduleName] = importedModule;
      |    }
      |  }
      |
      |  // Instantiate the module with all the resolved imports
      |  const instance = await WebAssembly.instantiate(module, importsObj);
      |  const moduleExports = instance.exports;
      |
      |  if (typeof scalajsCom !== 'undefined') {
      |    const newReceive = moduleExports["scalajs:com/receive"];
      |    if (newReceive !== undefined) {
      |      scalajsCom.init(function(msg) {
      |        newReceive(jsStringToWasmI16Array(msg));
      |      });
      |    }
      |  }
      |
      |  return moduleExports;
      |}
    """.stripMargin
  }

  private def write(input: Seq[Input])(out: OutputStream): Unit = {
    def runScript(path: Path): String = {
      try {
        val f = path.toFile
        val pathJS = "\"" + escapeJS(f.getAbsolutePath) + "\""
        s"""
          require('vm').runInThisContext(
            require('fs').readFileSync($pathJS, { encoding: "utf-8" }),
            { filename: $pathJS, displayErrors: true }
          )
        """
      } catch {
        case _: UnsupportedOperationException =>
          val code = new String(Files.readAllBytes(path), StandardCharsets.UTF_8)
          val codeJS = "\"" + escapeJS(code) + "\""
          val pathJS = "\"" + escapeJS(path.toString) + "\""
          s"""
            require('vm').runInThisContext(
              $codeJS,
              { filename: $pathJS, displayErrors: true }
            )
          """
      }
    }

    def requireCommonJSModule(module: Path): String =
      s"""require("${escapeJS(toFile(module).getAbsolutePath)}")"""

    /* We use toPath.toUri to get a file:/// URI instead of a file:/ URI.
     * The default provider of nio.file.Path guarantees this form for local files.
     */
    def importESModule(module: Path): String =
      s"""import("${escapeJS(toFile(module).toPath.toUri.toASCIIString)}")"""

    def importWasmModule(module: Path): String =
      s"""importWasmModule("${escapeJS(toFile(module).getAbsolutePath)}")"""

    def execInputExpr(input: Input): String = input match {
      case Input.Script(script)         => runScript(script)
      case Input.CommonJSModule(module) => requireCommonJSModule(module)
      case Input.ESModule(module)       => importESModule(module)
      case Input.WasmModule(module)     => importWasmModule(module)
    }

    val hasESModule = input.exists(_.isInstanceOf[Input.ESModule])
    val hasWasmModule = input.exists(_.isInstanceOf[Input.WasmModule])

    val p = new PrintStream(out, false, "UTF8")
    try {
      if (!hasESModule && !hasWasmModule) {
        /* If there is no ES module nor Wasm module in the input, we can do
         * everything synchronously, and directly on the standard input.
         */
        for (item <- input)
          p.println(execInputExpr(item) + ";")
      } else {
        /* If there is at least one ES module or Wasm module, we must asynchronously
         * chain things, and we must use an actual file to feed code to Node.js
         * (because `import()` cannot be used from the standard input).
         */
        val importChain = input.foldLeft("Promise.resolve()") { (prev, item) =>
          s"$prev.\n  then(() => ${execInputExpr(item)})"
        }
        val importerFileContent = {
          s"""
            |${if (hasWasmModule) wasmModuleSupport else "" }
            |$importChain.catch(e => {
            |  console.error(e);
            |  process.exit(1);
            |});
          """.stripMargin
        }
        val f = createTmpFile("importer.js")
        Files.write(f.toPath, importerFileContent.getBytes(StandardCharsets.UTF_8))
        p.println(s"""require("${escapeJS(f.getAbsolutePath)}");""")
      }
    } finally {
      p.close()
    }
  }

  private def toFile(path: Path): File = {
    try {
      path.toFile
    } catch {
      case _: UnsupportedOperationException =>
        val f = createTmpFile(path.toString)
        Files.copy(path, f.toPath(), StandardCopyOption.REPLACE_EXISTING)
        f
    }
  }

  // tmpSuffixRE and createTmpFile copied from HTMLRunnerBuilder.scala

  private val tmpSuffixRE = """[a-zA-Z0-9-_.]*$""".r

  private def createTmpFile(path: String): File = {
    /* - createTempFile requires a prefix of at least 3 chars
     * - we use a safe part of the path as suffix so the extension stays (some
     *   browsers need that) and there is a clue which file it came from.
     */
    val suffix = tmpSuffixRE.findFirstIn(path).orNull

    val f = File.createTempFile("tmp-", suffix)
    f.deleteOnExit()
    f
  }

  /** Requirements for source map support. */
  sealed abstract class SourceMap

  object SourceMap {
    /** Disable source maps. */
    case object Disable extends SourceMap

    /** Enable source maps if `source-map-support` is available. */
    case object EnableIfAvailable extends SourceMap

    /** Always enable source maps.
     *
     *  If `source-map-support` is not available, loading the .js code will
     *  fail.
     */
    case object Enable extends SourceMap
  }

  final class Config private (
      val executable: String,
      val args: List[String],
      val env: Map[String, String],
      val sourceMap: SourceMap
  ) {
    private def this() = {
      this(
          executable = "node",
          args = Nil,
          env = Map.empty,
          sourceMap = SourceMap.EnableIfAvailable
      )
    }

    def withExecutable(executable: String): Config =
      copy(executable = executable)

    def withArgs(args: List[String]): Config =
      copy(args = args)

    def withEnv(env: Map[String, String]): Config =
      copy(env = env)

    def withSourceMap(sourceMap: SourceMap): Config =
      copy(sourceMap = sourceMap)

    /** Forces enabling (true) or disabling (false) source maps.
     *
     *  `sourceMap = true` maps to [[SourceMap.Enable]]. `sourceMap = false`
     *  maps to [[SourceMap.Disable]]. [[SourceMap.EnableIfAvailable]] is never
     *  used by this method.
     */
    def withSourceMap(sourceMap: Boolean): Config =
      withSourceMap(if (sourceMap) SourceMap.Enable else SourceMap.Disable)

    private def copy(
        executable: String = executable,
        args: List[String] = args,
        env: Map[String, String] = env,
        sourceMap: SourceMap = sourceMap
    ): Config = {
      new Config(executable, args, env, sourceMap)
    }
  }

  object Config {
    /** Returns a default configuration for a [[NodeJSEnv]].
     *
     *  The defaults are:
     *
     *  - `executable`: `"node"`
     *  - `args`: `Nil`
     *  - `env`: `Map.empty`
     *  - `sourceMap`: [[SourceMap.EnableIfAvailable]]
     */
    def apply(): Config = new Config()
  }
}
