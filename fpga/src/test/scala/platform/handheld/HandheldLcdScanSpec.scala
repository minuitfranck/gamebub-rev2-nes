package platform.handheld

import chisel3._
import chisel3.util._
import lib.video.ColorRGB
import net.gamebub.core.pce.PceTreadle
import org.scalatest.freespec.AnyFreeSpec
import platform.handheld.display.ILI9488

import scala.util.Random

/**
 * The LCD scan-out end to end on the rev2 panel: the real display driver (ILI9488, rotated: each scan line is
 * a column of the picture, the pixel counter starting 6 clocks before the first pixel) at the core's real
 * display clock and frame period, the read-ahead and scaling of HandheldTop's LCD path, the smooth filter and
 * a framebuffer that answers `3 + video filter latency` clocks after the address. Every pixel the panel is
 * sent while its data enable is high is checked, so the first and the last pixel of every scan line: with
 * Off exactly the nearest-neighbor pixel, with LCD Grid that darkened at the block edges, with Smooth the
 * sharp bilinear formula, in every scaling mode (with the picture's vertical centering where it does not fill
 * the column), and black outside the picture. The picture is random, so no two rows or columns agree.
 */
class HandheldLcdScanSpec extends AnyFreeSpec {
  /** A picture area (as VideoAreaV0.Area): the rectangle of the frame the LCD shows. */
  case class Rect(x: Int, y: Int, width: Int, height: Int)

  private val ScreenWidth = 480
  private val ScreenHeight = 320
  private val FilterLatency = 3

  /** The rev2 display clock of a core: the lowest the ILI9488 accepts from its MMCM (as each core computes it). */
  def displayClockHz(mmcmVcoHz: Double): Int =
    (mmcmVcoHz / (mmcmVcoHz / ILI9488.getClockDisplayHz(1.0 / 60)._1).floor.toInt).toInt

  /** HandheldTop's four scaling modes (register 0x1018) for one area on the rev2 LCD: (dstWidth, dstHeight). */
  def scaleModes(w: Int, h: Int): Seq[(Int, Int)] = {
    val integerScale = (ScreenWidth / w).min(ScreenHeight / h).max(1)
    val fit =
      if (ScreenWidth * h <= ScreenHeight * w) (ScreenWidth, ScreenWidth * h / w)
      else (ScreenHeight * w / h, ScreenHeight)
    Seq((w * integerScale, h * integerScale), fit, (((ScreenHeight * 4 + 1) / 3).min(ScreenWidth), ScreenHeight),
      (ScreenWidth, ScreenHeight))
  }

