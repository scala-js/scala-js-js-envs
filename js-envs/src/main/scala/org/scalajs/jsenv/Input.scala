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

import java.nio.file.Path

/** Input to a [[JSEnv]].
 *
 *  Implementors of a [[JSEnv]] are expected to pattern match on this input
 *  type and handle the ones they support.
 *
 *  Note that this type is not sealed, so future versions of Scala.js, as well
 *  as third-party libraries, may add additional input types.
 *
 *  [[JSEnv]]s are expected to fail with an [[UnsupportedInputException]] on
 *  `Input` types that they do not handle.
 */
abstract class Input

object Input {
  /** The file is to be loaded as a script into the global scope. */
  final case class Script(script: Path) extends Input

  /** The file is to be loaded as an ES module.
   *
   *  Some environments may not be able to load several ES modules in a
   *  deterministic order. If that is the case, they must reject an
   *  `ESModule` input if it appears with other Inputs such that loading
   *  in a deterministic order is not possible.
   */
  final case class ESModule(module: Path) extends Input

  /** The file is to be loaded as a CommonJS module. */
  final case class CommonJSModule(module: Path) extends Input

  /** The file is to be loaded as a Wasm module.
   *
   *  The `imports` are made available to the given module. Each pair
   *  `(name, input)` means that the Wasm module can import the module `name`
   *  and get the loaded result of `input`. `input` must be an `Input`
   *  appearing before this `Input.WasmModule` in the sequence of `Input`s to
   *  the JS env (as determined by `eq`).
   */
  final case class WasmModule(module: Path, imports: List[(String, Input)]) extends Input
}

class UnsupportedInputException(msg: String, cause: Throwable)
    extends IllegalArgumentException(msg, cause) {
  def this(msg: String) = this(msg, null)
  def this(input: Seq[Input]) = this(s"Unsupported input: $input")
}
