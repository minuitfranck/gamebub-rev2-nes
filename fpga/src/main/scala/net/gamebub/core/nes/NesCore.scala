package net.gamebub.core.nes

import chisel3._

/**
 * The vendored MiSTer NES core, module `NES` in
 * `fpga/verilog/nes/rtl/nes.sv` (NES_MiSTer 26e2efb, see
 * `fpga/verilog/nes/README.md`).
 *
 * Port names, directions and widths match the SystemVerilog module exactly;
 * `desiredName` is the module name. Everything runs on `clk`, the NTSC master
 * clock (21.477 MHz); the CPU, PPU and APU are clock enables derived from
 * counters inside, so the glue stalls the whole machine by gating `clk`.
 *
 * Memory: the core asks for bytes on two buses in MiSTer's SDRAM address map
 * (`cpumem_*`: PRG-ROM 0x000000-0x1FFFFF, work RAM 0x380000, cart RAM
 * 0x3C0000; `ppumem_*`: CHR 0x200000-0x2FFFFF, CIRAM 0x3A0000, high cart RAM
 * 0x3F8000) with level read / write strobes, and samples the answers at the
 * edges flagged by `gb_cpu_latch` / `gb_ppu_latch` (Game Bub additions).
 *
 * Save states (MiSTer's machinery): `Savestate_SDRAM*` is the byte port to
 * the RAMs above (same address map), `SAVE_out_*` the 64-bit slot storage
 * port (one-cycle `SAVE_out_ena`, one-cycle `SAVE_out_done`, DWORD
 * addresses). `SaveStateExt_*` served the MiSTer top level's own save-state
 * register (SDRAM controller latches) and is unused here (`SaveStateExt_Dout`
 * must be 0: it is ORed into the core's register bus).
 */
class NesCore extends ExtModule {
  override def desiredName: String = "NES"

