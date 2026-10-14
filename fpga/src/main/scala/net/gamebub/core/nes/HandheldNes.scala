package net.gamebub.core.nes

import chisel3._
import chisel3.util._
import lib.audio.AudioRateAdapter
import lib.mem.cache.{LineBuffer, LineReadCache}
import lib.mem.{MemoryArbiter, MemoryInterface, MemoryMap, PipelineInterfaceBridge, PipelineMemoryArbiter, PipelineMemoryBurstCdc, PipelineMemoryInterface, PipelineMemoryLowPriorityMux, RegisterMap}
import lib.mem.sdram.BurstSdramController
import lib.mem.sram.AsyncSramController
import lib.util.ButtonFilter
import lib.mem.sram.{BytePortBridge, SramHostAdapter}
import net.gamebub.framework.Core
import net.gamebub.framework.interface._
import xilinx.{BUFG, BUFGCE, MMCM, PLL}

object HandheldNes {
  //////////////////////////////////
  // Clock tree: the SNES port's (HandheldSnes), which produces the NES /
  // SNES master clock exactly. MMCM 50 / 1 x 23.625 = 1181.25 MHz -> /11 ->
  // PLL x12 = 1288.636 MHz -> /60 = 21.4773 MHz system (core) clock, /30 =
  // 42.955 MHz SDRAM controller (2x, through PipelineMemoryBurstCdc), /30 at
  // 216 degrees for the SDRAM chip's clock pin (core_nes.xdc = core_snes.xdc:
  // same PLL output indices and instance names). Display and host SPI clocks
  // from the MMCM's VCO.
  //////////////////////////////////
  val ClockInHz = 50_000_000
  val MmcmDivide = 1
  val MmcmMultiply = 23.625
  val MmcmVcoHz = ClockInHz.toDouble / MmcmDivide * MmcmMultiply
  val PllInputDivider = 11
  val PllMultiply = 12
  val PllVcoHz = MmcmVcoHz / PllInputDivider * PllMultiply
  val SysDivider = 60
  val SdramDivider = 30
  val SdramClockPhaseNs = 14.0
  val ClockSystemHz = (PllVcoHz / SysDivider).toInt
  val ClockSdramHz = (PllVcoHz / SdramDivider).toInt
  val SdramClockPhaseDegrees = {
    val step = 360.0 / (8 * SdramDivider)
    (360.0 * SdramClockPhaseNs * ClockSdramHz / 1e9 / step).round * step
  }
  val SpiDivider = (MmcmVcoHz / 160_000_000).floor.toInt
  val ClockSpiHz = (MmcmVcoHz / SpiDivider).toInt
  require(ClockSpiHz > 160_000_000)

  /** The NTSC master clock the core assumes (236.25 / 11 MHz). */
  val MasterClockNtscHz = 21477272
  /** A PAL NES's master clock (26.6017125 MHz / 1.25). The physical clock stays at the NTSC rate; see `palPace`. */
  val MasterClockPalHz = 21281370

  /** SRAM layout (bytes; the SRAM is 512 KiB of 16-bit words). */
  object SramMap {
    /** CHR-ROM / CHR-RAM: MiSTer 0x200000-0x277FFF. */
    val ChrBase = 0x00000
    val ChrBytes = NesRomInfo.MaxChrBytes
    /** High cart RAM window: MiSTer 0x3F8000-0x3FFFFF. */
    val HighRamBase = 0x78000
  }

  /** A warm reset (register 0x2000) holds reset_nes for 2^WarmResetBits - 1 delivered core clocks. */
  val WarmResetBits = 8
  /** SS_BUSY (status bit 7) stays high at least this long, so the firmware's 25 ms poll sees a short load. */
  val SsBusyStretchCycles = ClockSystemHz / 10

  object CommandState extends ChiselEnum {
    val idle, busy, error, done = Value
  }

  /**
   * Register 0x0000. N.B. the last field is bit 0. Reset value 0x2C00: the ROM path's early answers and eager issue
   * on, Sound Filter Soft (settings.json's default; the firmware writes the settings before the game starts).
   */
  class ConfigBits extends Bundle {
    /** Bits 31:15: reserved (read back as written). */
    val reserved = UInt(17.W)
    /** Bits 14:13: Sound Filter (0 Off, 1 Soft, 2 Softer, 3 Speaker; [[NesAudio]]). */
    val soundFilter = UInt(2.W)
    /** Bit 12: PPU reset behaviour (0 Famicom: the PPU resets with the CPU; 1 NES). */
    val ppuResetNes = Bool()
    /** Bit 11: the ROM cache issues a miss's first word in its lookup cycle. */
    val romEagerIssue = Bool()
    /** Bit 10: the ROM line buffer answers a miss with the word as it arrives. */
    val romEarlyAnswers = Bool()
    /** Bit 9: NES B / A on the Game Bub's Y / B instead of B / A. */
    val buttonsBY = Bool()
    /** Bit 8: no 8-sprites-per-line limit. */
    val extraSprites = Bool()
    /** Bit 7: mask the left 8 dots (the PPU's own edge mask). */
    val maskLeft = Bool()
    /** Bit 6: draw the top and bottom 8 lines black. */
    val hideOverscan = Bool()
    /** Bits 5:2: palette (0 Kitrinx, 1 Smooth, 2 Wavebeam, 3 Sony CXA, 4 PC-10). */
    val palette = UInt(4.W)
    /** Bits 1:0: system type (0 auto from the header, 1 NTSC, 2 PAL, 3 Dendy). */
    val sysType = UInt(2.W)
  }
  val ConfigResetValue = 0x2C00

  /**
   * A decimating low-pass for the APU output: a CIC (`stages` cascaded box
   * averages of `ratio` enabled clocks), integrating on `enable` (a delivered
   * core clock) and emitting `outValid` every `ratio` of them. Unity DC gain
   * (the integrators grow by ratio^stages, shifted back), wrap-around
   * arithmetic as CIC filters require.
   */
  class AudioDecimator(inWidth: Int = 16, ratio: Int = 512, stages: Int = 3) extends Module {
    require(isPow2(ratio) && ratio >= 4)
    val io = IO(new Bundle {
      val enable = Input(Bool())
      val in = Input(SInt(inWidth.W))
      val out = Output(SInt(inWidth.W))
      val outValid = Output(Bool())
    })
    val growth = stages * log2Ceil(ratio)
    val width = inWidth + growth
    val integrators = Seq.fill(stages)(RegInit(0.S(width.W)))
    when (io.enable) {
      integrators.foldLeft(io.in) { (x, acc) =>
        acc := acc + x
        acc
      }
    }
    val counter = RegInit(0.U(log2Ceil(ratio).W))
    val strobe = io.enable && counter.andR
    when (io.enable) {
      counter := counter + 1.U
    }
    val delays = Seq.fill(stages)(RegInit(0.S(width.W)))
    val combOut = delays.foldLeft(integrators.last: SInt) { (x, d) =>
      when (strobe) { d := x }
      x - d
    }
    val out = RegInit(0.S(inWidth.W))
    when (strobe) {
      out := (combOut >> growth).asSInt
    }
    io.out := out
    io.outValid := RegNext(strobe, false.B)
  }
}

