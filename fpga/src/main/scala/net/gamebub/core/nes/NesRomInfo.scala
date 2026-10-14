package net.gamebub.core.nes

import chisel3._
import chisel3.util._

object NesRomInfo {
  /** Mapper numbers with an instance in the vendored cart.sv (`{flags[18:17], flags[7:0]}` = `me` index). */
  val SupportedMappers: Seq[Int] = Seq(0, 1, 2, 3, 4, 7, 9, 10, 11, 13, 28, 30, 33, 34, 37, 38, 45, 46, 47, 48, 52, 64,
    66, 70, 71, 74, 76, 77, 78, 79, 80, 81, 82, 86, 87, 88, 89, 93, 94, 95, 97, 101, 105, 107, 112, 113, 118, 119, 133,
    140, 144, 145, 146, 148, 149, 152, 154, 155, 158, 165, 171, 180, 184, 185, 189, 191, 192, 194, 195, 196, 205, 206,
    207, 208, 232, 268)

  /** CHR-ROM that fits the SRAM's CHR area (the top 32 KiB are the high cart RAM window). */
  val MaxChrBytes = 0x78000
  /** PRG that fits below the save-state slots (0x1000000) with the 16-byte header. */
  val MaxPrgBytes = 0x1000000 - 16

  class Result extends Bundle {
    /** MiSTer's 64-bit `mapper_flags` (GameLoader encoding). */
    val flags = UInt(64.W)
    val prgMask = UInt(10.W)
    val chrMask = UInt(10.W)
    /** The real PRG / CHR-ROM sizes in the file, bytes (NES 2.0 sizes in full, unlike MiSTer's 22-bit copy). */
    val prgBytes = UInt(25.W)
    val chrBytes = UInt(25.W)
    /** File offset of the CHR data (16 + PRG; trainers are refused). */
    val chrOffset = UInt(25.W)
    val chrRam = Bool()
    val battery = Bool()
    /** NES 2.0 header. */
    val nes20 = Bool()
    /** NES 2.0 battery RAM (NVRAM) size code (64 << n bytes; 0 = none). */
    val nvramShift = UInt(4.W)
    /** 10-bit mapper number. */
    val mapper = UInt(10.W)
    /** The header's timing: 0 NTSC, 1 PAL, 2 Dendy (Vs. and multi-region count as NTSC). */
    val autoSysType = UInt(2.W)
    /** "NES\x1A" magic present. */
    val magicOk = Bool()
    val trainer = Bool()
    val mapperSupported = Bool()
    /** Everything the glue checks: magic, no trainer, mapper built, sizes fit, CHR word-aligned. */
    val supported = Bool()
  }

