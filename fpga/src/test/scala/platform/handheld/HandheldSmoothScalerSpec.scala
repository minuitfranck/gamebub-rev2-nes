package platform.handheld

import chisel3._
import lib.video.ColorRGB
import net.gamebub.core.pce.PceTreadle
import org.scalatest.freespec.AnyFreeSpec

import java.math.{MathContext, RoundingMode}
import scala.util.Random

/**
 * The Smooth screen filter (framework register 0x101C = 3, [[HandheldSmoothScaler]]): a random picture scanned
 * through the hardware the way the LCD path drives it (read-ahead position, framebuffer read `readDelay`
 * clocks later, scan along one axis with porches, the line buffer across it) gives, on every screen pixel,
 * exactly the sharp bilinear formula with the weights rounded to eighths; at whole-number scales, and with
 * the filter off, every pixel is the nearest-neighbor pixel as before.
 *
 * The reference here is the formula itself in 50-digit decimals, independent of the integer form the
 * hardware and [[HandheldSmoothScaler.check]] use. The simulator is treadle2 through [[PceTreadle]].
 */
class HandheldSmoothScalerSpec extends AnyFreeSpec {
  private val ReadDelay = 3

  class Harness(srcWidth: Int, srcHeight: Int, dstWidth: Int, dstHeight: Int, fastIsY: Boolean) extends Module {
    val io = IO(new Bundle {
      val mode = Input(UInt(2.W))
      val relX = Input(UInt(12.W))
      val relY = Input(UInt(12.W))
      val color = Input(ColorRGB(8))
      val srcX = Output(UInt())
      val srcY = Output(UInt())
      val out = Output(ColorRGB(8))
    })
    val lineLength = if (fastIsY) dstHeight + 3 else dstWidth + 3
    val scaler = Module(new HandheldSmoothScaler(srcWidth, srcHeight, lineLength, fastIsY, ReadDelay))
    val stepX = HandheldScreenFilter.step(srcWidth, dstWidth).U
    val stepY = HandheldScreenFilter.step(srcHeight, dstHeight).U
    scaler.io.in.enable := io.mode === HandheldScreenFilter.Smooth.U &&
      HandheldSmoothScaler.smoothable(srcWidth, srcHeight, dstWidth, dstHeight).B
    scaler.io.in.relX := io.relX
    scaler.io.in.relY := io.relY
    scaler.io.in.productX := io.relX * stepX
    scaler.io.in.productY := io.relY * stepY
    scaler.io.in.dstWidth := dstWidth.U
    scaler.io.in.dstHeight := dstHeight.U
    scaler.io.in.color := io.color
    io.srcX := scaler.io.srcX
    io.srcY := scaler.io.srcY
    io.out := scaler.io.out
  }

  private val mc = new MathContext(50, RoundingMode.HALF_EVEN)
  private def dec(x: Int) = BigDecimal(x, mc)

  /** The formula, one axis: (i0, i1, weight of i1 in eighths, rounded half up). */
  def reference(source: Int, destination: Int, n: Int): (Int, Int, Int) = {
    val s = dec(destination) / dec(source)
    val half = BigDecimal("0.5", mc)
    val u = (dec(n) + half) / s - half
    val i0 = u.setScale(0, BigDecimal.RoundingMode.FLOOR).toInt
    val f = u - dec(i0)
    val fp = (((f - half) * s) + half).max(dec(0)).min(dec(1))
    // 1e-30 guards exact halves against the last decimal digit (true values are never that close otherwise).
    val w = (fp * dec(8) + half + BigDecimal("1e-30", mc)).setScale(0, BigDecimal.RoundingMode.FLOOR).toInt
    (i0.max(0).min(source - 1), (i0 + 1).min(source - 1), w)
  }

  private def nearest(source: Int, destination: Int, n: Int): Int =
    ((n.toLong * HandheldScreenFilter.step(source, destination)) >> 16).toInt

  /**
   * Scan the whole picture (plus porches) and compare every screen pixel. Returns how many screen pixels
   * were blends (neither weight 0 nor 8 in some axis).
   */
  def run(src: (Int, Int), dst: (Int, Int), fastIsY: Boolean, mode: Int, smoothExpected: Boolean): Int = {
    val (srcWidth, srcHeight) = src
    val (dstWidth, dstHeight) = dst
    val name = s"HandheldSmoothScalerSpec_${srcWidth}x${srcHeight}_${dstWidth}x${dstHeight}_${if (fastIsY) "rot" else "row"}_$mode"
    val sim = PceTreadle(new Harness(srcWidth, srcHeight, dstWidth, dstHeight, fastIsY), name)
    val rnd = new Random(srcWidth * 1000 + dstWidth)
    val image = Array.fill(srcHeight, srcWidth)((rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256)))
    sim.poke("io_mode", BigInt(mode))
    sim.poke("reset", true)
    sim.step(2)
    sim.poke("reset", false)

