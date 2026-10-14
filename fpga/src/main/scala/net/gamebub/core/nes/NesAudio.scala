package net.gamebub.core.nes

import chisel3._
import chisel3.util._

/**
 * Sound Filter (register 0x0000 bits 14:13; docs/nes-port-design.md): 0 Off
 * (the decimated APU output as before the setting existed), 1 Soft, 2 Softer,
 * 3 Speaker, each a 2nd-order Butterworth low-pass with the cut-off below, on
 * the decimator's samples ([[NesAudioFilter]]). The NES's own output has a
 * 14 kHz low-pass; without one the APU's square waves, triangle steps and
 * noise reach the speaker with all their edges (the owner: "crackly / not
 * crisp"). Ported from core-ngpc's NgpcAudio (r2.4), the arithmetic unchanged:
 * only the sample rate differs, so the coefficients do.
 *
 * One sample per 512 delivered core clocks: 41.95 kHz with the NTSC master
 * clock the core assumes. PAL withholds one core clock in ~110 (`palPace`),
 * 0.9 % fewer samples a second, which lowers the cut-offs by as much: the
 * filter runs in the core's own time, ahead of the rate adapter.
 */
object NesAudio {
  val SampleRateHz: Double = HandheldNes.MasterClockNtscHz / 512.0
  /** Cut-offs of Sound Filter codes 1, 2, 3 (-3 dB points). */
  val FilterCutoffsHz: Seq[Double] = Seq(10000.0, 6000.0, 3500.0)
  val FilterNames: Seq[String] = Seq("Off", "Soft", "Softer", "Speaker")
  /** Fractional bits of -a1 / -a2; g has two more, so 4 g = 1 + a1 + a2 exactly (unity DC gain). */
  val CoefFractionBits = 16
  /** Fractional bits the output history keeps below the output LSB. */
  val StateFractionBits = 8
  /**
   * Stored output history: 17 integer bits (sign included) and 8 fractional
   * bits, the DSP48E1's 25-bit A port. |y| <= 1.262 x 32768 for these
   * filters (the L1 norm of the Soft impulse response), so the saturation on
   * the stored value never acts; it only guarantees that nothing can wrap.
   */
  val StateWidth = 25
  /** Fractional bits of the accumulator: na (16) x Y (8), or g (18) x (w << WShift). */
  val AccFractionBits = CoefFractionBits + StateFractionBits
  /** w = x + 2 x1 + x2 is an integer; shifted up by this it meets g at the accumulator's scale. */
  val WShift = AccFractionBits - (CoefFractionBits + 2)
  /** A change of Sound Filter cross-fades over this many samples (6.1 ms). */
  val FadeSamples = 256

  /**
   * One low-pass as the hardware holds it: `na1` = -a1 x 2^16, `na2` = -a2 x
   * 2^16 (18-bit signed), `g` = 2^16 - na1 - na2 read with 18 fractional bits:
   * y = g (x + 2 x1 + x2) - a1 y1 - a2 y2.
   */
  final case class Biquad(cutoffHz: Double, na1: Int, na2: Int, g: Int) {
    def a1: Double = -na1.toDouble / (1 << CoefFractionBits)
    def a2: Double = -na2.toDouble / (1 << CoefFractionBits)
    def gain: Double = g.toDouble / (1 << (CoefFractionBits + 2))
  }

  /**
   * 2nd-order Butterworth low-pass by the bilinear transform with the cut-off
   * pre-warped (RBJ's cookbook LPF with Q = 1/sqrt(2)); both zeros at z = -1.
   */
  def butterworth(cutoffHz: Double, sampleRateHz: Double = SampleRateHz): Biquad = {
    val k = math.tan(math.Pi * cutoffHz / sampleRateHz)
    val norm = 1.0 / (1.0 + math.sqrt(2.0) * k + k * k)
    val a1 = 2.0 * (k * k - 1.0) * norm
    val a2 = (1.0 - math.sqrt(2.0) * k + k * k) * norm
    val na1 = math.round(-a1 * (1 << CoefFractionBits)).toInt
    val na2 = math.round(-a2 * (1 << CoefFractionBits)).toInt
    Biquad(cutoffHz, na1, na2, (1 << CoefFractionBits) - na1 - na2)
  }