/**
 * NES / Famicom core, wrapping the vendored MiSTer NES core
 * (`fpga/verilog/nes`, [[NesCore]]); docs/nes-port-design.md.
 *
 * Clocked at 21.477 MHz (the NES master clock). The core runs on a gated
 * copy of that clock: at an edge where the core samples memory data that has
 * not arrived yet (a PRG read the SDRAM cache has not answered, a CHR read
 * the SRAM has not answered), the edge is withheld, so the core observes
 * MiSTer's fixed memory latency.
 *
 * Memory map (the core's MiSTer SDRAM addresses, decoded here):
 *  - SDRAM: the `.nes` file at byte 0 as the host loads it (PRG at byte 16,
 *           read through a 16-line register buffer and a 32 KiB 2-way line
 *           cache); save-state slots 4 x 256 KiB at 0x1000000
 *  - SRAM:  CHR-ROM / CHR-RAM (MiSTer 0x200000, 480 KiB; the host's ROM
 *           transfer writes the file's CHR words here instead of the SDRAM)
 *           and the high cart RAM window (MiSTer 0x3F8000, 32 KiB)
 *  - BRAM:  work RAM 2 KiB (0x380000), CIRAM 2 KiB (0x3A0000), cart RAM
 *           32 KiB (0x3C0000, the `.sav`)
 *
 * Host windows: registers at 0x0xxx_xxxx, the SDRAM at 0x3xxx_xxxx (ROM file
 * id 0, states file id 2 at 0x31000000), the cart RAM at 0x4xxx_xxxx (save
 * file id 1, 32-bit words), commands at 0xF0xx_xxxx. Registers:
 *  - 0x0000 config (rw, reset 0x2C00): see [[HandheldNes.ConfigBits]]
 *  - 0x0004 / 0x0008 (r) mapper flags, low / high word (MiSTer encoding)
 *  - 0x000C (r) ROM info: bits 9:0 mapper, 11:10 system type in use, bit 12
 *           NES 2.0, 13 battery, 14 CHR-RAM, 15 mapper built, 16 trainer, 17
 *           magic ok, 18 supported, 19 analysis valid
 *  - 0x0010 (r) ROM file size; 0x001C (r) PRG KiB (31:16), CHR-ROM KiB (15:0)
 *  - 0x0014 save states: write bit 0 save / bit 1 load (slot in bits 3:2, or
 *           register 0x0018 with bit 4 set, so settings write 0x11 / 0x12);
 *           reads back the slot in bits 3:2
 *  - 0x0018 the save-state slot (bits 1:0)
 *  - 0x0100 (r) status: bit 0 setup complete, 1 halted, 2 focus, 3 ROM
 *           loading (cold reset), 4 core paused (save-state pause), 6 SS_AVAIL,
 *           7 SS_BUSY (stretched to >= 100 ms), 8 a save completed since the
 *           last save request, 9 a request not yet taken by the core, 10 a
 *           request in progress (the core runs without focus), 14:11 slots
 *           holding a state
 *  - 0x1000-0x1024 statistics (write clears): run cycles, stall cycles, PRG
 *           stalls, CHR stalls, ROM requests, buffer hits, cache misses, CHR
 *           reads, frames, save-state SRAM stalls
 *  - 0x2000 (w) bit 0: warm reset
 */
class HandheldNes extends Module with Core {
  import HandheldNes._

  val FramePeriod = NesVideoCapture.MasterClocksPerFrame.toDouble / ClockSystemHz
  /** A PAL / Dendy frame: 312 lines x 341 dots at the 5.320342 MHz PAL dot clock (50.007 Hz); the LCD driver
    * keeps a longer scan line for it (2026-10-09). */
  val FramePeriodPal = 312.0 * 341 / 5320342.0
  val displayDivider = (MmcmVcoHz / ClocksV0.getClockDisplayHz(FramePeriod)._1).floor.toInt

  val io = IO(new Bundle {
    val clocks = new ClocksV0(
      clockSystemHz = ClockSystemHz,
      clockDisplayHz = (MmcmVcoHz / displayDivider).toInt,
      clockSpiHz = ClockSpiHz,
    )
    val video = new VideoV0(
      videoWidth = NesVideoCapture.Width,
      videoHeight = NesVideoCapture.Height,
      colorDepthR = NesVideoCapture.ColorDepth,
      colorDepthG = NesVideoCapture.ColorDepth,
      colorDepthB = NesVideoCapture.ColorDepth,
      framePeriod = FramePeriod,
      framePeriodSlow = Some(FramePeriodPal),
    )
    val audio = new AudioV0()
    val host = new HostV0()
    val input = new InputV0()
    val sram = new SramV0()
    val sdram = new SdramV0()
  })

  //////////////////////////////////
  // Clocks
  //////////////////////////////////
  val mmcm = Module(new MMCM(
    clockInHz = ClockInHz,
    divide = MmcmDivide,
    multiply = MmcmMultiply,
    clockOutConfig = Seq(
      MMCM.ClockOut(PllInputDivider), // PLL input
      MMCM.ClockOut(displayDivider),  // Display
      MMCM.ClockOut(SpiDivider),      // Host SPI
    )
  ))
  mmcm.io.clockIn := io.clocks.clockIn50M
  mmcm.io.powerDown := false.B
  // core_nes.xdc names this instance's SDRAM pin clock output (core/pll/pll/CLKOUT2).
  val pll = Module(new PLL(
    clockInHz = (MmcmVcoHz / PllInputDivider).round.toInt,
    divide = 1,
    multiply = PllMultiply,
    clockOutConfig = Seq(
      PLL.ClockOut(SysDivider),   // System
      PLL.ClockOut(SdramDivider), // SDRAM controller (2x)
      PLL.ClockOut(SdramDivider, phase = SdramClockPhaseDegrees), // SDRAM chip (forwarded)
    )
  ))
  pll.io.clockIn := BUFG(mmcm.io.clockOuts(0))
  pll.io.powerDown := false.B
  pll.io.reset := !mmcm.io.locked
  io.clocks.clockOutSystem := pll.io.clockOuts(0)
  val clockSdram = pll.io.clockOuts(1)
  val clockSdramPin = BUFG(pll.io.clockOuts(2))
  io.clocks.clockOutDisplay := mmcm.io.clockOuts(1)
  io.clocks.clockOutSpi := mmcm.io.clockOuts(2)
  io.clocks.locked := mmcm.io.locked && pll.io.locked

