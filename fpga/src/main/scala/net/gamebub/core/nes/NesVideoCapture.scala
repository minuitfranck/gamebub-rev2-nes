package net.gamebub.core.nes

import chisel3._
import chisel3.util._

object NesVideoCapture {
  val Width = 256
  val Height = 240
  val ColorDepth = 5
  /** Master clocks per NTSC frame: 262 lines x 341 dots x 4 (one dot less on odd rendered frames). */
  val MasterClocksPerFrame = 262 * 341 * 4
  /** The PPU's colour output lags its dot counter by two dots (see the class comment). */
  val FirstDot = 2

  /**
   * The five built-in palettes of MiSTer's `video.sv` (64 x 24-bit RGB,
   * indexed by the PPU colour), in the order of MiSTer's "Palette" menu;
   * config values 0-4 select them (5-15 fall back to the first).
   */
  object Palettes {
    /** Kitrinx (video.sv `pal_kitrinx_lut`). */
    val Kitrinx: Seq[Int] = Seq(
      0x666666, 0x01247B, 0x1B1489, 0x39087C, 0x520257, 0x5C0725, 0x571300, 0x472300,
      0x2D3300, 0x0E4000, 0x004500, 0x004124, 0x003456, 0x000000, 0x000000, 0x000000,
      0xADADAD, 0x2759C9, 0x4845DB, 0x6F34CA, 0x922B9B, 0xA1305A, 0x9B4018, 0x885400,
      0x686700, 0x3E7A00, 0x1B8213, 0x0D7C57, 0x136C99, 0x000000, 0x000000, 0x000000,
      0xFFFFFF, 0x78ABFF, 0x9897FF, 0xC086FF, 0xE27DEF, 0xF281AF, 0xED916D, 0xDBA43B,
      0xBDB825, 0x92CB33, 0x6DD463, 0x5ECEA8, 0x65BEEA, 0x525252, 0x000000, 0x000000,
      0xFFFFFF, 0xCADBFF, 0xD8D2FF, 0xE7CCFF, 0xF4C9F9, 0xFACBDF, 0xF7D2C4, 0xEEDAAF,
      0xE1E3A5, 0xD0EBAB, 0xC2EEBF, 0xBDEBDB, 0xC0E4F7, 0xB8B8B8, 0x000000, 0x000000,
    )
    /** Smooth (video.sv `pal_smooth_lut`). */
    val Smooth: Seq[Int] = Seq(
      0x6A6D6A, 0x001380, 0x1E008A, 0x39007A, 0x550056, 0x5A0018, 0x4F1000, 0x3D1C00,
      0x253200, 0x003D00, 0x004000, 0x003924, 0x002E55, 0x000000, 0x000000, 0x000000,
      0xB9BCB9, 0x1850C7, 0x4B30E3, 0x7322D6, 0x951FA9, 0x9D285C, 0x983700, 0x7F4C00,
      0x5E6400, 0x227700, 0x027E02, 0x007645, 0x006E8A, 0x000000, 0x000000, 0x000000,
      0xFFFFFF, 0x68A6FF, 0x8C9CFF, 0xB586FF, 0xD975FD, 0xE377B9, 0xE58D68, 0xD49D29,
      0xB3AF0C, 0x7BC211, 0x55CA47, 0x46CB81, 0x47C1C5, 0x4A4D4A, 0x000000, 0x000000,
      0xFFFFFF, 0xCCEAFF, 0xDDDEFF, 0xECDAFF, 0xF8D7FE, 0xFCD6F5, 0xFDDBCF, 0xF9E7B5,
      0xF1F0AA, 0xDAFAA9, 0xC9FFBC, 0xC3FBD7, 0xC4F6F6, 0xBEC1BE, 0x000000, 0x000000,
    )
    /** Wavebeam (video.sv `pal_wavebeam_lut`). */
    val Wavebeam: Seq[Int] = Seq(
      0x6B6B6B, 0x001B88, 0x21009A, 0x40008C, 0x600067, 0x64001E, 0x590800, 0x481600,
      0x283600, 0x004500, 0x004908, 0x00421D, 0x003659, 0x000000, 0x000000, 0x000000,
      0xB4B4B4, 0x1555D3, 0x4337EF, 0x7425DF, 0x9C19B9, 0xAC0F64, 0xAA2C00, 0x8A4B00,
      0x666B00, 0x218300, 0x008A00, 0x008144, 0x007691, 0x000000, 0x000000, 0x000000,
      0xFFFFFF, 0x63B2FF, 0x7C9CFF, 0xC07DFE, 0xE977FF, 0xF572CD, 0xF4886B, 0xDDA029,
      0xBDBD0A, 0x89D20E, 0x5CDE3E, 0x4BD886, 0x4DCFD2, 0x525252, 0x000000, 0x000000,
      0xFFFFFF, 0xBCDFFF, 0xD2D2FF, 0xE1C8FF, 0xEFC7FF, 0xFFC3E1, 0xFFCAC6, 0xF2DAAD,
      0xEBE3A0, 0xD2EDA2, 0xBCF4B4, 0xB5F1CE, 0xB6ECF1, 0xBFBFBF, 0x000000, 0x000000,
    )
    /** SonyCxa (video.sv `pal_sonycxa_lut`). */
    val SonyCxa: Seq[Int] = Seq(
      0x585858, 0x00238C, 0x00139B, 0x2D0585, 0x5D0052, 0x7A0017, 0x7A0800, 0x5F1800,
      0x352A00, 0x093900, 0x003F00, 0x003C22, 0x00325D, 0x000000, 0x000000, 0x000000,
      0xA1A1A1, 0x0053EE, 0x153CFE, 0x6028E4, 0xA91D98, 0xD41E41, 0xD22C00, 0xAA4400,
      0x6C5E00, 0x2D7300, 0x007D06, 0x007852, 0x0069A9, 0x000000, 0x000000, 0x000000,
      0xFFFFFF, 0x1FA5FE, 0x5E89FE, 0xB572FE, 0xFE65F6, 0xFE6790, 0xFE773C, 0xFE9308,
      0xC4B200, 0x79CA10, 0x3AD54A, 0x11D1A4, 0x06BFFE, 0x424242, 0x000000, 0x000000,
      0xFFFFFF, 0xA0D9FE, 0xBDCCFE, 0xE1C2FE, 0xFEBCFB, 0xFEBDD0, 0xFEC5A9, 0xFED18E,
      0xE9DE86, 0xC7E992, 0xA8EEB0, 0x95ECD9, 0x91E4FE, 0xACACAC, 0x000000, 0x000000,
    )
    /** Pc10Better (video.sv `pal_pc10_lut`). */
    val Pc10Better: Seq[Int] = Seq(
      0x6D6D6D, 0x10247C, 0x0A06B3, 0x6950C2, 0x6A0F62, 0x831264, 0x872F0F, 0x774C11,
      0x5E490F, 0x2C430A, 0x1E612A, 0x258011, 0x164244, 0x000000, 0x000000, 0x000000,
      0xB6B6B6, 0x2767C0, 0x1F48DA, 0x7114DA, 0x8A17DC, 0xB71987, 0xB0150F, 0xB37219,
      0x806C15, 0x3E8313, 0x258011, 0x34A46F, 0x2C8589, 0x000000, 0x000000, 0x000000,
      0xFFFFFF, 0x87B2ED, 0x9795EB, 0xC07BEB, 0xBD1DE1, 0xD97EED, 0xD59620, 0xDFB624,
      0xCFD326, 0x84CA20, 0x41E11D, 0x7FEED6, 0x4EE9EF, 0x000000, 0x000000, 0x000000,
      0xFFFFFF, 0xC3D8F6, 0xD2BBF4, 0xECBEF6, 0xE29EF2, 0xE8BCBA, 0xF0DBA0, 0xF5F969,
      0xF7FA87, 0xC3F364, 0xACF180, 0x7FEED6, 0xAAD5F4, 0x000000, 0x000000, 0x000000,
    )
    val All: Seq[Seq[Int]] = Seq(Kitrinx, Smooth, Wavebeam, SonyCxa, Pc10Better)
  }