  /** Sound Filter codes 1, 2, 3. */
  val Biquads: Seq[Biquad] = FilterCutoffsHz.map(butterworth(_))
}

/**
 * The three low-passes of [[NesAudio]] on a stereo stream, the level shift
 * and the cross-fade between settings.
 *
 * All three filters run all the time, each on the same input with its own
 * Direct Form I history (the input history is shared), so the output of
 * every setting is always a settled filter output. A change of `filter` or
 * `attenuate` (taken at a sample, never during a fade) cross-fades from the
 * old setting's output to the new one's over [[NesAudio.FadeSamples]]
 * samples. Seeding a filter from another's history instead makes a click
 * (a jump by the difference in group delay, up to four times the signal's own
 * largest step for a 1 kHz tone) or worse (full-scale high-frequency content
 * rings to about 2.8 x full scale).
 *
 * Arithmetic, per sample and channel (x: 16-bit input, Y = y x 2^8):
 * {{{
 *   acc  = g (x + 2 x1 + x2) << 6  +  na1 Y1  +  na2 Y2        (output LSB = 2^24)
 *   Y    = sat25((acc + 2^15) >> 16)
 *   out  = sat16((Y + 2^(7 + s)) >> (8 + s))                      s = 1 at -6 dB
 * }}}
 * One multiplier (25 x 18, operands, product and accumulator registered: a
 * DSP48E1) serves the 18 products of the filters and the 2 of the fade, in
 * 26 clocks after `inValid`; `inValid` must be at least 32 clocks apart (the
 * NES decimator's are 512 or more).
 *
 * Off, steady (no fade in progress): `out` is `in` registered every clock,
 * so the rate adapter gets the decimator's samples as before this module, 26
 * clocks later (with -6 dB: (in + 1) >> 1).
 *
 * core-ngpc's NgpcAudioFilter (r2.4) line for line: the NES is mono and has no
 * Sound Level, so HandheldNes gives both channels the same sample and ties
 * `attenuate` off, and the module stays the one tested on the NGPC.
 */
class NesAudioFilter(fadeSamples: Int = NesAudio.FadeSamples) extends Module {
  import NesAudio._
  require(isPow2(fadeSamples) && fadeSamples >= 2)
  val io = IO(new Bundle {
    /** The stereo sample; taken on `inValid`, and every clock while the output is Off. */
    val in = Input(Vec(2, SInt(16.W)))
    val inValid = Input(Bool())
    /** Sound Filter: 0 Off, 1 Soft, 2 Softer, 3 Speaker. */
    val filter = Input(UInt(2.W))
    /** Sound Level: true = -6 dB. */
    val attenuate = Input(Bool())
    val out = Output(Vec(2, SInt(16.W)))
    /** One clock per sample, 26 clocks after `inValid`: `out` holds this sample's value (in every setting). */
    val outValid = Output(Bool())
  })

  val fadeBits = log2Ceil(fadeSamples)
  val filters = Biquads.length
  /** Filter state is kept per (filter k, channel c) pair, group 2 k + c. */
  val groups = 2 * filters
  require(filters == 3)

  //////////////////////////////////
  // Input history (shared) and the sample in progress
  //////////////////////////////////
  val x0 = RegInit(VecInit(Seq.fill(2)(0.S(16.W))))
  val x1 = RegInit(VecInit(Seq.fill(2)(0.S(16.W))))
  val x2 = RegInit(VecInit(Seq.fill(2)(0.S(16.W))))
  /** x + 2 x1 + x2 of the sample in progress (18 bits hold 4 x -32768). */
  val w = RegInit(VecInit(Seq.fill(2)(0.S(18.W))))
  /** Output history of each group, [[NesAudio.StateFractionBits]] fractional bits. */
  val y1 = RegInit(VecInit(Seq.fill(groups)(0.S(StateWidth.W))))
  val y2 = RegInit(VecInit(Seq.fill(groups)(0.S(StateWidth.W))))