  //////////////////////////////////
  // Framework state
  //////////////////////////////////
  val regCoreSetup = RegInit(false.B)
  val regCoreReset = RegInit(true.B)
  val regCoreFocus = RegInit(false.B)
  /** From a ROM transfer's start until SetupComplete: the core is held in its power-on reset. */
  val coldReset = RegInit(true.B)
  /** A ROM transfer is in progress (FileWriteStart 0 .. FileWriteEnd 0): the CHR splitter is active. */
  val romLoading = RegInit(false.B)
  val romFileSize = RegInit(0.U(25.W))
  val warmResetCounter = RegInit(0.U(WarmResetBits.W))
  val warmResetWrite = WireDefault(false.B)

  //////////////////////////////////
  // Host registers
  //////////////////////////////////
  val configReg = RegInit(ConfigResetValue.U(32.W).asTypeOf(new ConfigBits))
  val ssControlWrite = WireDefault(false.B)
  val ssControlWriteData = WireDefault(0.U(32.W))
  val ssSlot = RegInit(0.U(2.W))
  val ssSlotReg = RegInit(0.U(2.W))
  val statRunCycles = RegInit(0.U(32.W))
  val statStallCycles = RegInit(0.U(32.W))
  val statStallsPrg = RegInit(0.U(32.W))
  val statStallsChr = RegInit(0.U(32.W))
  val statRomRequests = RegInit(0.U(32.W))
  val statRomBufferHits = RegInit(0.U(32.W))
  val statRomCacheMisses = RegInit(0.U(32.W))
  val statChrReads = RegInit(0.U(32.W))
  val statFrames = RegInit(0.U(32.W))
  val statStallsSs = RegInit(0.U(32.W))
  val statusWire = Wire(UInt(15.W))
  val romInfoWire = Wire(UInt(20.W))
  val romSizesWire = Wire(UInt(32.W))

  val registerInterface = Wire(new MemoryInterface(addressWidth = 16, dataWidth = 32))
  val sdramHost = Wire(new MemoryInterface(addressWidth = 25, dataWidth = 32))
  val cartRamHost = Wire(new MemoryInterface(addressWidth = NesCartRam.AddressWidth, dataWidth = 32))
  val commandInterface = Wire(new MemoryInterface(addressWidth = 16, dataWidth = 32))
  val memoryMap = MemoryMap(
    addressWidth = 32,
    dataWidth = 32,
    entries = Seq(
      0x0.U(4.W) -> registerInterface,
      0x3.U(4.W) -> sdramHost,
      0x4.U(4.W) -> cartRamHost,
      0xF0.U(8.W) -> commandInterface,
    ))
  io.host.mem.unsafe :<>= memoryMap.unsafe
  memoryMap.writeStrobe := "b1111".U

  val romInfo = Module(new NesRomInfo)
  val flagsReg = Mux(romInfo.io.valid, romInfo.io.result.flags, 0.U)

  registerInterface <> RegisterMap(
    addressWidth = 16,
    dataWidth = 32,
    entries = Seq(
      0x0000 -> RegisterMap.Entry.rw(configReg),
      0x0004 -> RegisterMap.Entry.r(flagsReg(31, 0)),
      0x0008 -> RegisterMap.Entry.r(flagsReg(63, 32)),
      0x000C -> RegisterMap.Entry.r(romInfoWire),
      0x0010 -> RegisterMap.Entry.r(romFileSize),
      0x0014 -> RegisterMap.Entry(32,
        RegisterMap.ReadFn(_ => Cat(ssSlot, 0.U(2.W))),
        RegisterMap.WriteFn((write, data) => {
          ssControlWrite := write
          ssControlWriteData := data
        })),
      0x0018 -> RegisterMap.Entry.rw(ssSlotReg),
      0x001C -> RegisterMap.Entry.r(romSizesWire),
      0x0100 -> RegisterMap.Entry.r(statusWire),
      0x1000 -> RegisterMap.Entry.rw(statRunCycles),
      0x1004 -> RegisterMap.Entry.rw(statStallCycles),
      0x1008 -> RegisterMap.Entry.rw(statStallsPrg),
      0x100C -> RegisterMap.Entry.rw(statStallsChr),
      0x1010 -> RegisterMap.Entry.rw(statRomRequests),
      0x1014 -> RegisterMap.Entry.rw(statRomBufferHits),
      0x1018 -> RegisterMap.Entry.rw(statRomCacheMisses),
      0x101C -> RegisterMap.Entry.rw(statChrReads),
      0x1020 -> RegisterMap.Entry.rw(statFrames),
      0x1024 -> RegisterMap.Entry.rw(statStallsSs),
      0x2000 -> RegisterMap.Entry(32, RegisterMap.ReadFn(), RegisterMap.WriteFn((write, data) => {
        when (write && data(0)) { warmResetWrite := true.B }
      })),
    )
  )

  //////////////////////////////////
  // SRAM: host (CHR loading), PPU (CHR / high cart RAM), save-state engine
  //////////////////////////////////
  val sramArbiter = Module(new MemoryArbiter(addressWidth = 18, dataWidth = 16, n = 3, fair = true))
  val sramHostAdapter = Module(new SramHostAdapter)
  sramArbiter.io.initiator(0) <> sramHostAdapter.io.sram
  val sramHostPort = Wire(new MemoryInterface(addressWidth = 19, dataWidth = 32))
  sramHostAdapter.io.host <> sramHostPort

  //////////////////////////////////
  // SDRAM: host (port 0) and the ROM cache with the save-state side port (port 1)
  //////////////////////////////////
  val sdramArbiter = Module(new PipelineMemoryArbiter(addressWidth = 25, dataWidth = 32, n = 2))
  val sdramBridgePort = Wire(new MemoryInterface(addressWidth = 25, dataWidth = 32))
  locally {
    val bridge = Module(new PipelineInterfaceBridge(addressWidth = 25, dataWidth = 32))
    bridge.io.source <> sdramBridgePort
    bridge.io.dest <> sdramArbiter.io.initiator(0)
  }

