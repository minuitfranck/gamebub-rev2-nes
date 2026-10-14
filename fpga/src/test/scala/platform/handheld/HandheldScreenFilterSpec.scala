package platform.handheld

import chisel3._
import lib.video.ColorRGB
import net.gamebub.core.pce.PceTreadle
import org.scalatest.freespec.AnyFreeSpec

/**
 * The LCD screen filter (framework register 0x101C, [[HandheldScreenFilter]]): for a picture of
 * srcWidth x srcHeight drawn at dstWidth x dstHeight with the LCD scaler's steps, which screen pixels are
 * darkened in each mode, and by how much. Off and 1x / fractional scales leave every pixel unchanged; the
 * LCD grid at N x N darkens exactly each block's last row and column, scanlines only its last row.
 *
 * The simulator is treadle2 through [[PceTreadle]] (no verilator here).
 */
class HandheldScreenFilterSpec extends AnyFreeSpec {
  class Harness(srcWidth: Int, srcHeight: Int, dstWidth: Int, dstHeight: Int) extends Module {
    val io = IO(new Bundle {
      val mode = Input(UInt(2.W))
      val relX = Input(UInt(10.W))
      val relY = Input(UInt(10.W))
      val color = Input(ColorRGB(8))
      val out = Output(ColorRGB(8))
    })
    val stepX = HandheldScreenFilter.step(srcWidth, dstWidth).U
    val stepY = HandheldScreenFilter.step(srcHeight, dstHeight).U
    val edge = HandheldScreenFilter.edge(
      io.mode,
      HandheldScreenFilter.filterable(srcWidth, srcHeight, dstWidth, dstHeight).B,
      HandheldScreenFilter.lastInBlock(io.relX * stepX, stepX),
      HandheldScreenFilter.lastInBlock(io.relY * stepY, stepY),
    )
    io.out := Mux(edge, HandheldScreenFilter.darken(io.color), io.color)
  }

  private val Color = (0xFF, 0x80, 0x13)
  private def dark(c: Int) = c - (c >> 2) - (c >> 3)

  /** Every darkened pixel over a full row sweep (at a few rows) and a full column sweep (at a few columns). */
  private def check(name: String, src: (Int, Int), dst: (Int, Int), mode: Int)(want: (Int, Int) => Boolean): Unit = {
    val sim = PceTreadle(new Harness(src._1, src._2, dst._1, dst._2), s"HandheldScreenFilterSpec_${name}_$mode")
    sim.poke("io_mode", BigInt(mode))
    sim.poke("io_color_r", BigInt(Color._1))
    sim.poke("io_color_g", BigInt(Color._2))
    sim.poke("io_color_b", BigInt(Color._3))
    val points =
      (for (y <- Seq(0, 1, 2, 5, dst._2 - 1); x <- 0 until dst._1) yield (x, y)) ++
        (for (x <- Seq(0, 1, 2, 5, dst._1 - 1); y <- 0 until dst._2) yield (x, y))
    var bad = 0
    var darkened = 0
    for ((x, y) <- points) {
      sim.poke("io_relX", BigInt(x))
      sim.poke("io_relY", BigInt(y))
      sim.step(1)
      val got = (sim.peek("io_out_r").toInt, sim.peek("io_out_g").toInt, sim.peek("io_out_b").toInt)
      val isDark = want(x, y)
      val expect = if (isDark) (dark(Color._1), dark(Color._2), dark(Color._3)) else Color
      if (isDark) darkened += 1
      if (got != expect) {
        if (bad < 3) println(s"$name mode $mode ($x, $y): got $got, want $expect")
        bad += 1
      }
    }
    println(s"$name mode $mode: ${points.size} pixels, $darkened darkened, $bad wrong")
    assert(bad == 0)
  }

  "darkening is c - c/4 - c/8 (about 62%)" in {
    assert(dark(0xFF) == 0xA1 && dark(0x80) == 0x50 && dark(0) == 0)
  }

  "filterable only at a whole-number scale of 2 or more" in {
    assert(HandheldScreenFilter.filterable(160, 144, 320, 288))  // GB window at 2x
    assert(HandheldScreenFilter.filterable(240, 160, 480, 320))  // SGB zoomed at 2x
    assert(!HandheldScreenFilter.filterable(256, 224, 256, 224)) // SGB whole at 1x
    assert(!HandheldScreenFilter.filterable(160, 144, 355, 320)) // GB window fit
    assert(!HandheldScreenFilter.filterable(160, 144, 480, 320)) // GB window stretch
  }

  for (mode <- 0 to 3) {
    s"GB window 2x, mode $mode" in {
      check("gb2x", (160, 144), (320, 288), mode) { (x, y) =>
        mode match {
          case HandheldScreenFilter.LcdGrid => x % 2 == 1 || y % 2 == 1
          case HandheldScreenFilter.Scanlines => y % 2 == 1
          case _ => false
        }
      }
    }
  }

  "3x blocks: LCD grid and scanlines on the third row / column" in {
    check("gb3x", (160, 144), (480, 432), HandheldScreenFilter.LcdGrid)((x, y) => x % 3 == 2 || y % 3 == 2)
    check("gb3x", (160, 144), (480, 432), HandheldScreenFilter.Scanlines)((_, y) => y % 3 == 2)
  }

  "1x and fractional scales are unchanged in every mode" in {
    for (mode <- 1 to 2) {
      check("whole1x", (256, 224), (256, 224), mode)((_, _) => false)
      check("fit", (160, 144), (355, 320), mode)((_, _) => false)
      check("stretch", (160, 144), (480, 320), mode)((_, _) => false)
    }
  }
}
