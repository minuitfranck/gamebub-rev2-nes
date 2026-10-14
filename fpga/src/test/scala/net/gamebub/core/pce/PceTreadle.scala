package net.gamebub.core.pce

import chisel3.RawModule

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

/**
 * Runs a Chisel 7 design on chiseltest's bundled pure-JVM interpreter
 * (treadle2), for specs that need no verilator.
 *
 * This machine has no verilator (which the SNES specs' `EphemeralSimulator`
 * needs) and chiseltest 6.0.0's `ChiselBridge` does not load against Chisel
 * 7.7, so the design is elaborated to CHIRRTL with Chisel's own
 * `ChiselStage`, the statement forms of the FIRRTL 6 dialect that
 * firrtl2's 1.x text parser does not read are rewritten (`connect`,
 * `invalidate`, `regreset`, `public module`, radix literals, n-ary `cat`,
 * the version / layer / annotation lines, the `const` qualifier) and the
 * result is handed to `treadle2.TreadleTester`. The rewrite is syntactic
 * and refuses anything it does not know, so the RTL simulated is the
 * design's own. Unlike the helpers in [[PceRomInfoSpec]] and
 * [[PceVideoCaptureSpec]] this one passes CHIRRTL memories (`smem` /
 * `cmem` / `mport`, what `chisel3.util.SRAM` and `SyncReadMem` emit) and
 * instances through, which treadle's own lowering handles. Both texts are
 * written under `build/<name>/` for inspection.
 *
 * Timing note for designs with falling-edge registers
 * (`withClock((!clock.asBool).asClock)`): treadle2 updates a register on a
 * derived inverted clock together with the rising-edge registers of the
 * same step (it sees the pre-edge values, like any other register), not
 * half a cycle earlier as the hardware does, so a value poked before a
 * step reaches a rising-edge register fed by such a capture one step later
 * than on the board. [[PceRomPathSpec]] measures this at the start of its
 * run and cancels it in its harness.
 */
object PceTreadle {
  /** A treadle2 simulation, addressed by the lowered port names (`io_a_b`). */
  class Sim(val tester: treadle2.TreadleTester) {
    def poke(name: String, value: BigInt): Unit = tester.poke(name, value)
    def poke(name: String, value: Boolean): Unit = tester.poke(name, if (value) 1 else 0)
    def peek(name: String): BigInt = tester.peek(name)
    def peekBool(name: String): Boolean = tester.peek(name) != 0
    def step(n: Int = 1): Unit = tester.step(n)
    def expect(name: String, value: BigInt, what: String = ""): Unit = {
      val got = peek(name)
      assert(got == value, s"$name: got $got, want $value${if (what.nonEmpty) s" ($what)" else ""}")
    }
    def finish(): Unit = tester.finish
  }

  def apply(gen: => RawModule, name: String): Sim = {
    val chirrtl = circt.stage.ChiselStage.emitCHIRRTL(gen)
    val legacy = downgrade(chirrtl)
    val dir = Paths.get("build", name)
    Files.createDirectories(dir)
    Files.write(dir.resolve(s"$name.chirrtl.fir"), chirrtl.getBytes(StandardCharsets.UTF_8))
    Files.write(dir.resolve(s"$name.legacy.fir"), legacy.getBytes(StandardCharsets.UTF_8))
    // firrtl2's ConstantPropagation recurses without end on register-alias
    // loops (a StackOverflowError on the ROM path's library modules); it is
    // an optimization only, so it is skipped.
    val tester = treadle2.TreadleTester(Seq(
      firrtl2.stage.FirrtlSourceAnnotation(legacy),
      firrtl2.transforms.NoConstantPropagationAnnotation,
    ))
    val scheduler = tester.engine.scheduler
    scheduler.combinationalAssigns = new IndexedAssignBuffer(scheduler.combinationalAssigns)
    new Sim(tester)
  }

  /**
   * treadle2's `Scheduler.executeCombinationalAssigns` runs the assigns as
   * `combinationalAssigns.toSeq(index)`; in Scala 2.13 `ArrayBuffer.toSeq`
   * builds a `List`, so every evaluation is quadratic in the circuit size
   * (the ROM path's 16 k assigns: seconds per clock). This buffer's `toSeq`
   * hands out an array-backed sequence instead, rebuilt only when the
   * buffer changed. Installed on the scheduler after the tester is built
   * (the assigns are all registered and sorted by then).
   */
  private class IndexedAssignBuffer(initial: Iterable[treadle2.executable.Assigner])
    extends scala.collection.mutable.ArrayBuffer[treadle2.executable.Assigner] {
    this ++= initial
    private var cached: scala.collection.immutable.ArraySeq[treadle2.executable.Assigner] = null
    private var cachedLength = -1
    override def toSeq: scala.collection.immutable.Seq[treadle2.executable.Assigner] = {
      if (cached == null || cachedLength != length) {
        cached = scala.collection.immutable.ArraySeq.unsafeWrapArray(toArray)
        cachedLength = length
      }
      cached
    }
  }