  //////////////////////////////////
  // ROM file transfer: header analysis and the CHR splitter
  //////////////////////////////////
  // The host streams the .nes file into the SDRAM window. NesRomInfo takes
  // the header from the first four words; once it is complete, every word at
  // or beyond the CHR offset goes to the SRAM instead (CHR byte k at SRAM
  // byte k), anything past the SRAM's CHR area is dropped, and the rest
  // (header, PRG) reaches the SDRAM as usual. The states file (0x1000000 up)
  // always goes to the SDRAM.
  val romInfoClear = WireDefault(false.B)
  val romInfoFinish = WireDefault(false.B)
  romInfo.io.clear := romInfoClear
  romInfo.io.stream.valid := sdramHost.enable && sdramHost.write && romLoading
  romInfo.io.stream.address := sdramHost.address
  romInfo.io.stream.data := sdramHost.dataWrite
  romInfo.io.finish := romInfoFinish
  romInfo.io.fileSize := DontCare
  val chrOffset = romInfo.io.live.chrOffset
  val hostInChr = romLoading && romInfo.io.headerComplete && sdramHost.address < NesSaveState.SlotsBase.U &&
    sdramHost.address >= chrOffset
  val hostChrByte = sdramHost.address - chrOffset
  val hostChrDrop = hostInChr && hostChrByte >= SramMap.ChrBytes.U
  val hostChrSram = hostInChr && !hostChrDrop
  val hostDropDone = RegInit(false.B)
  hostDropDone := sdramHost.enable && hostChrDrop && !hostDropDone

  sdramBridgePort.enable := sdramHost.enable && !hostInChr
  sdramBridgePort.address := sdramHost.address
  sdramBridgePort.write := sdramHost.write
  sdramBridgePort.dataWrite := sdramHost.dataWrite
  sdramBridgePort.writeStrobe := sdramHost.writeStrobe
  sramHostPort.enable := sdramHost.enable && hostChrSram
  sramHostPort.address := hostChrByte(18, 0)
  sramHostPort.write := sdramHost.write
  sramHostPort.dataWrite := sdramHost.dataWrite
  sramHostPort.writeStrobe := sdramHost.writeStrobe
  sdramHost.done := Mux(hostInChr, Mux(hostChrDrop, hostDropDone, sramHostPort.done), sdramBridgePort.done)
  sdramHost.dataRead := Mux(hostInChr, Mux(hostChrDrop, 0.U, sramHostPort.dataRead), sdramBridgePort.dataRead)

  val romValid = romInfo.io.valid
  val rom = romInfo.io.result
  val romSupported = romValid && rom.supported
  /** The system type in use: the setting, or (Auto) the header's timing. */
  val sysType = Mux(configReg.sysType === 0.U, Mux(romValid, rom.autoSysType, 0.U), configReg.sysType - 1.U)
  val regionPal = sysType === 1.U
  romInfoWire := Cat(romValid, rom.supported, rom.magicOk, rom.trainer, rom.mapperSupported, rom.chrRam, rom.battery,
    rom.nes20, sysType, rom.mapper)
  romSizesWire := Cat(rom.prgBytes(24, 10).pad(16), rom.chrBytes(24, 10).pad(16))

  //////////////////////////////////
  // Save-state slots (SDRAM): scanner and the core's slot port share the side port
  //////////////////////////////////
  val ssScanner = Module(new NesSaveSlotScanner)
  val ssScanFromFile = WireDefault(false.B)
  val ssRescan = WireDefault(false.B)
  ssScanner.io.start := ssScanFromFile || ssRescan
  ssScanner.io.invalidate := false.B
  val ssPort = Module(new NesSaveStatePort)

  //////////////////////////////////
  // Framework command interface
  //////////////////////////////////
  val commandHostState = RegInit(CommandState.idle)
  val regCommandHost = Reg(Vec(4, UInt(32.W)))
  commandInterface <> RegisterMap(
    addressWidth = 16,
    dataWidth = 32,
    entries =
      regCommandHost.zipWithIndex.map { case (reg, i) => (0x0000 + (4 * i) -> RegisterMap.Entry.rw(reg)) }
  )
  val cartRam = Module(new NesCartRam)
  val cartRamClearDirty = WireDefault(false.B)
  /** Waiting for the ROM analysis after FileWriteEnd 0. */
  val waitRomInfo = RegInit(false.B)
  when (romInfo.io.valid) {
    waitRomInfo := false.B
  }
  val setupReady = regCoreSetup && !ssScanner.io.busy
  /**
   * The .sav size to write back: none without a battery, nor for UNROM 512
   * (its battery flag means PRG flash saves, not supported); 8 KiB for a NES
   * 2.0 image with an 8 KiB NVRAM; else 32 KiB (MiSTer's size for iNES 1.0
   * battery images).
   */
  val flashSaveCart = rom.mapper === 30.U && rom.battery
  val saveFileSize = Mux(!romValid || !rom.battery || flashSaveCart, 0.U(32.W),
    Mux(rom.nes20 && rom.nvramShift === 7.U, 0x2000.U(32.W), NesCartRam.SizeBytes.U(32.W)))

  io.host.commandHost.busy := commandHostState === CommandState.busy
  io.host.commandHost.done := commandHostState === CommandState.done
  io.host.commandHost.error := commandHostState === CommandState.error
  when (io.host.commandHost.request) {
    when (commandHostState === CommandState.idle) {
      val command = regCommandHost(0)(15, 0)
      val fileId = regCommandHost(1)(15, 0)
      val fileSize = regCommandHost(2)
      for (reg <- regCommandHost) {
        reg := 0.U
      }
      commandHostState := CommandState.done

      when (command === HostV0.CommandGetStatus.U) {
        when (setupReady) {
          regCommandHost(0) := Mux(regCoreReset, HostV0.StatusCoreHalt.U, HostV0.StatusCoreRun.U)
        } .otherwise {
          regCommandHost(0) := HostV0.StatusSetup.U
        }
      } .elsewhen (command === HostV0.CommandSetupComplete.U) {
        // An image the core cannot run fails the setup: the firmware reports
        // a core error instead of showing a black screen.
        when (!romSupported) {
          commandHostState := CommandState.error
        } .otherwise {
          regCoreSetup := true.B
          coldReset := false.B
        }
      } .elsewhen (command === HostV0.CommandCoreRun.U) {
        regCoreReset := false.B
      } .elsewhen (command === HostV0.CommandCoreHalt.U) {
        regCoreReset := true.B
      } .elsewhen (command === HostV0.CommandNotifyFocus.U) {
        regCoreFocus := regCommandHost(1)(0)
      } .elsewhen (command === HostV0.CommandFileWriteStart.U) {
        when (fileId === 0.U) {
          regCoreSetup := false.B
          coldReset := true.B
          romLoading := true.B
          romInfoClear := true.B
          ssScanner.io.invalidate := true.B
        }
      } .elsewhen (command === HostV0.CommandFileWriteEnd.U) {
        when (fileId === 0.U) {
          romLoading := false.B
          romFileSize := fileSize(24, 0)
          romInfo.io.fileSize := fileSize(24, 0)
          romInfoFinish := true.B
          waitRomInfo := true.B
          commandHostState := CommandState.busy
        } .elsewhen (fileId === 2.U) {
          ssScanFromFile := true.B
          commandHostState := CommandState.busy
        }
      } .elsewhen (command === HostV0.CommandFileReadStart.U) {
        when (fileId === 1.U) {
          regCommandHost(0) := saveFileSize
        } .elsewhen (fileId === 2.U) {
          regCommandHost(0) := ssScanner.io.usedSlots << log2Ceil(NesSaveState.SlotSize)
        }
      } .elsewhen (command === HostV0.CommandFileReadEnd.U) {
        when (fileId === 1.U) {
          cartRamClearDirty := true.B
        }
      } .otherwise {
        commandHostState := CommandState.error
      }
    } .elsewhen (commandHostState === CommandState.busy) {
      when (!waitRomInfo && !romInfoFinish && !ssScanner.io.busy && !ssScanFromFile) {
        commandHostState := CommandState.done
      }
    }
  } .otherwise {
    commandHostState := CommandState.idle
  }
  ssScanner.io.loadedSize := Mux(ssScanFromFile, regCommandHost(2)(22, 0), NesSaveState.FullSize.U)
  io.host.commandCore.request := false.B

