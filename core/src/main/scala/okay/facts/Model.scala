package okay.facts

import okay.llm.Llm
import okay.scala3.*

/** THE FAKE MODEL PORT (specs/design.md §14): fixed outputs replayed in order — okay-llm's fake, its text in pieces
 * by word, a tool call written as Qwen's `<tool_call>{"name": …, "arguments": …}</tool_call>` read as one. Tests
 * and the TCK's dry runs never need a model */
object Model:
  /** the outputs in order, one a generation; past the last, the last again. `seen` gets each prompt's last text */
  def replay[R[_], Rest[_], A](outputs: Vector[String], seen: scala.collection.mutable.Buffer[String] =
    scala.collection.mutable.Buffer.empty)(p: A ! R)(using Split[Llm, R, Rest]): A ! Rest =
    var i = -1
    Llm.fake[R, Rest, A] { last =>
      seen += last
      i += 1
      outputs(math.min(i, outputs.size - 1))
    }(p)

  /** a tool call as the fake's text gives it */
  def call(name: String, arguments: String): String =
    s"""<tool_call>{"name": "$name", "arguments": $arguments}</tool_call>"""