  val io = FlatIO(new Bundle {
    val clk = Input(Clock())
    /** Warm reset (active high). */
    val reset_nes = Input(Bool())
    /** PPU reset behaviour: 0 Famicom (PPU reset with the CPU), 1 NES. */
    val ppu_rst_behavior = Input(Bool())
    /** Power-on reset (T65 `pwr_n`, APU cold reset), held while a ROM loads. */
    val cold_reset = Input(Bool())
    /** Pause at the next vblank instruction boundary (save states). */
    val pausecore = Input(Bool())
    val corepaused = Output(Bool())
    /** 0 NTSC, 1 PAL, 2 Dendy, 3 Vs. System. */
    val sys_type = Input(UInt(2.W))
    val vs_dip_switches = Input(UInt(8.W))
    /** Master clock counter (MiSTer's `nes_ce`, SDRAM / video phase). */
    val nes_div = Output(UInt(2.W))
    /** iNES / NES 2.0 header data in MiSTer's GameLoader encoding (see [[NesRomInfo]]). */
    val mapper_flags = Input(UInt(64.W))
    /** APU + mapper audio, offset binary. */
    val sample = Output(UInt(16.W))
    /** PPU palette index of the current dot. */
    val color = Output(UInt(6.W))
    val joypad_clock = Output(UInt(2.W))
    val joypad_out = Output(UInt(3.W))
    val joypad1_data = Input(UInt(5.W))
    val joypad2_data = Input(UInt(5.W))
    val fds_busy = Input(Bool())
    val fds_eject = Input(Bool())
    val fds_auto_eject = Input(Bool())
    val max_diskside = Input(UInt(2.W))
    val fds_fast = Input(Bool())
    val diskside = Output(UInt(2.W))

    val audio_channels = Input(UInt(5.W))
    val ex_sprites = Input(Bool())
    /** Edge mask: 0 off, 1 left, 2 both, 3 auto. */
    val mask = Input(UInt(2.W))
    val dejitter_timing = Input(Bool())

    val cpumem_addr = Output(UInt(25.W))
    val cpumem_read = Output(Bool())
    val cpumem_write = Output(Bool())
    val cpumem_dout = Output(UInt(8.W))
    val cpumem_din = Input(UInt(8.W))
    val ppumem_addr = Output(UInt(22.W))
    val ppumem_read = Output(Bool())
    val ppumem_write = Output(Bool())
    val ppumem_dout = Output(UInt(8.W))
    val ppumem_din = Input(UInt(8.W))
    val refresh = Output(Bool())

    val prg_mask = Input(UInt(21.W))
    val chr_mask = Input(UInt(20.W))

    val bram_addr = Output(UInt(18.W))
    val bram_din = Input(UInt(8.W))
    val bram_dout = Output(UInt(8.W))
    val bram_write = Output(Bool())
    val bram_override = Output(Bool())

    val cycle = Output(UInt(9.W))
    val scanline = Output(UInt(9.W))
    val int_audio = Input(Bool())
    val ext_audio = Input(Bool())
    val apu_ce = Output(Bool())
    val gg = Input(Bool())
    val gg_code = Input(UInt(129.W))
    val gg_avail = Output(Bool())
    val gg_reset = Input(Bool())
    val emphasis = Output(UInt(3.W))
    val save_written = Output(Bool())
    val mapper_has_flashsaves = Output(Bool())
    val debug_dots = Input(Bool())

    val mapper_has_savestate = Output(Bool())
    val increaseSSHeaderCount = Input(Bool())
    val save_state = Input(Bool())
    val load_state = Input(Bool())
    val savestate_number = Input(UInt(2.W))
    val sleep_savestate = Output(Bool())
    val state_loaded = Output(Bool())

    val hsync = Output(Bool())
    val hblank = Output(Bool())
    val vsync = Output(Bool())
    val vblank = Output(Bool())
    val composite_a = Output(SInt(24.W))
    val composite_b = Output(SInt(24.W))

    val Savestate_SDRAMAddr = Output(UInt(25.W))
    val Savestate_SDRAMRdEn = Output(Bool())
    val Savestate_SDRAMWrEn = Output(Bool())
    val Savestate_SDRAMWriteData = Output(UInt(8.W))
    val Savestate_SDRAMReadData = Input(UInt(8.W))

    val SaveStateExt_Din = Output(UInt(64.W))
    val SaveStateExt_Adr = Output(UInt(10.W))
    val SaveStateExt_wren = Output(Bool())
    val SaveStateExt_rst = Output(Bool())
    val SaveStateExt_Dout = Input(UInt(64.W))
    val SaveStateExt_load = Output(Bool())

    val SAVE_out_Din = Output(UInt(64.W))
    val SAVE_out_Dout = Input(UInt(64.W))
    val SAVE_out_Adr = Output(UInt(26.W))
    val SAVE_out_rnw = Output(Bool())
    val SAVE_out_ena = Output(Bool())
    val SAVE_out_be = Output(UInt(8.W))
    val SAVE_out_done = Input(Bool())

    /** `div_cpu` 10..12: the CPU bus samples memory data at this edge (Game Bub addition). */
    val gb_cpu_latch = Output(Bool())
    /** `ppu_ce`: the PPU samples VRAM data at this edge (Game Bub addition). */
    val gb_ppu_latch = Output(Bool())
  })
}

object NesCore {
  /**
   * Drive the inputs the Game Bub glue does not use to MiSTer's defaults:
   * no FDS, no Vs. DIP switches, no cheats, all APU channels, internal and
   * expansion audio on, video dejitter on (MiSTer "Video Dijitter: Enabled"),
   * no debug dots, no EEPROM mapper data, no MiSTer top-level save-state
   * register. The glue overrides what it exposes (later connections win).
   */
  def tieOffUnused(c: NesCore): Unit = {
    c.io.ppu_rst_behavior := false.B
    c.io.vs_dip_switches := 0.U
    c.io.fds_busy := false.B
    c.io.fds_eject := false.B
    c.io.fds_auto_eject := false.B
    c.io.max_diskside := 0.U
    c.io.fds_fast := false.B
    c.io.audio_channels := "b11111".U
    c.io.ex_sprites := false.B
    c.io.mask := 0.U
    c.io.dejitter_timing := false.B
    c.io.bram_din := 0.U
    c.io.int_audio := true.B
    c.io.ext_audio := true.B
    c.io.gg := true.B
    c.io.gg_code := 0.U
    c.io.gg_reset := false.B
    c.io.debug_dots := false.B
    c.io.increaseSSHeaderCount := true.B
    c.io.save_state := false.B
    c.io.load_state := false.B
    c.io.savestate_number := 0.U
    c.io.SaveStateExt_Dout := 0.U
    c.io.joypad2_data := 0.U
  }
}