  //////////////////////////////////
  // Core, gated clock, resets
  //////////////////////////////////
  val coreRun = Wire(Bool())
  val coreClock = BUFGCE(clock, coreRun)
  /** True in the cycle after a core clock edge was delivered (core outputs are fresh). */
  val tick = RegNext(coreRun, false.B)

  // MiSTer resets the machine when the system type changes.
  val lastSysType = RegNext(sysType, 0.U)
  when (warmResetWrite || lastSysType =/= sysType) {
    warmResetCounter := ~0.U(WarmResetBits.W)
  } .elsewhen (tick && warmResetCounter =/= 0.U) {
    warmResetCounter := warmResetCounter - 1.U
  }
  val hostReset = reset.asBool || regCoreReset || coldReset || warmResetCounter =/= 0.U

  val core = Module(new NesCore)
  NesCore.tieOffUnused(core)
  core.io.clk := coreClock
  core.io.reset_nes := hostReset
  core.io.cold_reset := coldReset
  core.io.sys_type := sysType
  core.io.mapper_flags := flagsReg
  core.io.prg_mask := Mux(romValid, rom.prgMask, 0.U)
  core.io.chr_mask := Mux(romValid, rom.chrMask, 0.U)
  core.io.ppu_rst_behavior := configReg.ppuResetNes
  core.io.ex_sprites := configReg.extraSprites
  core.io.mask := Mux(configReg.maskLeft, 1.U, 0.U)

  //////////////////////////////////
  // Save-state requests (the SNES port's control logic, same register layout)
  //////////////////////////////////
  val ssSaveRequest = RegInit(false.B)
  val ssLoadRequest = RegInit(false.B)
  val ssSaveDone = RegInit(false.B)
  val ssRunPending = RegInit(false.B)
  val ssLastWasSave = RegInit(false.B)
  core.io.save_state := ssSaveRequest
  core.io.load_state := ssLoadRequest
  core.io.savestate_number := ssSlot
  // MiSTer registers the pause request (NES.sv: pausecore <= sleep_savestate).
  core.io.pausecore := withClock(coreClock) { RegNext(core.io.sleep_savestate, false.B) }

  // No states for a cartridge with more CHR-RAM than the state's 32 KiB CHR entry
  // (savestates.vhd; chrMask > 15 = more than 16 x 2 KiB): a load would restore the
  // registers but not the tiles above 32 KiB (Kira Kira Star Night DX, 2026-10-09).
  val ssAvailable = romSupported && core.io.mapper_has_savestate && !(rom.chrRam && rom.chrMask > 15.U)
  val ssSlotValid = ssScanner.io.valid
  val ssRequestSlot = Mux(ssControlWriteData(4), ssSlotReg, ssControlWriteData(3, 2))
  val ssSaveWrite = ssControlWrite && ssControlWriteData(0) && ssAvailable
  val ssLoadWrite = ssControlWrite && ssControlWriteData(1) && ssAvailable && ssSlotValid(ssRequestSlot)

  //////////////////////////////////
  // CPU bus: PRG-ROM (SDRAM through the cache), work RAM and cart RAM (BRAM)
  //////////////////////////////////
  val cpuAddr = core.io.cpumem_addr
  val cpuPrg = cpuAddr(24, 21) === 0.U
  val cpuWorkRam = cpuAddr(24, 11) === (0x380000 >> 11).U
  val cpuCartRam = cpuAddr(24, 15) === (0x3C0000 >> 15).U

  val workRam = Module(new NesDualPortRam(2048))
  workRam.io.a.address := cpuAddr(10, 0)
  workRam.io.a.write := core.io.cpumem_write && cpuWorkRam
  workRam.io.a.dataWrite := core.io.cpumem_dout

  cartRam.io.host <> cartRamHost
  cartRam.io.clearDirty := cartRamClearDirty
  cartRam.io.core.address := cpuAddr(14, 0)
  cartRam.io.core.write := core.io.cpumem_write && cpuCartRam
  cartRam.io.core.dataWrite := core.io.cpumem_dout

