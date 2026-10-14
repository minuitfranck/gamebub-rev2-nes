package platform.handheld

import chisel3._
import chisel3.util._
import lib.video.ColorRGB

/**
 * "Smooth" LCD screen filter (framework register 0x101C = 3): sharp bilinear scaling at fractional scales.
 *
 * At a scale like 427/256 nearest-neighbor draws some source pixels 1 screen pixel wide and others 2. Sharp
 * bilinear keeps every source pixel crisp and blends only the one screen pixel that straddles a source-pixel
 * boundary, by how much of that screen pixel lies on each side. Per axis, for screen pixel n of a picture of
 * N source pixels drawn over D screen pixels (s = D / N):
 * {{{
 *   u  = (n + 0.5) / s - 0.5 ;  i0 = floor(u) ;  f = u - i0
 *   f' = clamp((f - 0.5) * s + 0.5, 0, 1)
 *   i1 = min(i0 + 1, N - 1) ;  i0 = clamp(i0, 0, N - 1)
 * }}}
 * and the color is the bilinear blend of the four source pixels with weights f'x, f'y, each rounded to
 * eighths (half up). Worked out, f' = ((n + 1) * N - (i0 + 1) * D) / N clamped: the part of screen pixel n
 * right of the boundary between source pixels i0 and i0 + 1. Most pixels get f' = 0 or 1 (one source pixel).
 *
 * Hardware: the framebuffer has a single read port on the display clock, one pixel per clock. Each screen
 * pixel reads the source pixel at its far edge (i1 when the pixel straddles a boundary, its only source
 * pixel otherwise); i0 is then what the previous screen pixel read. Along the scan (fast) axis that is the color one clock earlier; across it (slow axis)
 * it is the previous scan line, kept in a line buffer (one entry per screen pixel along the fast axis,
 * holding the fast-axis blend, 3 x 11 bits: one RAMB18 up to 512 pixels). The weight of the pixel read is
 * 8 f' = round(8 e / N) with e = (rel + 1) * N - read * D exact in integers (see `axis`).
 * [[HandheldSmoothScaler.check]] proves at elaboration, for each mode the smooth filter is used in, that this
 * reads and weighs exactly what the formula above asks for, and elaboration fails otherwise.
 *
 * Only for a picture scaled up by a non-integer factor ([[HandheldSmoothScaler.smoothable]]); at integer
 * scales, and when off, the scaler reads nearest-neighbor exactly as before and the color passes straight
 * through, unchanged.
 *
 * No latency: the blend is combinational after the framebuffer's color (the weights and the line buffer read
 * are prepared during the read), so the LCD path reads ahead exactly as without the filter. The rev2 panel's
 * pixel counter starts each line only 6 clocks before its first pixel, as much as the read and the cores'
 * video filters already take; the first version (NES r2.6, SNES 0.0.3 r2.3, PCE r2.9, MD r2.6) added 2 clocks
 * of its own and so read the first 2 pixels of every scan line (the picture's top 2 rows in 4:3 and Fit)
 * at the end of the previous line, a strip of wrong pixels, even with the filter off.
 *
 * Picture areas (a core with a 'videoArea', see VideoAreaV0, e.g. the GB-MiSTer port's 160 x 144 window,
 * 240 x 160 Zoomed and 256 x 224 Whole): the scaler is built for the list of area sizes and the `area` input
 * picks one at run time, which selects the source size N in rel * N and the rounding thresholds; the line
 * buffer is shared. The source pixel is then relative to the area's origin. With one size (no areas) the
 * hardware is the single-size scaler's, unchanged.
 */
object HandheldSmoothScaler {
  /** Weights are in eighths. */
  val Levels = 8
  /** Clocks from the color input to the color output: none (see above). */
  val Latency = 0

  /**
   * How far ahead of the display the LCD path reads the framebuffer: 3 clocks of reading, the core's video
   * filter and this filter's [[Latency]]. The display driver's pixel counter starts each line that many clocks
   * before the first pixel at most (the rev2 ILI9488: hsync 3 + back porch 3 = 6), or the first pixels of each
   * scan line are read at the end of the previous one (see HandheldLcdScanSpec).
   */
  def framebufferReadDelay(videoFilterLatency: Int): Int = 3 + videoFilterLatency + Latency

  /** The smooth filter applies to a `srcWidth` x `srcHeight` picture drawn at `dstWidth` x `dstHeight`:
   *  enlarged in both directions, but not by a whole number in both (there the pixels are already even). */
  def smoothable(srcWidth: Int, srcHeight: Int, dstWidth: Int, dstHeight: Int): Boolean =
    dstWidth >= srcWidth && dstHeight >= srcHeight && !(dstWidth % srcWidth == 0 && dstHeight % srcHeight == 0)

