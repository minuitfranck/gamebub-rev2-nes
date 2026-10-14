package net.gamebub.core.nes

import chisel3._
import chisel3.util._
import net.gamebub.core.pce.PceTreadle
import org.scalatest.freespec.AnyFreeSpec

import scala.util.Random

/**
 * Sound Filter (docs/nes-port-design.md): [[NesAudioFilter]] against a
 * bit-exact model ([[NesAudioSpec.Model]]) and against the designed biquads
 * in double precision, at the NES decimator's rate. The cases are core-ngpc's
 * NgpcAudioSpec filter cases with the NES coefficients, without Sound Level
 * (the NES ties it off; the module is the NGPC's, where it is tested), plus
 * the Off path and the output timing HandheldNes relies on.
 *
 * The simulator is treadle2 through [[PceTreadle]] (no verilator here).
 */
class NesAudioSpec extends AnyFreeSpec {
  import NesAudioSpec._

  /** Clocks from one `inValid` to the next in the filter harness (the module needs >= 32; the NES decimator's are 512 or more). */
  val Spacing = 32
  val Fs = NesAudio.SampleRateHz.toDouble

  lazy val filterSim: PceTreadle.Sim = PceTreadle(new NesAudioFilter, "NesAudioFilterSpec")

  /** Reset the filter harness: every history 0, setting Off, no fade. */
  def freshFilter(): Harness = {
    val sim = filterSim
    sim.poke("io_inValid", false)
    sim.poke("io_in_0", BigInt(0))
    sim.poke("io_in_1", BigInt(0))
    sim.poke("io_filter", BigInt(0))
    sim.poke("io_attenuate", false)
    sim.poke("reset", true)
    sim.step(3)
    sim.poke("reset", false)
    sim.step(2)
    new Harness(sim)
  }

  class Harness(sim: PceTreadle.Sim) {
    val model = new Model
    /** One sample through the hardware and the model; returns both outputs. */
    def sample(l: Int, r: Int, filter: Int, attenuate: Boolean): ((Int, Int), (Int, Int)) = {
      sim.poke("io_in_0", BigInt(l))
      sim.poke("io_in_1", BigInt(r))
      sim.poke("io_filter", BigInt(filter))
      sim.poke("io_attenuate", attenuate)
      sim.poke("io_inValid", true)
      sim.step(1)
      sim.poke("io_inValid", false)
      sim.step(Spacing - 1)
      val hw = (signed16(sim.peek("io_out_0")), signed16(sim.peek("io_out_1")))
      (hw, model.sample(l, r, filter, attenuate))
    }
    /** Samples that must match the model exactly; returns the hardware's outputs. */
    def run(xs: Seq[(Int, Int)], filter: Int, attenuate: Boolean = false, what: String = ""): Seq[(Int, Int)] =
      xs.zipWithIndex.map { case ((l, r), i) =>
        val (hw, m) = sample(l, r, filter, attenuate)
        assert(hw == m, s"$what sample $i (in $l / $r, filter $filter, -6 dB $attenuate): hardware $hw, model $m")
        hw
      }
    /** Enter a setting and let the fade into it finish (the input is silence). */
    def settle(filter: Int, attenuate: Boolean = false): Unit =
      run(Seq.fill(NesAudio.FadeSamples + 8)((0, 0)), filter, attenuate, "settle")
  }

  def tone(freq: Double, amplitude: Double, n: Int, phase: Double = 0.0): Seq[Int] =
    (0 until n).map(i => math.round(amplitude * math.sin(2 * math.Pi * freq * i / Fs + phase)).toInt)