  /**
   * MiSTer's emphasis attenuation (`video.sv`, 8-bit channels, `x[7:2]` =
   * x >> 2): for emphasis bits BGR (bit 0 red, 1 green, 2 blue in the PPU's
   * $2001 order as the core outputs them) the other channels are dimmed.
   */
  def emphasize(r: UInt, g: UInt, b: UInt, emph: UInt): (UInt, UInt, UInt) = {
    def q(x: UInt) = (x >> 2).asUInt
    def e(x: UInt) = (x >> 3).asUInt
    val out = Wire(Vec(3, UInt(8.W)))
    out(0) := r; out(1) := g; out(2) := b
    switch (emph) {
      is (1.U) { out(1) := g - q(g); out(2) := b - q(b) }
      is (2.U) { out(0) := r - q(r); out(2) := b - q(b) }
      is (3.U) { out(0) := r - q(r); out(1) := g - e(g); out(2) := b - q(b) - e(b) }
      is (4.U) { out(0) := r - e(r); out(1) := g - e(g) }
      is (5.U) { out(0) := r - e(r); out(1) := g - q(g); out(2) := b - e(b) }
      is (6.U) { out(0) := r - q(r); out(1) := g - e(g); out(2) := b - e(b) }
      is (7.U) { out(0) := r - q(r); out(1) := g - q(g); out(2) := b - q(b) }
    }
    (out(0), out(1), out(2))
  }