    val (dstFast, dstSlow) = if (fastIsY) (dstHeight, dstWidth) else (dstWidth, dstHeight)
    val latency = ReadDelay + HandheldSmoothScaler.Latency
    // Screen positions in scan order, (fast, slow) relative to the picture, with 6 porch pixels before each
    // line and a few after, and a line before the picture.
    val scan = for (s <- -1 until dstSlow; f <- -6 until dstFast + 4) yield (f, s)
    val reads = new Array[(Int, Int)](scan.length)
    var bad = 0
    var blends = 0
    for (k <- scan.indices) {
      val (f, s) = scan(k)
      val (x, y) = if (fastIsY) (s, f) else (f, s)
      sim.poke("io_relX", BigInt(x & 0xFFF))
      sim.poke("io_relY", BigInt(y & 0xFFF))
      reads(k) = (sim.peek("io_srcX").toInt, sim.peek("io_srcY").toInt)
      // The framebuffer answers ReadDelay clocks after the address.
      val (cr, cg, cb) =
        if (k >= ReadDelay) {
          val (rx, ry) = reads(k - ReadDelay)
          if (rx < srcWidth && ry < srcHeight) image(ry)(rx) else (0, 0, 0)
        } else (0, 0, 0)
      sim.poke("io_color_r", BigInt(cr))
      sim.poke("io_color_g", BigInt(cg))
      sim.poke("io_color_b", BigInt(cb))
      // The output now is the pixel whose position was presented `latency` clocks ago.
      if (k >= latency) {
        val (pf, ps) = scan(k - latency)
        if (pf >= 0 && pf < dstFast && ps >= 0) {
          val (px, py) = if (fastIsY) (ps, pf) else (pf, ps)
          val got = (sim.peek("io_out_r").toInt, sim.peek("io_out_g").toInt, sim.peek("io_out_b").toInt)
          val want = if (smoothExpected) {
            val (x0, x1, wx) = reference(srcWidth, dstWidth, px)
            val (y0, y1, wy) = reference(srcHeight, dstHeight, py)
            if ((wx % 8) != 0 || (wy % 8) != 0) blends += 1
            val taps = Seq((x0, y0, (8 - wx) * (8 - wy)), (x1, y0, wx * (8 - wy)), (x0, y1, (8 - wx) * wy), (x1, y1, wx * wy))
            def channel(c: ((Int, Int, Int)) => Int) =
              (taps.map { case (sx, sy, w) => c(image(sy)(sx)) * w }.sum + 32) / 64
            (channel(_._1), channel(_._2), channel(_._3))
          } else {
            image(nearest(srcHeight, dstHeight, py))(nearest(srcWidth, dstWidth, px))
          }
          if (got != want) {
            if (bad < 5) println(s"$name: screen ($px, $py): got $got, want $want")
            bad += 1
          }
        }
      }
      sim.step(1)
    }
    sim.finish()
    assert(bad == 0, s"$name: $bad screen pixels differ")
    blends
  }

  "the integer form matches the formula for the rev2 LCD's modes of several source sizes" in {
    // NES / SNES / PCE / MD / GBA / GB / NGPC / WS on 480 x 320: 4:3, Fit and Stretch.
    for ((w, h) <- Seq((256, 240), (256, 224), (320, 224), (240, 160), (160, 144), (160, 152), (224, 144));
         (dw, dh) <- Seq((427, 320), (480 min (320 * w / h), 320 min (480 * h / w)), (480, 320))
         if HandheldSmoothScaler.smoothable(w, h, dw, dh)) {
      assert(HandheldSmoothScaler.check(w, dw), s"$w -> $dw")
      assert(HandheldSmoothScaler.check(h, dh), s"$h -> $dh")
      for (n <- 0 until dw) {
        val (i0, i1, wr) = HandheldSmoothScaler.reference(w, dw, n)
        assert((i0, i1, wr) == reference(w, dw, n), s"$w -> $dw, pixel $n")
      }
    }
  }

  // Small pictures at the NES's 4:3 and Fit factors on the rev2 LCD (256 -> 427 and 341, 240 -> 320), on
  // the rotated panel (rev2: the scan runs down the picture's columns) and a row-scanned one.
  "Smooth at 4:3 (rotated scan) is the formula" in {
    assert(run((48, 30), (80, 40), fastIsY = true, mode = 3, smoothExpected = true) > 0)
  }
  "Smooth at Fit (rotated scan) is the formula" in {
    assert(run((48, 30), (64, 40), fastIsY = true, mode = 3, smoothExpected = true) > 0)
  }
  "Smooth at 4:3 (row scan) is the formula" in {
    assert(run((48, 30), (80, 40), fastIsY = false, mode = 3, smoothExpected = true) > 0)
  }
  "Smooth at the NES's real 4:3 (256 x 240 -> 427 x 320, rotated scan) is the formula" in {
    assert(run((256, 240), (427, 320), fastIsY = true, mode = 3, smoothExpected = true) > 0)
  }
  "Smooth at the NES's real Fit (256 x 240 -> 341 x 320, rotated scan) is the formula" in {
    assert(run((256, 240), (341, 320), fastIsY = true, mode = 3, smoothExpected = true) > 0)
  }
  "Smooth at an integer scale (2x) is unchanged nearest-neighbor" in {
    run((24, 20), (48, 40), fastIsY = true, mode = 3, smoothExpected = false)
  }
  "Off at 4:3 is unchanged nearest-neighbor" in {
    run((48, 30), (80, 40), fastIsY = true, mode = 0, smoothExpected = false)
  }
  "LCD Grid / Scanlines at 4:3 leave the colors unchanged here" in {
    run((48, 30), (80, 40), fastIsY = true, mode = 1, smoothExpected = false)
    run((48, 30), (80, 40), fastIsY = true, mode = 2, smoothExpected = false)
  }
}