  "the coefficients are the Butterworth design, quantized with unity DC gain" in {
    val expected = Seq((5601, -11329, 71264), (52537, -18845, 31844), (83806, -31271, 13001))
    assert(NesAudio.Biquads.map(b => (b.na1, b.na2, b.g)) == expected)
    for (b <- NesAudio.Biquads) {
      // 4 g = 1 + a1 + a2 exactly: the DC gain is 1 in the integers the hardware uses.
      assert(b.g == (1 << NesAudio.CoefFractionBits) - b.na1 - b.na2)
      assert(4 * b.gain == 1 + b.a1 + b.a2)
      val ideal = Ideal(b.cutoffHz)
      for (f <- Seq(100.0, 1000.0, 3500.0, 6000.0, 10000.0, 15000.0, 20000.0)) {
        val d = 20 * math.log10(response(b.gain, b.a1, b.a2, f) / response(ideal.b0, ideal.a1, ideal.a2, f))
        assert(math.abs(d) < 0.005, f"${b.cutoffHz} Hz filter at $f%.0f Hz: quantized differs by $d%.4f dB")
      }
      val atCutoff = 20 * math.log10(response(b.gain, b.a1, b.a2, b.cutoffHz))
      assert(math.abs(atCutoff + 3.0103) < 0.01, f"${b.cutoffHz} Hz filter: $atCutoff%.4f dB at its cut-off")
      // Every value fits the 18-bit multiplier port.
      for (v <- Seq(b.na1, b.na2, b.g)) assert(v >= -(1 << 17) && v < (1 << 17))
    }
  }

  "impulse responses match the model exactly and the designed biquads within 1 LSB" in {
    val h = freshFilter()
    for ((b, k) <- NesAudio.Biquads.zipWithIndex) {
      val filter = k + 1
      h.settle(filter)
      val a = 16384
      val xs = Seq((a, -a)) ++ Seq.fill(63)((0, 0))
      val out = h.run(xs, filter, what = s"impulse ${b.cutoffHz}")
      val ideal = Ideal(b.cutoffHz).impulse(64).map(_ * a)
      for (i <- 0 until 64) {
        assert(math.abs(out(i)._1 - ideal(i)) <= 1.0, f"${b.cutoffHz} Hz: h[$i] ${out(i)._1} vs ${ideal(i)}%.2f")
        assert(out(i)._2 == -out(i)._1 || math.abs(out(i)._2 + ideal(i)) <= 1.0)
      }
      println(f"impulse ${b.cutoffHz}%.0f Hz (x16384): ${out.take(8).map(_._1).mkString(" ")} ... (designed ${ideal.take(8).map(v => f"$v%.1f").mkString(" ")})")
    }
  }

  "frequency responses match the designed biquads" in {
    val h = freshFilter()
    val amplitude = 16000.0
    val freqs = Seq(100.0, 1000.0, 2000.0, 3500.0, 6000.0, 10000.0, 15000.0, 20000.0)
    for ((b, k) <- NesAudio.Biquads.zipWithIndex) {
      val filter = k + 1
      h.settle(filter)
      val ideal = Ideal(b.cutoffHz)
      val rows = freqs.grouped(2).flatMap { pair =>
        val fl = pair.head
        val fr = pair.last
        val n = 400
        val xs = tone(fl, amplitude, n).zip(tone(fr, amplitude, n, 1.0))
        val out = h.run(xs, filter, what = s"sine ${b.cutoffHz}")
        val skip = 100
        Seq((fl, fitAmplitude(out.drop(skip).map(_._1.toDouble), fl, skip)),
          (fr, fitAmplitude(out.drop(skip).map(_._2.toDouble), fr, skip)))
      }.toSeq
      val table = rows.map { case (f, got) =>
        val want = amplitude * response(ideal.b0, ideal.a1, ideal.a2, f)
        assert(math.abs(got - want) <= 0.002 * want + 1.0, f"${b.cutoffHz} Hz filter at $f%.0f Hz: amplitude $got%.1f, designed $want%.1f")
        f"$f%.0f Hz ${20 * math.log10(got / amplitude)}%.3f dB (designed ${20 * math.log10(want / amplitude)}%.3f)"
      }
      println(s"${NesAudio.FilterNames(filter)} (${b.cutoffHz} Hz): " + table.mkString(", "))
    }
  }