  /** Header analysis as combinational logic over the 16 header bytes (MiSTer GameLoader, NES.sv.reference 1404-1540). */
  def analyze(ines: Vec[UInt], fileSize: UInt): Result = {
    val r = Wire(new Result)
    val prgrom = ines(4)
    val chrrom = ines(5)
    val chrram = ines(11)(3, 0)
    val isNes20 = ines(7)(3, 2) === 2.U
    val isNes20Prg = isNes20 && ines(9)(3, 0) === 0xF.U
    val isNes20Chr = isNes20 && ines(9)(7, 4) === 0xF.U
    val hasChrRam = Mux(!isNes20, chrrom === 0.U, chrram =/= 0.U)

    // MiSTer's 22-bit sizes (used for the masks and flags, exactly as upstream).
    val prgSize2 = Mux(isNes20Prg, (Cat(ines(4)(1, 0), 1.U(1.W)) << ines(4)(7, 2))(21, 0), Cat(prgrom, 0.U(14.W)))
    val chrSize2 = Mux(isNes20Chr, (Cat(ines(5)(1, 0), 1.U(1.W)) << ines(5)(7, 2))(21, 0), Cat(0.U(1.W), chrrom, 0.U(13.W)))
    val chrRamSize = Mux(isNes20, (64.U(22.W) << chrram)(21, 0), 0x2000.U(22.W))
    def mask(size: UInt): UInt = VecInit((0 until 10).map(i => size > (1 << i).U(11.W))).asUInt
    r.prgMask := mask(prgSize2(21, 11))
    r.chrMask := mask(Mux(chrSize2.orR, chrSize2(21, 11), chrRamSize(21, 11)))

    def sizeCode(pages: UInt): UInt = MuxCase(7.U(3.W), Seq(
      (pages <= 1.U) -> 0.U, (pages <= 2.U) -> 1.U, (pages <= 4.U) -> 2.U, (pages <= 8.U) -> 3.U,
      (pages <= 16.U) -> 4.U, (pages <= 32.U) -> 5.U, (pages <= 64.U) -> 6.U))
    val prgSizeCode = sizeCode(prgrom)
    val chrSizeCode = sizeCode(chrrom)

    val isDirty = !isNes20 && (ines(9)(7, 1) =/= 0.U || ines(10) =/= 0.U || ines(11) =/= 0.U || ines(12) =/= 0.U ||
      ines(13) =/= 0.U || ines(14) =/= 0.U || ines(15) =/= 0.U)
    val mapper8 = Cat(Mux(isDirty, 0.U(4.W), ines(7)(7, 4)), ines(6)(7, 4))
    val ines2mapper = Mux(isNes20, ines(8), 0.U)
    val prgram = Mux(isNes20, ines(10)(3, 0), 0.U)
    val prgNvram = Mux(isNes20, ines(10)(7, 4), 0.U)
    val piano = isNes20 && ines(15)(5, 0) === 0x19.U
    val mapper99 = Cat(ines2mapper(1, 0), mapper8) === 99.U
    val vsHardwareType = Mux(isNes20 && ines(7)(1, 0) === 1.U, ines(13)(7, 4), 0.U)
    val mapper99Dual = mapper99 && vsHardwareType === 5.U
    val mapper99Raid = mapper99 && vsHardwareType === 6.U
    val hasSaves = ines(6)(1)

    r.flags := Cat(
      0.U(4.W),                                                        // 63:60
      Mux(isNes20, ines(15)(6, 0), 0.U(7.W)),                          // 59:53 default expansion device
      vsHardwareType,                                                  // 52:49
      Mux(isNes20 && ines(7)(1, 0) === 1.U, ines(13)(3, 0), 0.U(4.W)), // 48:45 Vs. PPU type
      Mux(isNes20, ines(7)(1, 0), 0.U(2.W)),                           // 44:43 console type
      Mux(mapper99, chrSize2(14, 13), 0.U(2.W)),                       // 42:41
      Mux(mapper99Dual, prgSize2(16, 14), Mux(mapper99Raid, 4.U(3.W), Mux(mapper99, prgSize2(15, 13), 0.U(3.W)))), // 40:38
      Mux(isNes20, ines(12)(1, 0), 0.U(2.W)),                          // 37:36 timing
      isNes20,                                                         // 35
      prgNvram,                                                        // 34:31
      piano,                                                           // 30
      prgram,                                                          // 29:26
      hasSaves,                                                        // 25
      ines2mapper,                                                     // 24:17 (submapper 24:21, mapper bits 20:17)
      ines(6)(3),                                                      // 16 four-screen
      hasChrRam,                                                       // 15
      ines(6)(0),                                                      // 14 mirroring
      chrSizeCode,                                                     // 13:11
      prgSizeCode,                                                     // 10:8
      mapper8,                                                         // 7:0
    )

    // The real sizes in the file (NES 2.0: 12-bit page counts, or exponent-multiplier).
    val prgPages = Cat(Mux(isNes20, ines(9)(3, 0), 0.U(4.W)), prgrom)
    val chrPages = Cat(Mux(isNes20, ines(9)(7, 4), 0.U(4.W)), chrrom)
    val expPrg = Cat(ines(4)(1, 0), 1.U(1.W)) << ines(4)(7, 2)
    val expChr = Cat(ines(5)(1, 0), 1.U(1.W)) << ines(5)(7, 2)
    // An exponent above 24 cannot be a real file; saturate so that the size checks fail.
    val prgBytes = Mux(isNes20Prg, Mux(ines(4)(7, 2) > 24.U, 0x1FFFFFF.U, expPrg(24, 0)), Cat(prgPages, 0.U(14.W))(24, 0))
    val chrBytes = Mux(isNes20Chr, Mux(ines(5)(7, 2) > 24.U, 0x1FFFFFF.U, expChr(24, 0)), Cat(chrPages, 0.U(13.W))(24, 0))
    r.prgBytes := prgBytes
    r.chrBytes := chrBytes
    r.chrOffset := prgBytes + 16.U
    r.chrRam := hasChrRam
    r.battery := hasSaves
    r.nes20 := isNes20
    r.nvramShift := prgNvram
    r.mapper := Cat(ines2mapper(1, 0), mapper8)
    // MiSTer's auto_sys_type, with Vs. System (console type 1) run as NTSC.
    r.autoSysType := MuxCase(0.U(2.W), Seq(
      (isNes20 && ines(7)(1, 0) === 1.U) -> 0.U,
      (isNes20 && ines(12)(1, 0) === 1.U) -> 1.U,
      (isNes20 && ines(12)(1, 0) === 3.U) -> 2.U))
    r.magicOk := ines(0) === 0x4E.U && ines(1) === 0x45.U && ines(2) === 0x53.U && ines(3) === 0x1A.U
    r.trainer := ines(6)(2)
    r.mapperSupported := SupportedMappers.map(m => r.mapper === m.U).reduce(_ || _)
    val chrFits = hasChrRam || chrBytes <= MaxChrBytes.U
    val prgFits = prgBytes =/= 0.U && prgBytes <= MaxPrgBytes.U && prgBytes(1, 0) === 0.U
    val fileLongEnough = fileSize >= prgBytes +& 16.U
    r.supported := r.magicOk && !r.trainer && r.mapperSupported && chrFits && prgFits && fileLongEnough
    r
  }
}