  class Harness(frame: (Int, Int), areas: Seq[Rect], withAreas: Boolean, area: Int, dst: (Int, Int),
    filter: Int, clockHz: Int, framePeriod: Double) extends Module {
    val io = IO(new Bundle {
      val readX = Output(UInt(16.W))
      val readY = Output(UInt(16.W))
      val color = Input(ColorRGB(8))
      val out = Output(ColorRGB(8))
      val enable = Output(Bool())
      val screenX = Output(UInt(16.W))
      val screenY = Output(UInt(16.W))
    })
    val (dstWidth, dstHeight) = dst
    val a = areas(area)
    val driver = Module(new ILI9488(clockHz, framePeriod))
    driver.io.lastRenderedFrame := 0.U
    // As HandheldTop's LCD path on rev2 (displayRotate).
    val dpiX = driver.io.pixelY
    val dpiY = driver.io.pixelX
    val offsetX = ((ScreenWidth - dstWidth) / 2).max(0)
    val offsetY = ((ScreenHeight - dstHeight) / 2).max(0)
    val stepX = HandheldScreenFilter.step(a.width, dstWidth)
    val stepY = HandheldScreenFilter.step(a.height, dstHeight)
    val framebufferReadDelay = HandheldSmoothScaler.framebufferReadDelay(FilterLatency)
    val videoRelX = dpiX + 0.U - offsetX.U
    val videoRelY = dpiY + framebufferReadDelay.U - offsetY.U
    val videoProductX = videoRelX * stepX.U
    val videoProductY = videoRelY * stepY.U
    val scaler = Module(new HandheldSmoothScaler(frame._1, frame._2, ScreenHeight, fastIsY = true,
      readDelay = 3 + FilterLatency, areas = if (withAreas) areas.map(r => (r.width, r.height)) else Nil))
    scaler.area.foreach(_ := area.U)
    scaler.io.in.enable := (filter == HandheldScreenFilter.Smooth &&
      HandheldSmoothScaler.smoothable(a.width, a.height, dstWidth, dstHeight)).B
    scaler.io.in.relX := videoRelX
    scaler.io.in.relY := videoRelY
    scaler.io.in.productX := videoProductX
    scaler.io.in.productY := videoProductY
    scaler.io.in.dstWidth := dstWidth.U
    scaler.io.in.dstHeight := dstHeight.U
    scaler.io.in.color := io.color
    val edge = HandheldScreenFilter.edge(filter.U(2.W),
      HandheldScreenFilter.filterable(a.width, a.height, dstWidth, dstHeight).B,
      HandheldScreenFilter.lastInBlock(videoProductX, stepX.U), HandheldScreenFilter.lastInBlock(videoProductY, stepY.U))
    val darken = ShiftRegister(edge, framebufferReadDelay)
    io.readX := scaler.io.srcX +& a.x.U
    io.readY := scaler.io.srcY +& a.y.U
    val inBounds = dpiX >= offsetX.U && dpiX < offsetX.U +& dstWidth.U && dpiY >= offsetY.U && dpiY < offsetY.U +& dstHeight.U
    val picture = scaler.io.out
    io.out := Mux(inBounds, Mux(darken, HandheldScreenFilter.darken(picture), picture), 0.U.asTypeOf(io.out))
    io.enable := driver.io.signals.enable
    io.screenX := dpiX
    io.screenY := dpiY
  }

  private def nearest(source: Int, destination: Int, n: Int): Int =
    ((n.toLong * HandheldScreenFilter.step(source, destination)) >> 16).toInt