  // 2 ways x 1024 lines x 16 bytes, next-line prefetch, a 16-line register
  // buffer in front (HandheldSnes / HandheldPce). Both are flushed (reset)
  // when a new ROM transfer starts: the SDRAM contents change under them.
  // The core is held in reset from that command on and its PRG reads are
  // not forwarded while it is (below), so after a short delay, which lets a
  // fill already in flight finish, nothing uses the path; the cache's
  // 1024-cycle re-initialization is over long before the core runs.
  val romFlushCountdown = RegInit(0.U(8.W))
  when (romFlushCountdown =/= 0.U) {
    romFlushCountdown := romFlushCountdown - 1.U
  }
  when (romInfoClear) {
    romFlushCountdown := 255.U
  }
  val romFlushReg = RegNext(romFlushCountdown === 1.U, false.B)
  val romCacheReset = reset.asBool || romFlushReg
  val romCache = withReset(romCacheReset) {
    Module(new LineReadCache(addressWidth = 25, dataWidth = 32, numLines = 1024, wordsPerLine = 4, ways = 2, prefetchDistance = 2))
  }
  val romBuffer = withReset(romCacheReset) {
    Module(new LineBuffer(addressWidth = 25, numLines = 16, wordsPerLine = 4))
  }
  val sdramMux = Module(new PipelineMemoryLowPriorityMux(addressWidth = 25, dataWidth = 32))
  sdramArbiter.io.initiator(1) <> sdramMux.io.target
  sdramMux.io.main <> romCache.io.out
  // The cache's requests are in PRG space; the PRG starts at file byte 16.
  sdramMux.io.main.address := romCache.io.out.address + 16.U
  romCache.io.in <> romBuffer.io.out
  romBuffer.io.outLine := romCache.io.line
  romBuffer.io.earlyValid := romCache.io.fillWordValid && configReg.romEarlyAnswers
  romCache.io.eagerIssue := configReg.romEagerIssue
  romBuffer.io.earlyAddress := romCache.io.fillWordAddress
  romBuffer.io.earlyData := romCache.io.fillWordData
  // The side port: the slot scanner while it runs, the save-state port otherwise.
  locally {
    val side = sdramMux.io.side
    val scanning = ssScanner.io.busy
    side.enable := Mux(scanning, ssScanner.io.mem.enable, ssPort.io.mem.enable)
    side.address := Mux(scanning, ssScanner.io.mem.address, ssPort.io.mem.address)
    side.isWrite := Mux(scanning, ssScanner.io.mem.isWrite, ssPort.io.mem.isWrite)
    side.writeStrobe := Mux(scanning, ssScanner.io.mem.writeStrobe, ssPort.io.mem.writeStrobe)
    side.dataWrite := Mux(scanning, ssScanner.io.mem.dataWrite, ssPort.io.mem.dataWrite)
    ssScanner.io.mem.ready := side.ready && scanning
    ssScanner.io.mem.dataRead := side.dataRead
    ssPort.io.mem.ready := side.ready && !scanning
    ssPort.io.mem.dataRead := side.dataRead
  }

  // cpumem_read is a level for the PRG read part of a CPU bus cycle (MiSTer's
  // SDRAM starts an access at its rising edge); a read is requested when the
  // strobe rises or the word changes (the core's outputs only change at
  // delivered edges, so each request fires once). Nothing is requested while
  // the core is held in reset: the CPU does not execute then, but the bus
  // cycle counters run and the mappers that are not reset-gated would issue
  // reads while the host is still transferring files into the SDRAM (a line
  // cached then could hold bytes from before the transfer). The fetch after
  // the reset is a fresh strobe.
  val romReadActive = core.io.cpumem_read && cpuPrg && !hostReset
  val regRomReadActive = RegNext(romReadActive, false.B)
  val regRomAddress = RegNext(cpuAddr(20, 0), 0.U(21.W))
  val romRequest = romReadActive && (!regRomReadActive || regRomAddress(20, 2) =/= cpuAddr(20, 2))
  romBuffer.io.request := romRequest
  romBuffer.io.address := Cat(0.U(4.W), cpuAddr(20, 2), 0.U(2.W))
  val romHit = romBuffer.io.hit
  val romPending = RegInit(false.B)
  val romData = RegInit(0.U(32.W))
  val romDataNow = WireDefault(romData)
  when (romHit || romBuffer.io.respValid) {
    romDataNow := romBuffer.io.dataRead
    romData := romBuffer.io.dataRead
    romPending := false.B
  }
  when (romRequest && !romHit) {
    romPending := true.B
  }
  when (romFlushReg) {
    romPending := false.B
  }
  val romByte = romDataNow.asTypeOf(Vec(4, UInt(8.W)))(cpuAddr(1, 0))
  core.io.cpumem_din := MuxCase(0xFF.U(8.W), Seq(
    cpuPrg -> romByte,
    cpuWorkRam -> workRam.io.a.dataRead,
    cpuCartRam -> cartRam.io.core.dataRead,
  ))

  //////////////////////////////////
  // PPU bus: CHR and high cart RAM (SRAM), CIRAM (BRAM)
  //////////////////////////////////
  val ppuAddr = core.io.ppumem_addr
  val ppuChr = ppuAddr(21, 20) === 2.U && ppuAddr(19, 0) < SramMap.ChrBytes.U
  val ppuHighRam = ppuAddr(21, 15) === (0x3F8000 >> 15).U
  val ppuCiram = ppuAddr(21, 11) === (0x3A0000 >> 11).U
  val ppuSram = ppuChr || ppuHighRam

  val ciram = Module(new NesDualPortRam(2048))
  ciram.io.a.address := ppuAddr(10, 0)
  ciram.io.a.write := core.io.ppumem_write && ppuCiram
  ciram.io.a.dataWrite := core.io.ppumem_dout

  val chrPort = Module(new BytePortBridge(addressWidth = 19, wordBase = 0))
  sramArbiter.io.initiator(1) <> chrPort.io.mem
  chrPort.io.address := Mux(ppuHighRam, Cat(0xF.U(4.W), ppuAddr(14, 0)), ppuAddr(18, 0))
  chrPort.io.dataWrite := core.io.ppumem_dout
  chrPort.io.ceN := !ppuSram
  chrPort.io.oeN := !core.io.ppumem_read
  chrPort.io.weN := !core.io.ppumem_write
  chrPort.io.sample := true.B
  chrPort.io.invalidate := false.B
  chrPort.io.bufferOff := true.B
  chrPort.io.oneWay := false.B
  core.io.ppumem_din := MuxCase(0xFF.U(8.W), Seq(
    ppuSram -> chrPort.io.dataRead,
    ppuCiram -> ciram.io.a.dataRead,
  ))

  //////////////////////////////////
  // Save-state RAM port (bytes, MiSTer addresses) and slot port
  //////////////////////////////////
  val ssAddr = core.io.Savestate_SDRAMAddr
  val ssRd = core.io.Savestate_SDRAMRdEn
  val ssWr = core.io.Savestate_SDRAMWrEn
  val ssWorkRam = ssAddr(24, 11) === (0x380000 >> 11).U
  val ssCiram = ssAddr(24, 11) === (0x3A0000 >> 11).U
  val ssCartRam = ssAddr(24, 15) === (0x3C0000 >> 15).U
  val ssChr = ssAddr(24, 20) === 2.U && ssAddr(19, 0) < SramMap.ChrBytes.U
  val ssHighRam = ssAddr(24, 15) === (0x3F8000 >> 15).U
  val ssActive = core.io.sleep_savestate