  /** The formula above for one axis: (i0, i1, weight of i1 in eighths), in exact integers. */
  def reference(source: Int, destination: Int, n: Int): (Int, Int, Int) = {
    // u = ((2n + 1) N - D) / 2D, so i0 = floor of that; f' = ((n + 1) N - (i0 + 1) D) / N.
    val i0 = Math.floorDiv((2 * n + 1) * source - destination, 2 * destination)
    val num = ((n + 1) * source - (i0 + 1) * destination).max(0).min(source)
    val w = Math.floorDiv(2 * Levels * num + source, 2 * source)
    (i0.max(0).min(source - 1), (i0 + 1).min(source - 1), w)
  }

  /** The scaler's step (as in the LCD path): ceil(65536 * source / destination). */
  def step(source: Int, destination: Int): Int = ((65536L * source + destination - 1) / destination).toInt

  /** What the hardware does for one axis: (source pixel read, weight of that pixel in eighths). */
  def hardware(source: Int, destination: Int, n: Int): (Int, Int) = {
    val k = ((n * step(source, destination).toLong) >> 16).toInt
    val left = if (k * destination > n * source) k - 1 else k
    val e = (n + 1) * source - (left + 1) * destination
    if (e > 0) (left + 1, threshold(source).count(e >= _)) else (left, Levels)
  }

  /** e >= threshold(N)(j - 1) for j = 1 .. 8 counts round(8 e / N) (half up): 16 e >= (2j - 1) N. */
  def threshold(source: Int): Seq[Int] = (1 to Levels).map(j => ((2 * j - 1) * source + 15) / 16)

  /**
   * Whether the hardware matches [[reference]] on every screen pixel of an axis: the pixel read, blended with
   * the previous pixel's read, gives the same source pixels and weights.
   */
  def check(source: Int, destination: Int): Boolean = (0 until destination).forall { n =>
    val (i0, i1, w) = reference(source, destination, n)
    val (read, wr) = hardware(source, destination, n)
    val prev = if (n > 0) hardware(source, destination, n - 1)._1 else -1
    def weights(pairs: (Int, Int)*): Map[Int, Int] =
      pairs.filter(_._2 != 0).groupMapReduce(_._1)(_._2)(_ + _)
    weights(i0 -> (Levels - w), i1 -> w) == weights(prev -> (Levels - wr), read -> wr)
  }

  /** Exact per-channel blend of two colors: a * (8 - w) + b * w, as a (bits + 3)-bit value. */
  private def lerp(a: UInt, b: UInt, w: UInt): UInt = {
    val bits = a.getWidth + 3
    val out = (a.zext << 3) + (b.zext - a.zext) * w.zext
    val u = out.asUInt
    u(bits - 1, 0)
  }
}

/**
 * See [[HandheldSmoothScaler$ HandheldSmoothScaler]]. The inputs are the LCD scaler's read-ahead position
 * (relative to the picture origin, wrapped at the pixel counter's width like the scaler's), its products
 * rel * step and the picture size of the current mode; `srcX` / `srcY` are the source pixel to
 * read there. `color` is that pixel's color `readDelay` clocks later; `out` is the picture's color at the
 * same time (combinational, [[HandheldSmoothScaler.Latency]] = 0).
 *
 * @param lineLength screen pixels along the scan axis (the line buffer's depth)
 * @param fastIsY    the display scans the picture's columns (rotated panel): X is the slow axis
 * @param areas      the picture areas' sizes (width, height), each inside `srcWidth` x `srcHeight`, when the
 *                   core draws through areas; `area` (an extra input, only with two or more) then selects
 *                   one by its index. Empty: one area, the whole `srcWidth` x `srcHeight` frame.
 */