  class Rgb555 extends Bundle {
    val r = UInt(5.W)
    val g = UInt(5.W)
    val b = UInt(5.W)
  }
}

/**
 * Samples the PPU's output into the framework's 256 x 240 framebuffer, in the
 * core's clock domain, reproducing MiSTer's `video.sv` in its "full" overscan
 * mode (240 lines x 256 dots, `hide_overscan` = 01).
 *
 * `video.sv` takes a dot at `pix_ce` (`nes_div` == 0, registered, i.e. one
 * master clock after the PPU's dot counter has moved), registers the palette
 * colour and shows it one `pix_ce` later, while its blanking follows the dot
 * counter through three registers: a dot sampled at `cycle` = c is visible
 * when c - 2 is in 1..256, so framebuffer column x is the colour sampled at
 * `cycle` x + 2 (`FirstDot`). Rows are `scanline` 0..239 (the pre-render line
 * is 511).
 *
 * Outputs change only at `pix_ce` edges: `strobe` is high for one core cycle
 * with a visible dot in `pixel`; `hblank` is high outside the 256 visible
 * dots (its rising edge advances the framework's row) and `vblank` outside
 * the 240 visible lines (the framework restarts the frame and swaps buffers
 * at its rising edge). The glue qualifies `strobe` with its tick. While
 * `paused` (the core's save-state pause, whose video counters are a
 * free-running replica) nothing changes: the LCD keeps the last frame.
 *
 * `hideOverscan` draws rows 0-7 and 232-239 black (what a TV hides).
 */
class NesVideoCapture extends Module {
  import NesVideoCapture._
  val io = IO(new Bundle {
    val nesDiv = Input(UInt(2.W))
    val color = Input(UInt(6.W))
    val emphasis = Input(UInt(3.W))
    val cycle = Input(UInt(9.W))
    val scanline = Input(UInt(9.W))
    val paused = Input(Bool())
    val palette = Input(UInt(4.W))
    val hideOverscan = Input(Bool())

    val pixel = Output(new Rgb555)
    val strobe = Output(Bool())
    val hblank = Output(Bool())
    val vblank = Output(Bool())
  })

  val tables = Palettes.All.map(p => VecInit(p.map(_.U(24.W))))
  val paletteColor = Wire(UInt(24.W))
  paletteColor := tables(0)(io.color)
  for ((t, i) <- tables.zipWithIndex.drop(1)) {
    when (io.palette === i.U) { paletteColor := t(io.color) }
  }
  // Emphasis only applies to colours whose low nibble is not $E / $F (video.sv: ~&color_ef[3:1]).
  val emph = Mux(io.color(3, 1).andR, 0.U, io.emphasis)
  val (er, eg, eb) = emphasize(paletteColor(23, 16), paletteColor(15, 8), paletteColor(7, 0), emph)

  val pixCe = RegNext(io.nesDiv === 0.U, false.B)
  val strobe = RegInit(false.B)
  val hblank = RegInit(true.B)
  val vblank = RegInit(true.B)
  val pixel = RegInit(0.U.asTypeOf(new Rgb555))
  strobe := false.B
  when (pixCe && !io.paused) {
    val inLine = io.cycle >= FirstDot.U && io.cycle < (FirstDot + Width).U
    val inFrame = io.scanline < Height.U
    hblank := !inLine
    vblank := !inFrame
    when (inLine && inFrame) {
      strobe := true.B
      val black = io.hideOverscan && (io.scanline < 8.U || io.scanline >= (Height - 8).U)
      pixel.r := Mux(black, 0.U, er(7, 3))
      pixel.g := Mux(black, 0.U, eg(7, 3))
      pixel.b := Mux(black, 0.U, eb(7, 3))
    }
  }
  io.pixel := pixel
  io.strobe := strobe
  io.hblank := hblank
  io.vblank := vblank
}