  workRam.io.b.address := ssAddr(10, 0)
  workRam.io.b.write := ssWr && ssWorkRam
  workRam.io.b.dataWrite := core.io.Savestate_SDRAMWriteData
  ciram.io.b.address := ssAddr(10, 0)
  ciram.io.b.write := ssWr && ssCiram
  ciram.io.b.dataWrite := core.io.Savestate_SDRAMWriteData
  cartRam.io.ssActive := ssActive
  cartRam.io.ss.address := ssAddr(14, 0)
  cartRam.io.ss.write := ssWr && ssCartRam
  cartRam.io.ss.dataWrite := core.io.Savestate_SDRAMWriteData

  // CHR-ROM is not state: a load writes back only CHR-RAM and the high cart RAM.
  val ssSramPort = Module(new BytePortBridge(addressWidth = 19, wordBase = 0))
  sramArbiter.io.initiator(2) <> ssSramPort.io.mem
  ssSramPort.io.address := Mux(ssHighRam, Cat(0xF.U(4.W), ssAddr(14, 0)), ssAddr(18, 0))
  ssSramPort.io.dataWrite := core.io.Savestate_SDRAMWriteData
  ssSramPort.io.ceN := !(ssChr || ssHighRam)
  ssSramPort.io.oeN := !ssRd
  ssSramPort.io.weN := !(ssWr && (ssHighRam || (ssChr && rom.chrRam)))
  ssSramPort.io.sample := true.B
  ssSramPort.io.invalidate := false.B
  ssSramPort.io.bufferOff := true.B
  ssSramPort.io.oneWay := false.B
  core.io.Savestate_SDRAMReadData := MuxCase(0.U(8.W), Seq(
    ssWorkRam -> workRam.io.b.dataRead,
    ssCiram -> ciram.io.b.dataRead,
    ssCartRam -> cartRam.io.ss.dataRead,
    (ssChr || ssHighRam) -> ssSramPort.io.dataRead,
  ))

  ssPort.io.ena := core.io.SAVE_out_ena
  ssPort.io.address := core.io.SAVE_out_Adr
  ssPort.io.rnw := core.io.SAVE_out_rnw
  ssPort.io.dataWrite := core.io.SAVE_out_Din
  core.io.SAVE_out_Dout := ssPort.io.dataRead
  core.io.SAVE_out_done := ssPort.io.done
  ssPort.io.tick := tick
  ssPort.io.coreRun := coreRun

  //////////////////////////////////
  // Stall control
  //////////////////////////////////
  // The core samples CPU-bus data at div_cpu 10-12 (gb_cpu_latch) and PPU
  // VRAM data at ppu_ce (gb_ppu_latch). A request starts four or more clocks
  // before its latch (div_cpu 6; the first cycle of a PPU read), so a buffer
  // or cache hit and an idle SRAM answer in time; the edge is withheld only
  // while a read is still outstanding at a latch. Block RAM answers the cycle
  // after its address and never stalls. The save-state engine's SRAM reads
  // stall until answered (the core is paused then anyway).
  val stallPrg = romPending && !romBuffer.io.respValid && core.io.gb_cpu_latch
  val stallChr = (chrPort.io.pendingRead && core.io.gb_ppu_latch) || chrPort.io.full
  val stallSs = ssSramPort.io.pending
  val stall = stallPrg || stallChr || stallSs
  val coreWant = (regCoreFocus || hostReset || ssRunPending) && !stall
  // PAL: withhold one edge in ~110 (HandheldSnes palPace), so the core sees
  // a PAL master clock on average.
  val palPace = RegInit(0.U(25.W))
  val palSkip = WireDefault(false.B)
  when (!regionPal) {
    palPace := 0.U
  } .elsewhen (coreWant) {
    val sum = palPace + (MasterClockNtscHz - MasterClockPalHz).U
    when (sum >= MasterClockNtscHz.U) {
      palPace := sum - MasterClockNtscHz.U
      palSkip := true.B
    } .otherwise {
      palPace := sum
    }
  }
  coreRun := coreWant && !palSkip

  // Save-state request bookkeeping (as HandheldSnes).
  when (coreRun) {
    ssSaveRequest := false.B
    ssLoadRequest := false.B
  }
  when (ssControlWrite) {
    when (ssSaveWrite) {
      ssSaveRequest := true.B
      ssSaveDone := false.B
      ssLastWasSave := true.B
    }
    when (ssLoadWrite) {
      ssLoadRequest := true.B
      ssLastWasSave := false.B
    }
    ssSlot := ssRequestSlot
  }
  when (ssPort.io.headerWritten) {
    ssSaveDone := true.B
  }
  val ssTimeout = RegInit(0.U(log2Ceil(5 * ClockSystemHz + 1).W))
  val ssTimedOut = ssRunPending && ssTimeout === (5 * ClockSystemHz).U
  ssTimeout := Mux(ssRunPending, ssTimeout + 1.U, 0.U)
  val regSleep = RegNext(core.io.sleep_savestate, false.B)
  /** The engine was seen busy since the request (so its fall ends the run). */
  val ssSeenBusy = RegInit(false.B)
  when (core.io.sleep_savestate) {
    ssSeenBusy := true.B
  }
  when (regSleep && !core.io.sleep_savestate) {
    ssRunPending := false.B
    ssSeenBusy := false.B
    when (ssLastWasSave) {
      ssRescan := true.B
    }
  }
  when (ssTimedOut) {
    ssSaveRequest := false.B
    ssLoadRequest := false.B
    ssRunPending := false.B
  }
  when (ssControlWrite) {
    ssRunPending := ssSaveWrite || ssLoadWrite
    ssSeenBusy := false.B
  }
  when (hostReset) {
    ssSaveRequest := false.B
    ssLoadRequest := false.B
    ssSaveDone := false.B
    ssRunPending := false.B
  }
  val ssBusyStretch = RegInit(0.U(log2Ceil(SsBusyStretchCycles + 1).W))
  when (core.io.sleep_savestate) {
    ssBusyStretch := SsBusyStretchCycles.U
  } .elsewhen (ssBusyStretch =/= 0.U) {
    ssBusyStretch := ssBusyStretch - 1.U
  }
  val ssBusy = core.io.sleep_savestate || ssBusyStretch =/= 0.U

  statusWire := Cat(
    ssSlotValid,                        // 14:11
    ssRunPending,                       // 10
    ssSaveRequest || ssLoadRequest,     // 9
    ssSaveDone,                         // 8
    ssBusy,                             // 7
    ssAvailable,                        // 6
    false.B,                            // 5
    core.io.corepaused,                 // 4
    coldReset,                          // 3
    regCoreFocus,                       // 2
    regCoreReset,                       // 1
    regCoreSetup,                       // 0
  )