  "full-scale square waves never wrap: the output saturates as the model does, the state never saturates" in {
    val h = freshFilter()
    for (filter <- 1 to 3) {
      h.settle(filter)
      for (period <- Seq(2, 4, 6, 48, 480)) {
        val xs = (0 until 600).map { i =>
          val high = (i / (period / 2)) % 2 == 0
          if (high) (32767, -32768) else (-32768, 32767)
        }
        val out = h.run(xs, filter, what = s"square period $period")
        val clipped = out.count { case (l, _) => l == 32767 || l == -32768 }
        println(f"${NesAudio.FilterNames(filter)}: full-scale square, period $period%3d samples: ${clipped} of 600 outputs at a rail (largest |y| of the three filters so far ${h.model.peak / 256.0}%.0f)")
      }
    }
    assert(h.model.stateSaturations == 0)
    assert(h.model.peak < (1L << 24) * 3 / 4, s"state peak ${h.model.peak / 256.0} is near the 65536 range")
  }

  "Off passes the decimator's samples unchanged; outValid comes 26 clocks after inValid, in every setting" in {
    val h = freshFilter()
    val rnd = new Random(5)
    val xs = Seq((32767, 32767), (-32768, -32768), (0, 0), (-1, -1)) ++
      Seq.fill(300) { val v = rnd.between(-32768, 32768); (v, v) }
    val out = h.run(xs, 0, what = "Off")
    assert(out == xs, "Off changed a sample")
    // The timing HandheldNes gives the rate adapter: one outValid per inValid, 26 clocks after it.
    val sim = filterSim
    for (filter <- Seq(0, 1, 3)) {
      h.settle(filter)
      sim.poke("io_in_0", BigInt(1234))
      sim.poke("io_in_1", BigInt(1234))
      sim.poke("io_inValid", true)
      sim.step(1)
      sim.poke("io_inValid", false)
      // t = clocks after the one that takes inValid.
      val strobes = (1 to 40).filter { _ =>
        sim.step(1)
        sim.peek("io_outValid") == 1
      }
      assert(strobes == Seq(26), s"filter $filter: outValid at clocks $strobes after inValid")
      h.model.sample(1234, 1234, filter, attenuate = false)
    }
  }

  "a change of Sound Filter cross-fades without a click" in {
    val h = freshFilter()
    val changes = Seq(((0, false), (3, false)), ((3, false), (0, false)), ((1, false), (3, false)), ((3, false), (1, false)),
      ((0, false), (1, false)), ((1, false), (0, false)), ((0, false), (2, false)), ((2, false), (1, false)))
    for (((from, to), i) <- changes.zipWithIndex; phase <- Seq(0.0, 1.1, 2.3)) {
      h.settle(from._1, from._2)
      val n = 900
      val xs = tone(1000, 20000, n, phase).zip(tone(1000, 20000, n, phase + 0.7))
      val before = h.run(xs.take(300), from._1, from._2, "before")
      val after = h.run(xs.drop(300), to._1, to._2, "after")
      val out = (before ++ after).map(_._1)
      def maxStep(s: Seq[Int]): Int = s.sliding(2).map(p => math.abs(p(1) - p(0))).max
      val natural = maxStep(out.slice(100, 300)) max maxStep(out.slice(300 + NesAudio.FadeSamples + 10, n))
      val atChange = maxStep(out.slice(298, 300 + NesAudio.FadeSamples + 10))
      assert(atChange <= natural * 1.02 + 2, s"$from -> $to (phase $phase): largest step $atChange around the change, $natural otherwise")
      if (phase == 0.0) println(s"change $from -> $to: largest step $atChange around the change, $natural in steady state (1 kHz, amplitude 20000)")
    }
  }