  //////////////////////////////////
  // Setting in force and the fade (alpha = fadeSamples: no fade)
  //////////////////////////////////
  val selFilter = RegInit(0.U(2.W))
  val selAttenuate = RegInit(false.B)
  val oldFilter = RegInit(0.U(2.W))
  val oldAttenuate = RegInit(false.B)
  val alpha = RegInit(fadeSamples.U((fadeBits + 1).W))
  val fading = alpha =/= fadeSamples.U

  when (io.inValid) {
    for (c <- 0 until 2) {
      x0(c) := io.in(c)
      w(c) := (io.in(c).pad(19) + (x1(c).pad(19) << 1) + x2(c).pad(19))(17, 0).asSInt
      x1(c) := io.in(c)
      x2(c) := x1(c)
    }
    when (fading) {
      alpha := alpha + 1.U
    } .elsewhen (io.filter =/= selFilter || io.attenuate =/= selAttenuate) {
      oldFilter := selFilter
      oldAttenuate := selAttenuate
      selFilter := io.filter
      selAttenuate := io.attenuate
      alpha := 1.U
    }
  }

  /** One setting's output for channel c (integer, not yet saturated): the filter's y1 or the input. */
  def settingOut(filter: UInt, attenuate: Bool, c: Int): SInt = {
    val base = VecInit((x0(c) << StateFractionBits).pad(StateWidth) +: (0 until filters).map(k => y1(2 * k + c)))(filter)
    Mux(attenuate, (base +& (1 << (StateFractionBits)).S) >> (StateFractionBits + 1),
      (base +& (1 << (StateFractionBits - 1)).S) >> StateFractionBits)
  }
  val oldOut = VecInit((0 until 2).map(c => settingOut(oldFilter, oldAttenuate, c)))
  val newOut = VecInit((0 until 2).map(c => settingOut(selFilter, selAttenuate, c)))
  val fadeDiff = VecInit((0 until 2).map(c => newOut(c) -& oldOut(c)))

  //////////////////////////////////
  // Sequencer: steps 0-17 issue the filter products (group j = step / 3:
  // g x (w << 6), na1 x Y1, na2 x Y2), a group's result is captured three
  // clocks after its last product (steps 5, 8, ... 20); steps 21-22 issue the
  // fade products alpha x (new - old), captured at 24-25.
  //////////////////////////////////
  val fadeStep = 3 * groups + 3
  val lastStep = fadeStep + 1
  val running = RegInit(false.B)
  val step = RegInit(0.U(5.W))
  when (io.inValid) {
    running := true.B
    step := 0.U
  } .elsewhen (running) {
    step := step + 1.U
    when (step === lastStep.U) {
      running := false.B
    }
  }

  import NesAudioFilter.{Entry, Op}
  // a operand: 0-1 w(c) << WShift, 2-7 Y1(group), 8-13 Y2(group), 14-15 fadeDiff(c)
  // b operand: 0-2 g(k), 3-5 na1(k), 6-8 na2(k), 9 alpha
  val table: Seq[Option[Entry]] = (0 until 32).map { s =>
    if (s < 3 * groups) {
      val group = s / 3
      val k = group / 2
      val c = group % 2
      Some(s % 3 match {
        case 0 => Entry(c, k, first = true, last = false, fade = false, group)
        case 1 => Entry(2 + group, 3 + k, first = false, last = false, fade = false, group)
        case _ => Entry(8 + group, 6 + k, first = false, last = true, fade = false, group)
      })
    } else if (s >= fadeStep && s <= lastStep) {
      Some(Entry(14 + s - fadeStep, 9, first = true, last = true, fade = true, s - fadeStep))
    } else {
      None
    }
  }
  val tableValid = VecInit(table.map(e => e.isDefined.B))
  val tableA = VecInit(table.map(e => e.map(_.a).getOrElse(0).U(4.W)))
  val tableB = VecInit(table.map(e => e.map(_.b).getOrElse(0).U(4.W)))
  val tableFirst = VecInit(table.map(e => e.exists(_.first).B))
  val tableLast = VecInit(table.map(e => e.exists(_.last).B))
  val tableFade = VecInit(table.map(e => e.exists(_.fade).B))
  val tableGroup = VecInit(table.map(e => e.map(_.group).getOrElse(0).U(3.W)))