  //////////////////////////////////
  // Core-clocked glue: joypad, video capture
  //////////////////////////////////
  val capture = withClock(coreClock) { Module(new NesVideoCapture) }
  withClock (coreClock) {
    val joypad = Module(new NesJoypad)
    val buttonFilter = Module(new ButtonFilter(new InputV0.Buttons))
    buttonFilter.io.enable := true.B
    buttonFilter.io.input := io.input.buttons
    joypad.io.buttons := buttonFilter.io.output
    joypad.io.swapAB := configReg.buttonsBY
    joypad.io.strobe := core.io.joypad_out(0)
    joypad.io.clocks := core.io.joypad_clock
    core.io.joypad1_data := joypad.io.port1
    core.io.joypad2_data := joypad.io.port2
  }
  capture.io.nesDiv := core.io.nes_div
  capture.io.color := core.io.color
  capture.io.emphasis := core.io.emphasis
  capture.io.cycle := core.io.cycle
  capture.io.scanline := core.io.scanline
  capture.io.paused := core.io.corepaused
  capture.io.palette := configReg.palette
  capture.io.hideOverscan := configReg.hideOverscan

  // Registers in the gated domain change only at delivered edges, so a
  // one-cycle strobe there is taken here once by qualifying it with `tick`.
  io.video.dataEnable := capture.io.strobe && tick
  io.video.data.r := capture.io.pixel.r
  io.video.data.g := capture.io.pixel.g
  io.video.data.b := capture.io.pixel.b
  io.video.hblank := capture.io.hblank
  io.video.vblank := capture.io.vblank

  //////////////////////////////////
  // Audio
  //////////////////////////////////
  // `sample` is offset binary, re-mixed at the CPU rate; a CIC over delivered
  // core clocks (512 of them per output sample, ~41.95 kHz) removes what
  // would alias, Sound Filter's low-pass follows (NesAudioFilter; Off passes
  // the CIC's samples as before), and the rate adapter smooths the jitter of
  // stalls and PAL pacing. Muted without focus.
  val decimator = Module(new AudioDecimator)
  decimator.io.enable := tick
  decimator.io.in := (core.io.sample ^ 0x8000.U).asSInt
  // The decimator's `out` holds the new sample from its outValid on. The NES is mono: both channels the same.
  val soundFilter = Module(new NesAudioFilter)
  soundFilter.io.in(0) := decimator.io.out
  soundFilter.io.in(1) := decimator.io.out
  soundFilter.io.inValid := decimator.io.outValid
  soundFilter.io.filter := configReg.soundFilter
  soundFilter.io.attenuate := false.B
  val audioAdapter = Module(new AudioRateAdapter(nominalPeriod = 512.0, fifoDepth = 256, gainShift = 12))
  audioAdapter.io.inValid := soundFilter.io.outValid
  audioAdapter.io.inLeft := soundFilter.io.out(0)
  audioAdapter.io.inRight := soundFilter.io.out(1)
  io.audio.left := Mux(regCoreFocus, audioAdapter.io.outLeft, 0.S)
  io.audio.right := Mux(regCoreFocus, audioAdapter.io.outRight, 0.S)

  //////////////////////////////////
  // Statistics
  //////////////////////////////////
  when (regCoreFocus) {
    when (coreRun) {
      statRunCycles := statRunCycles + 1.U
    } .elsewhen (!palSkip) {
      statStallCycles := statStallCycles + 1.U
      when (stallPrg) { statStallsPrg := statStallsPrg + 1.U }
      when (stallChr) { statStallsChr := statStallsChr + 1.U }
      when (stallSs) { statStallsSs := statStallsSs + 1.U }
    }
  }
  when (romRequest) { statRomRequests := statRomRequests + 1.U }
  when (romHit) { statRomBufferHits := statRomBufferHits + 1.U }
  when (romCache.io.miss) { statRomCacheMisses := statRomCacheMisses + 1.U }
  when (chrPort.io.readIssued) { statChrReads := statChrReads + 1.U }
  when (capture.io.vblank && !RegNext(capture.io.vblank, true.B)) { statFrames := statFrames + 1.U }

  //////////////////////////////////
  // SRAM and SDRAM controllers (as HandheldSnes)
  //////////////////////////////////
  // The pins go through the controller's I/O-block register stage (the SNES port's fix for
  // placement-dependent SRAM timing, cfab7caa; one more cycle per access, 2026-10-09).
  val sramController = Module(new AsyncSramController(addressWidth = 18, dataWidth = 16, registeredOutputs = true))
  io.sram.ceN := false.B
  io.sram.weN := sramController.io.signals.weN
  io.sram.oeN := sramController.io.signals.oeN
  io.sram.writeMaskN := sramController.io.signals.writeMaskN
  io.sram.address := sramController.io.signals.address
  sramController.io.signals.dataIn := io.sram.dataIn
  io.sram.dataOut := sramController.io.signals.dataOut
  io.sram.dataDir := sramController.io.signals.dataDir
  sramController.io.mem <> sramArbiter.io.target

  withClock(clockSdram) {
    val config = BurstSdramController.Config(
      clockFrequency = ClockSdramHz,
      accessLength = 2,
      timeRsc = (2 * 1_000_000_000) / ClockSdramHz, /* 2 clocks */
      timeWr = (2 * 1_000_000_000) / ClockSdramHz, /* 2 clocks */
      enableBurst = true,
      readCaptureFalling = true,
    )
    val sdram = Module(new BurstSdramController(config))
    val cdc = Module(new PipelineMemoryBurstCdc(
      addressWidth = 25,
      dataWidth = 32,
      addressBurstIncrement = 4,
      enablePrefetch = true,
    ))
    cdc.io.slowClock := clock
    cdc.io.initiator <> sdramArbiter.io.target
    cdc.io.target <> sdram.io.mem

    io.sdram.clock := clockSdramPin
    io.sdram.cke := sdram.io.signals.cke
    io.sdram.cs := sdram.io.signals.cs
    io.sdram.ras := sdram.io.signals.ras
    io.sdram.cas := sdram.io.signals.cas
    io.sdram.we := sdram.io.signals.we
    io.sdram.dqm := sdram.io.signals.dqm
    io.sdram.bank := sdram.io.signals.bank
    io.sdram.address := sdram.io.signals.address
    sdram.io.signals.dataIn := io.sdram.dataIn
    io.sdram.dataOut := sdram.io.signals.dataOut
    io.sdram.dataDir := sdram.io.signals.dataDir
  }
}