  private val Connect = """^(\s*)connect (\S+), (.+?)( @\[.*\])?$""".r
  private val Invalidate = """^(\s*)invalidate (\S+)( @\[.*\])?$""".r
  private val RegReset = """^(\s*)regreset (\S+) : (.+), (\S+), (\S+), (\S+)( @\[.*\])?$""".r
  private val PublicModule = """^(\s*)public module (.*)$""".r
  private val Circuit = """^circuit (\S+) :.*$""".r
  /** CHIRRTL memory with a read-under-write qualifier: FIRRTL 6 spells it `, undefined`, 1.x ` undefined`. */
  private val SmemRuw = """^(\s*smem \S+ : .+?), (old|new|undefined)( @\[.*\])?$""".r
  /** Radix-prefixed literals, `UInt<8>(0h4f)` / `SInt<4>(-0h4)`: the 1.x grammar takes decimal `UInt<8>(79)`. */
  private val RadixLiteral = """\((-?)0([hbo])([0-9a-fA-F]+)\)""".r
  private val Unsupported = Seq("layerblock", "intrinsic", "propassign", "define", "attach", "printf", "assert", "assume", "cover", "stop")
  /** Statement keywords the translation leaves alone (all in the 1.x grammar). */
  private val Statements = Set("circuit", "module", "extmodule", "input", "output", "wire", "reg", "node", "inst",
    "when", "else", "skip", "smem", "cmem", "mem", "read", "write", "infer", "rdwr", "defname", "parameter")
  private val MemFields = Set("data-type", "depth", "read-latency", "write-latency", "read-under-write", "reader", "writer", "readwriter")

  /** Splits `a, cat(b, c), d` at its top-level commas. */
  private def splitArgs(s: String): Seq[String] = {
    val parts = Seq.newBuilder[String]
    var depth = 0
    var start = 0
    for (i <- s.indices) s(i) match {
      case '(' => depth += 1
      case ')' => depth -= 1
      case ',' if depth == 0 =>
        parts += s.substring(start, i).trim
        start = i + 1
      case _ =>
    }
    parts += s.substring(start).trim
    parts.result()
  }

  /** `cat(a, b, c)` (n-ary since FIRRTL 4) -> `cat(a, cat(b, c))`, the 1.x binary form, recursively. */
  private val CatCall = """(?<![A-Za-z0-9_])cat\(""".r
  private def nestCat(line: String): String = {
    val at = CatCall.findFirstMatchIn(line).map(_.start).getOrElse(-1)
    if (at < 0) return line
    var depth = 0
    var end = -1
    var i = at + 3
    while (end < 0 && i < line.length) {
      line(i) match {
        case '(' => depth += 1
        case ')' => depth -= 1; if (depth == 0) end = i
        case _ =>
      }
      i += 1
    }
    require(end > 0, s"nestCat: unbalanced parentheses in: $line")
    val args = splitArgs(line.substring(at + 4, end)).map(nestCat)
    val nested = args.reduceRight((a, b) => s"cat($a, $b)")
    line.substring(0, at) + nested + nestCat(line.substring(end + 1))
  }

  private def literals(line: String): String = RadixLiteral.replaceAllIn(line, m => {
    val radix = m.group(2) match { case "h" => 16; case "o" => 8; case _ => 2 }
    java.util.regex.Matcher.quoteReplacement("(" + m.group(1) + BigInt(m.group(3), radix).toString + ")")
  })

  /** FIRRTL 6 CHIRRTL text to the 1.x dialect. */
  def downgrade(chirrtl: String): String = {
    var inAnnotations = false
    val out = Vector.newBuilder[String]
    for (raw <- chirrtl.linesIterator) {
      val line = nestCat(literals(raw).replace(" : const ", " : "))
      val trimmed = line.trim
      if (inAnnotations) {
        if (trimmed.endsWith("]]")) inAnnotations = false
      } else line match {
        case l if l.startsWith("FIRRTL version") => ()
        case Circuit(name) =>
          out += s"circuit $name :"
          if (line.contains("%[[") && !trimmed.endsWith("]]")) inAnnotations = true
        case _ if trimmed.startsWith("layer ") => ()
        case PublicModule(indent, rest) => out += s"${indent}module $rest"
        case Connect(indent, lhs, rhs, info) => out += s"$indent$lhs <= $rhs${Option(info).getOrElse("")}"
        case Invalidate(indent, ref, info) => out += s"$indent$ref is invalid${Option(info).getOrElse("")}"
        case RegReset(indent, name, tpe, clock, reset, init, info) =>
          out += s"${indent}reg $name : $tpe, $clock with : (reset => ($reset, $init))${Option(info).getOrElse("")}"
        case SmemRuw(head, ruw, info) => out += s"$head $ruw${Option(info).getOrElse("")}"
        case l =>
          Unsupported.find(k => trimmed.startsWith(k)).foreach { k =>
            throw new IllegalArgumentException(s"downgrade: no rule for '$k' in: $l")
          }
          if (trimmed.nonEmpty) {
            val keyword = trimmed.takeWhile(ch => ch != ' ' && ch != '(' && ch != ':')
            val ok = Statements.contains(keyword) || MemFields.contains(keyword) || trimmed.contains(" <= ") ||
              trimmed.contains(" is invalid") || trimmed.startsWith(";")
            require(ok, s"downgrade: FIRRTL construct not handled by the legacy translation: $l")
          }
          out += l
      }
    }
    out.result().mkString("\n") + "\n"
  }
}