  val op0 = Wire(new Op)
  op0.valid := running && tableValid(step)
  op0.first := tableFirst(step)
  op0.last := tableLast(step)
  op0.fade := tableFade(step)
  op0.group := tableGroup(step)

  val aOptions = VecInit((Seq(w(0) << WShift, w(1) << WShift) ++ y1 ++ y2 ++ fadeDiff).map(_.pad(StateWidth)))
  val bOptions = VecInit(
    (Biquads.map(_.g) ++ Biquads.map(_.na1) ++ Biquads.map(_.na2)).map(_.S(18.W)) ++
      Seq(alpha.zext.pad(18)) ++ Seq.fill(6)(0.S(18.W)))
  require(aOptions.length == 16 && bOptions.length == 16)

  // The multiplier: operand, product and accumulator registers (DSP48E1 AREG/BREG, MREG, PREG).
  val aReg = RegNext(aOptions(tableA(step)), 0.S)
  val bReg = RegNext(bOptions(tableB(step)), 0.S)
  val op1 = RegNext(op0, 0.U.asTypeOf(new Op))
  val mReg = RegNext(aReg * bReg, 0.S)
  val op2 = RegNext(op1, 0.U.asTypeOf(new Op))
  val acc = RegInit(0.S(48.W))
  when (op2.valid) {
    acc := Mux(op2.first, 0.S, acc) + mReg
  }
  val op3 = RegNext(op2, 0.U.asTypeOf(new Op))

  // A filter result: round to the stored precision, saturate (never acts, see StateWidth).
  locally {
    val drop = AccFractionBits - StateFractionBits
    val rounded = (acc + (1 << (drop - 1)).S) >> drop
    val max = ((1 << (StateWidth - 1)) - 1).S
    val min = (-(1 << (StateWidth - 1))).S
    val y = Mux(rounded > max, max, Mux(rounded < min, min, rounded))(StateWidth - 1, 0).asSInt
    when (op3.valid && op3.last && !op3.fade) {
      y1(op3.group) := y
      y2(op3.group) := y1(op3.group)
    }
  }

  //////////////////////////////////
  // Output
  //////////////////////////////////
  def sat16(v: SInt): SInt = Mux(v > 32767.S, 32767.S, Mux(v < (-32768).S, (-32768).S, v))(15, 0).asSInt
  val out = RegInit(VecInit(Seq.fill(2)(0.S(16.W))))
  val steadyOff = !fading && selFilter === 0.U
  for (c <- 0 until 2) {
    when (steadyOff) {
      out(c) := Mux(selAttenuate, (io.in(c) +& 1.S) >> 1, io.in(c))
    } .elsewhen (op3.valid && op3.fade && op3.group === c.U) {
      out(c) := sat16(oldOut(c) +& ((acc + (fadeSamples / 2).S) >> fadeBits))
    }
  }
  io.out := out
  // The right channel's fade product is captured last (step 25).
  io.outValid := RegNext(op3.valid && op3.fade && op3.group === 1.U, false.B)
}

object NesAudioFilter {
  /** A product in the multiplier's pipeline. */
  class Op extends Bundle {
    val valid = Bool()
    /** The accumulator starts from this product. */
    val first = Bool()
    /** The accumulator holds a result once this product is in. */
    val last = Bool()
    /** A fade product (group = channel); else a filter product (group = 2 k + c). */
    val fade = Bool()
    val group = UInt(3.W)
  }

  /** One step of the sequencer: operand selections and the product's role. */
  final case class Entry(a: Int, b: Int, first: Boolean, last: Boolean, fade: Boolean, group: Int)
}