  /**
   * One frame of the panel. Returns the number of pixels checked (the whole panel).
   */
  def run(name: String, clockHz: Int, framePeriod: Double, frame: (Int, Int), areas: Seq[Rect],
    withAreas: Boolean, area: Int, mode: Int, filter: Int): Int = {
    val a = areas(area)
    val dst @ (dstWidth, dstHeight) = scaleModes(a.width, a.height)(mode)
    val offsetX = ((ScreenWidth - dstWidth) / 2).max(0)
    val offsetY = ((ScreenHeight - dstHeight) / 2).max(0)
    val tag = s"HandheldLcdScanSpec_${name}_area${area}_mode${mode}_filter$filter"
    val sim = PceTreadle(new Harness(frame, areas, withAreas, area, dst, filter, clockHz, framePeriod), tag)
    val rnd = new Random(name.hashCode + area * 16 + mode)
    val image = Array.fill(frame._2, frame._1)((rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256)))
    val smooth = filter == HandheldScreenFilter.Smooth &&
      HandheldSmoothScaler.smoothable(a.width, a.height, dstWidth, dstHeight)
    val grid = HandheldScreenFilter.filterable(a.width, a.height, dstWidth, dstHeight) &&
      (filter == HandheldScreenFilter.LcdGrid || filter == HandheldScreenFilter.Scanlines)
    def darken(c: Int) = c - (c >> 2) - (c >> 3)
    def want(px: Int, py: Int): (Int, Int, Int) = {
      if (smooth) {
        val (x0, x1, wx) = HandheldSmoothScaler.reference(a.width, dstWidth, px)
        val (y0, y1, wy) = HandheldSmoothScaler.reference(a.height, dstHeight, py)
        val taps = Seq((x0, y0, (8 - wx) * (8 - wy)), (x1, y0, wx * (8 - wy)), (x0, y1, (8 - wx) * wy), (x1, y1, wx * wy))
        def channel(c: ((Int, Int, Int)) => Int) =
          (taps.map { case (sx, sy, w) => c(image(a.y + sy)(a.x + sx)) * w }.sum + 32) / 64
        (channel(_._1), channel(_._2), channel(_._3))
      } else {
        val sx = nearest(a.width, dstWidth, px)
        val sy = nearest(a.height, dstHeight, py)
        val c = image(a.y + sy)(a.x + sx)
        def last(source: Int, destination: Int, n: Int) =
          n == destination - 1 || nearest(source, destination, n + 1) != nearest(source, destination, n)
        val edge = grid && ((filter == HandheldScreenFilter.LcdGrid && last(a.width, dstWidth, px)) ||
          last(a.height, dstHeight, py))
        if (edge) (darken(c._1), darken(c._2), darken(c._3)) else c
      }
    }
    sim.poke("reset", true)
    sim.step(1)
    sim.poke("reset", false)
    val readDelay = 3 + FilterLatency
    val reads = scala.collection.mutable.ArrayBuffer[(Int, Int)]()
    val seen = Array.ofDim[Boolean](ScreenWidth, ScreenHeight)
    var checked = 0
    var bad = 0
    var k = 0
    // The active lines of one frame (vsync 1 + back porch 2 + 480 lines).
    while (checked < ScreenWidth * ScreenHeight && k < 600 * 1024) {
      reads += ((sim.peek("io_readX").toInt, sim.peek("io_readY").toInt))
      val (cr, cg, cb) =
        if (k >= readDelay) {
          val (rx, ry) = reads(k - readDelay)
          if (rx < frame._1 && ry < frame._2) image(ry)(rx) else (0, 0, 0)
        } else (0, 0, 0)
      sim.poke("io_color_r", BigInt(cr))
      sim.poke("io_color_g", BigInt(cg))
      sim.poke("io_color_b", BigInt(cb))
      if (sim.peek("io_enable") != 0) {
        val sx = sim.peek("io_screenX").toInt
        val sy = sim.peek("io_screenY").toInt
        assert(sx < ScreenWidth && sy < ScreenHeight && !seen(sx)(sy), s"$tag: pixel ($sx, $sy) twice or off the panel")
        seen(sx)(sy) = true
        checked += 1
        val got = (sim.peek("io_out_r").toInt, sim.peek("io_out_g").toInt, sim.peek("io_out_b").toInt)
        val (px, py) = (sx - offsetX, sy - offsetY)
        val expected =
          if (px >= 0 && px < dstWidth && py >= 0 && py < dstHeight) want(px, py) else (0, 0, 0)
        if (got != expected) {
          if (bad < 6) println(s"$tag: screen ($sx, $sy) = picture ($px, $py): got $got, want $expected")
          bad += 1
        }
      }
      sim.step(1)
      k += 1
    }
    sim.finish()
    assert(checked == ScreenWidth * ScreenHeight, s"$tag: only $checked pixels shown")
    assert(bad == 0, s"$tag: $bad screen pixels differ")
    checked
  }

  /** Every scaling mode with Off and Smooth, and LCD Grid where a mode is at a whole-number scale >= 2. */
  def allModes(name: String, clockHz: Int, framePeriod: Double, frame: (Int, Int), areas: Seq[Rect],
    withAreas: Boolean): Unit = {
    for (area <- areas.indices; mode <- 0 until 4) {
      val a = areas(area)
      val (dw, dh) = scaleModes(a.width, a.height)(mode)
      val filters = Seq(HandheldScreenFilter.Off, HandheldScreenFilter.Smooth) ++
        (if (HandheldScreenFilter.filterable(a.width, a.height, dw, dh)) Seq(HandheldScreenFilter.LcdGrid) else Nil)
      for (filter <- filters) {
        run(name, clockHz, framePeriod, frame, areas, withAreas, area, mode, filter)
      }
    }
  }

  "NES (256 x 240): every pixel of every scan line, every mode, Off / Grid / Smooth" in {
    import net.gamebub.core.nes.{HandheldNes, NesVideoCapture}
    allModes("nes", displayClockHz(HandheldNes.MmcmVcoHz), NesVideoCapture.MasterClocksPerFrame.toDouble / HandheldNes.ClockSystemHz, (256, 240), Seq(Rect(0, 0, 256, 240)),
      withAreas = false)
  }
}