/**
 * The iNES / NES 2.0 header of the ROM file, taken from the host's writes to
 * the SDRAM window while the file arrives (file offsets 0..15, i.e. the
 * first four 32-bit words, little-endian), and its analysis.
 *
 * `live` is the analysis of the bytes captured so far, registered: the host
 * window's CHR splitter uses `live.chrOffset` while the rest of the file
 * arrives (the header is complete long before the first CHR word). `result`
 * is latched at `finish` (FILE_WRITE_END of the ROM, with the file size) and
 * `valid` is set then; `clear` (FILE_WRITE_START of the ROM) forgets both.
 */
class NesRomInfo extends Module {
  import NesRomInfo._
  val io = IO(new Bundle {
    val clear = Input(Bool())
    val stream = Input(new Bundle {
      val valid = Bool()
      val address = UInt(25.W)
      val data = UInt(32.W)
    })
    /** All four header words have been written since `clear`. */
    val headerComplete = Output(Bool())
    val live = Output(new Result)
    val finish = Input(Bool())
    val fileSize = Input(UInt(25.W))
    val valid = Output(Bool())
    val result = Output(new Result)
  })

  val header = RegInit(VecInit(Seq.fill(16)(0.U(8.W))))
  val seen = RegInit(0.U(4.W))
  when (io.stream.valid && io.stream.address < 16.U) {
    val word = io.stream.address(3, 2)
    for (i <- 0 until 4) {
      header(Cat(word, i.U(2.W))) := io.stream.data(8 * i + 7, 8 * i)
    }
    seen := seen | UIntToOH(word, 4)
  }
  val fileSize = RegInit(0.U(25.W))
  val live = RegNext(analyze(header, fileSize))
  io.live := live
  io.headerComplete := seen.andR

  val valid = RegInit(false.B)
  val result = Reg(new Result)
  // `finish` comes with the size in the same cycle; latch the size first and
  // the analysis (which depends on it) one cycle later.
  val finishing = RegNext(io.finish, false.B)
  when (io.finish) {
    fileSize := io.fileSize
  }
  when (finishing) {
    result := analyze(header, fileSize)
    valid := true.B
  }
  when (io.clear) {
    header.foreach(_ := 0.U)
    seen := 0.U
    valid := false.B
    fileSize := 0.U
  }
  io.valid := valid
  io.result := result
}