  "random signals and random setting changes (also during fades) match the model" in {
    val h = freshFilter()
    val rnd = new Random(11)
    var filter = 0
    val attenuate = false
    var left = 0
    for (block <- 0 until 40) {
      if (left <= 0) {
        filter = rnd.nextInt(4)
        left = rnd.between(1, 400)
      }
      val f1 = rnd.between(50.0, 20000.0)
      val f2 = rnd.between(50.0, 20000.0)
      val n = rnd.between(20, 120)
      val xs = (0 until n).map { i =>
        def v(f: Double, p: Double) = 14000 * math.sin(2 * math.Pi * f * i / Fs + p) + rnd.between(-4000, 4000)
        (clamp16(math.round(v(f1, 0) + v(f2, 1)).toInt), clamp16(math.round(v(f2, 2) - v(f1, 3)).toInt))
      }
      h.run(xs, filter, attenuate, s"block $block")
      left -= n
    }
  }

  "silence decays to exactly 0 and a constant settles exactly (no limit cycles)" in {
    val h = freshFilter()
    val rnd = new Random(3)
    for (filter <- 1 to 3) {
      h.settle(filter)
      h.run(Seq.fill(200)((rnd.between(-30000, 30000), rnd.between(-30000, 30000))), filter, what = "noise")
      val zeros = h.run(Seq.fill(700)((0, 0)), filter, what = "zeros")
      val lastNonZero = zeros.lastIndexWhere(_ != (0, 0))
      assert(lastNonZero < 64, s"filter $filter: output not 0 at sample $lastNonZero after the noise ended")
      for (dc <- Seq(12345, -32768, 32767, -1)) {
        val out = h.run(Seq.fill(300)((dc, clamp16(-dc))), filter, what = s"DC $dc")
        val lastOff = out.lastIndexWhere(_._1 != dc)
        assert(lastOff < 64, s"filter $filter: DC $dc not reproduced exactly after sample $lastOff")
      }
      println(s"${NesAudio.FilterNames(filter)}: silent $lastNonZero samples after a noise burst; DC exact")
    }
  }
}

object NesAudioSpec {
  def signed16(v: BigInt): Int = { val i = v.toInt; if (i >= 32768) i - 65536 else i }
  def clamp16(v: Int): Int = v.max(-32768).min(32767)

  /** |H(e^jw)| of b0 (1 + 2 z^-1 + z^-2) / (1 + a1 z^-1 + a2 z^-2). */
  def response(b0: Double, a1: Double, a2: Double, f: Double, fs: Double = NesAudio.SampleRateHz): Double = {
    val w = 2 * math.Pi * f / fs
    def mag(c0: Double, c1: Double, c2: Double): Double = {
      val re = c0 + c1 * math.cos(w) + c2 * math.cos(2 * w)
      val im = -c1 * math.sin(w) - c2 * math.sin(2 * w)
      math.hypot(re, im)
    }
    mag(b0, 2 * b0, b0) / mag(1, a1, a2)
  }

  /** The designed biquad in double precision (no quantization). */
  final case class Ideal(cutoffHz: Double) {
    private val k = math.tan(math.Pi * cutoffHz / NesAudio.SampleRateHz)
    private val norm = 1.0 / (1.0 + math.sqrt(2.0) * k + k * k)
    val b0: Double = k * k * norm
    val a1: Double = 2.0 * (k * k - 1.0) * norm
    val a2: Double = (1.0 - math.sqrt(2.0) * k + k * k) * norm
    def impulse(n: Int): Seq[Double] = {
      val y = new Array[Double](n)
      for (i <- 0 until n) {
        val x = Seq(1.0, 2.0, 1.0).zipWithIndex.map { case (c, d) => if (i == d) c * b0 else 0.0 }.sum
        y(i) = x - (if (i >= 1) a1 * y(i - 1) else 0.0) - (if (i >= 2) a2 * y(i - 2) else 0.0)
      }
      y.toSeq
    }
  }

