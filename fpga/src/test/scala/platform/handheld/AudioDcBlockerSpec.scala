package platform.handheld

import net.gamebub.core.pce.PceTreadle
import org.scalatest.freespec.AnyFreeSpec

import scala.util.Random

/**
 * [[AudioDcBlocker]] (2026-09-24) against an exact integer model ([[AudioDcBlockerSpec.Model]]) on every input
 * tried, then its response at the framework's update rate (48 kHz): an offset decays with the time constant of
 * MiSTer's `DC_blocker` (the pole 1 - 2^-9), a tone passes, 15 Hz is at -3 dB, the output saturates at full
 * scale, and between updates the output follows the input at once.
 *
 * The simulator is treadle2 through [[PceTreadle]] (no verilator here).
 */
class AudioDcBlockerSpec extends AnyFreeSpec {
  import AudioDcBlockerSpec._

  lazy val sim: PceTreadle.Sim = PceTreadle(new AudioDcBlocker, "AudioDcBlockerSpec")

  /**
   * Reset, then apply `xs` one per clock with `update` on every `every`-th clock, checking each output (the
   * registered result of the clock its input was applied in) against [[Model]]; returns the outputs.
   */
  def run(xs: Seq[Int], every: Int, what: String): Seq[Int] = {
    sim.poke("io_update", false)
    sim.poke("io_in", BigInt(0))
    sim.poke("reset", true)
    sim.step(2)
    sim.poke("reset", false)
    val model = new Model
    xs.zipWithIndex.map { case (x, i) =>
      val update = i % every == 0
      sim.poke("io_in", BigInt(x))
      sim.poke("io_update", update)
      val want = model.out(x)
      model.step(x, update)
      sim.step(1)
      val got = signed16(sim.peek("io_out"))
      assert(got == want, s"$what, sample $i (in $x, update $update): hardware $got, model $want")
      got
    }
  }

  "equal to the exact model on every input tried, with an update every clock and less often" in {
    val rnd = new Random(24)
    val inputs: Seq[(String, Seq[Int])] = Seq(
      "random" -> Seq.fill(20000)(rnd.between(-32768, 32768)),
      "full-scale steps, random dwell" -> {
        val b = Seq.newBuilder[Int]
        var high = false
        for (_ <- 0 until 100) { b ++= Seq.fill(rnd.between(1, 400))(if (high) 32767 else -32768); high = !high }
        b.result()
      },
      "an unsigned mixer: 0..30000 with a tone" -> Seq.tabulate(20000)(i => 15000 + (if ((i / 40) % 2 == 0) 15000 else -15000)),
      "a long +20000 offset, then full scale down (saturates)" -> (Seq.fill(15000)(20000) ++ Seq.fill(50)(-32768) ++ Seq.fill(50)(32767)),
    )
    for ((what, xs) <- inputs; every <- Seq(1, 7, 1000)) {
      val outs = run(xs, every, s"$what, update every $every")
      assert(outs.forall(o => o >= -32768 && o <= 32767))
    }
  }

  "an offset of +4000 decays with the time constant of MiSTer's DC_blocker (2^9 updates, 10.7 ms at 48 kHz)" in {
    val outs = run(Seq.fill(6200)(4000), 1, "a +4000 offset")
    val a = 1.0 - 1.0 / 512
    assert(outs.head == 4000, s"the first output is the step: ${outs.head}")
    for (n <- Seq(100, 512, 1024, 2048)) {
      val want = 4000 * math.pow(a, n)
      assert(math.abs(outs(n) - want) <= 2.0, f"after $n updates: ${outs(n)}, want $want%.1f")
    }
    assert(math.abs(outs(5000)) <= 1, s"after 5000 updates (104 ms): ${outs(5000)}")
    assert(outs(6100) == 0, s"after 6100 updates: ${outs(6100)}")
  }

  "at 48 kHz a 1 kHz tone passes, 50 Hz nearly so, 15 Hz at -3 dB, as the filter's response" in {
    val fs = 48000.0
    val a = 1.0 - 1.0 / 512
    for (f <- Seq(15.0, 50.0, 1000.0)) {
      val period = fs / f
      val settle = 4000
      val measure = (math.ceil(3 * period)).toInt
      val xs = Seq.tabulate(settle + measure)(n => math.round(10000 * math.sin(2 * math.Pi * f * n / fs)).toInt)
      val outs = run(xs, 1, f"a $f%.0f Hz tone")
      val peak = outs.drop(settle).map(o => math.abs(o)).max / 10000.0
      val w = 2 * math.Pi * f / fs
      val num = math.hypot(1 - math.cos(w), math.sin(w))
      val den = math.hypot(1 - a * math.cos(w), a * math.sin(w))
      val want = num / den
      assert(math.abs(peak - want) <= 0.02, f"$f%.0f Hz: gain $peak%.4f, want $want%.4f")
    }
  }
}

object AudioDcBlockerSpec {
  def signed16(v: BigInt): Int = {
    val i = (v & 0xFFFF).toInt
    if (i >= 0x8000) i - 0x10000 else i
  }

  /** The blocker's arithmetic on integers: D the offset * 2^9, the output registered. */
  class Model {
    var d = 0L
    def offset: Long = Math.floorDiv(d, 512L)
    def out(in: Int): Int = (in - offset).max(-32768L).min(32767L).toInt
    def step(in: Int, update: Boolean): Unit = if (update) d = d + in - offset
  }
}