class HandheldSmoothScaler(srcWidth: Int, srcHeight: Int, lineLength: Int, fastIsY: Boolean, readDelay: Int,
  areas: Seq[(Int, Int)] = Nil)
  extends Module {
  import HandheldSmoothScaler._
  require(readDelay >= 2, "the weights take two clocks")
  require(areas.forall { case (w, h) => w > 0 && h > 0 && w <= srcWidth && h <= srcHeight },
    s"smooth filter: an area in $areas is not inside the $srcWidth x $srcHeight frame")
  private val sizes = if (areas.isEmpty) Seq((srcWidth, srcHeight)) else areas

  val io = IO(new Bundle {
    val in = Input(new Bundle {
      /** Smooth filter on and the current mode [[HandheldSmoothScaler.smoothable]]. */
      val enable = Bool()
      val relX = UInt(12.W)
      val relY = UInt(12.W)
      val productX = UInt(32.W)
      val productY = UInt(32.W)
      val dstWidth = UInt(12.W)
      val dstHeight = UInt(12.W)
      val color = ColorRGB(8)
    })
    val srcX = Output(UInt(log2Ceil(srcWidth).W))
    val srcY = Output(UInt(log2Ceil(srcHeight).W))
    val out = Output(ColorRGB(8))
  })
  /** With two or more areas: the index in `areas` of the one shown (like the framebuffer read, no delay). */
  val area = if (sizes.size > 1) Some(IO(Input(UInt(log2Ceil(sizes.size).W)))) else None
  private val areaReg = area.map(RegNext(_))

  /** `f` of the current area's source size along one axis (a constant with one area). */
  private def pick(sources: Seq[Int], index: Option[UInt])(f: Int => UInt): UInt = index match {
    case None => f(sources.head)
    case Some(i) => MuxLookup(i, f(sources.head))(sources.zipWithIndex.map { case (s, n) => n.U -> f(s) })
  }

  /**
   * One axis: the source pixel to read (combinational) and its weight in eighths (two clocks later).
   * The scaler's product gives the source pixel under the screen pixel's near edge, floor(rel * N / D), or
   * one too many (the step is rounded up): k * D > rel * N says so, exactly. The next source pixel starts at
   * screen position (left + 1) * D / N; e > 0 when that is inside this screen pixel, which then reads it.
   * `frame` is the frame's size along the axis (the output's width), `sources` N for each area.
   */
  private def axis(frame: Int, sources: Seq[Int], rel: UInt, product: UInt, dst: UInt): (UInt, UInt) = {
    val bits = log2Ceil(frame)
    val nearest = (product >> 16)(bits - 1, 0)
    val k = product >> 16
    val kd = k * dst
    val relN = pick(sources, area)(source => rel * source.U)
    val over = kd > relN
    val left = Mux(over, k - 1.U, k)
    val leftD = Mux(over, kd - dst, kd)
    val e = (relN +& pick(sources, area)(_.U)).zext - (leftD +& dst).zext
    val straddle = e > 0.S
    val read = Mux(straddle, left + 1.U, left)(bits - 1, 0)
    val eReg = RegNext(e)
    val straddleReg = RegNext(straddle)
    val weight = Mux(straddleReg,
      pick(sources, areaReg)(source => PopCount(threshold(source).map(t => eReg >= t.S))), Levels.U)
    (Mux(io.in.enable, read, nearest), RegNext(weight))
  }
  val (readX, weightX) = axis(srcWidth, sizes.map(_._1), io.in.relX, io.in.productX, io.in.dstWidth)
  val (readY, weightY) = axis(srcHeight, sizes.map(_._2), io.in.relY, io.in.productY, io.in.dstHeight)
  io.srcX := readX
  io.srcY := readY

  // Line up with the color, `readDelay` clocks after the position: the weights are two clocks along, the
  // line buffer's read address one clock early (its data comes a clock after the address).
  val (relFast, dstFast) = if (fastIsY) (io.in.relY, io.in.dstHeight) else (io.in.relX, io.in.dstWidth)
  val (weightFast, weightSlow) = if (fastIsY) (weightY, weightX) else (weightX, weightY)
  val wFast = ShiftRegister(weightFast, readDelay - 2)
  val wSlow = ShiftRegister(weightSlow, readDelay - 2)
  val indexEarly = ShiftRegister(relFast(log2Ceil(lineLength) - 1, 0), readDelay - 1)
  val index = RegNext(indexEarly)
  val inLine = ShiftRegister(relFast < dstFast && relFast < lineLength.U, readDelay)
  val enable = ShiftRegister(io.in.enable, readDelay)

  // Fast axis: blend with the previous pixel along the scan. The first pixel of a scan line never blends
  // (its weight is 8), so what the previous clock read (the porch) does not matter.
  val color = io.in.color
  val colorPrev = RegNext(color)
  val channels = Seq((color.r, colorPrev.r), (color.g, colorPrev.g), (color.b, colorPrev.b))
  val blendFast = VecInit(channels.map { case (c, p) => lerp(p, c, wFast) })

  // Slow axis: blend with the previous line's fast-axis blend at the same place along the scan (read a clock
  // ahead; this clock's write is to the entry before it, or none in the porch). The first line's weight is 8.
  val lineBuffer = SyncReadMem(lineLength, Vec(3, UInt(11.W)))
  val linePrev = lineBuffer.read(indexEarly)
  when (inLine) {
    lineBuffer.write(index, blendFast)
  }
  val blended = VecInit((0 until 3).map { i =>
    val v = lerp(linePrev(i), blendFast(i), wSlow)  // in 64ths
    ((v +& 32.U) >> 6)(7, 0)
  })

  val out = Wire(ColorRGB(8))
  out.r := Mux(enable, blended(0), color.r)
  out.g := Mux(enable, blended(1), color.g)
  out.b := Mux(enable, blended(2), color.b)
  io.out := out
}