  /** Amplitude of the tone at `f` in `ys` (least squares: a cos + b sin + c), sample 0 = index `offset`. */
  def fitAmplitude(ys: Seq[Double], f: Double, offset: Int): Double = {
    val w = 2 * math.Pi * f / NesAudio.SampleRateHz
    val rows = ys.indices.map(i => Array(math.cos(w * (i + offset)), math.sin(w * (i + offset)), 1.0))
    val m = Array.ofDim[Double](3, 4)
    for ((row, y) <- rows.zip(ys); r <- 0 until 3) {
      for (c <- 0 until 3) m(r)(c) += row(r) * row(c)
      m(r)(3) += row(r) * y
    }
    for (p <- 0 until 3) {
      val pivot = (p until 3).maxBy(r => math.abs(m(r)(p)))
      val t = m(p); m(p) = m(pivot); m(pivot) = t
      for (r <- 0 until 3 if r != p) {
        val factor = m(r)(p) / m(p)(p)
        for (c <- p until 4) m(r)(c) -= factor * m(p)(c)
      }
    }
    math.hypot(m(0)(3) / m(0)(0), m(1)(3) / m(1)(1))
  }

  /**
   * Bit-exact model of [[NesAudioFilter]], one call per sample (the output as
   * it stands once the sample's computation is done).
   */
  final class Model(fadeSamples: Int = NesAudio.FadeSamples) {
    private val bq = NesAudio.Biquads
    private val x1 = Array(0L, 0L)
    private val x2 = Array(0L, 0L)
    private val y1 = Array.fill(6)(0L)
    private val y2 = Array.fill(6)(0L)
    private val fadeBits = Integer.numberOfTrailingZeros(fadeSamples)
    var selF = 0
    var selA = false
    var oldF = 0
    var oldA = false
    var alpha: Long = fadeSamples
    /** Largest |Y| of any filter before the state saturation, and how often it acted. */
    var peak = 0L
    var stateSaturations = 0

    private def sat16(v: Long): Int = v.max(-32768L).min(32767L).toInt

    def sample(l: Int, r: Int, filter: Int, attenuate: Boolean): (Int, Int) = {
      val x0 = Array(l.toLong, r.toLong)
      val w = Array.tabulate(2)(c => x0(c) + 2 * x1(c) + x2(c))
      for (c <- 0 until 2) { x2(c) = x1(c); x1(c) = x0(c) }
      if (alpha != fadeSamples) alpha += 1
      else if (filter != selF || attenuate != selA) {
        oldF = selF; oldA = selA; selF = filter; selA = attenuate; alpha = 1
      }
      for (k <- 0 until 3; c <- 0 until 2) {
        val g = 2 * k + c
        val acc = bq(k).g.toLong * (w(c) << NesAudio.WShift) + bq(k).na1.toLong * y1(g) + bq(k).na2.toLong * y2(g)
        val drop = NesAudio.AccFractionBits - NesAudio.StateFractionBits
        var q = (acc + (1L << (drop - 1))) >> drop
        peak = peak max math.abs(q)
        val max = (1L << (NesAudio.StateWidth - 1)) - 1
        if (q > max) { q = max; stateSaturations += 1 }
        if (q < -max - 1) { q = -max - 1; stateSaturations += 1 }
        y2(g) = y1(g)
        y1(g) = q
      }
      def setting(f: Int, a: Boolean, c: Int): Long = {
        val base = if (f == 0) x0(c) << NesAudio.StateFractionBits else y1(2 * (f - 1) + c)
        if (a) (base + (1L << NesAudio.StateFractionBits)) >> (NesAudio.StateFractionBits + 1)
        else (base + (1L << (NesAudio.StateFractionBits - 1))) >> NesAudio.StateFractionBits
      }
      def out(c: Int): Int =
        if (alpha == fadeSamples && selF == 0) sat16(setting(0, selA, c))
        else {
          val o = setting(oldF, oldA, c)
          val n = setting(selF, selA, c)
          sat16(o + ((alpha * (n - o) + fadeSamples / 2) >> fadeBits))
        }
      (out(0), out(1))
    }
  }
}
