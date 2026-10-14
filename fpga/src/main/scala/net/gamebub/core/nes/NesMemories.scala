package net.gamebub.core.nes

import chisel3._
import chisel3.util._
import lib.mem.MemoryInterface

/** A byte port with a level write strobe, as the glue drives a block RAM from the core. */
class NesBytePort(addressWidth: Int) extends Bundle {
  val address = Input(UInt(addressWidth.W))
  val write = Input(Bool())
  val dataWrite = Input(UInt(8.W))
  val dataRead = Output(UInt(8.W))
}

/**
 * A true dual-port block RAM of bytes with two symmetrical byte ports (one
 * block RAM primitive): port A for the core's bus, port B for the save-state
 * engine. Synchronous read (data the cycle after the address), writes while
 * `write` is high. Both ports are in the module's clock domain (the system
 * clock): the core's outputs only change at delivered core clock edges, so a
 * level write repeated while the core is stalled writes the same byte again.
 */
class NesDualPortRam(sizeBytes: Int) extends Module {
  require(isPow2(sizeBytes))
  val addressWidth = log2Ceil(sizeBytes)
  val io = IO(new Bundle {
    val a = new NesBytePort(addressWidth)
    val b = new NesBytePort(addressWidth)
  })
  val mem = SyncReadMem(sizeBytes, UInt(8.W))
  io.a.dataRead := mem.readWrite(io.a.address, io.a.dataWrite, true.B, io.a.write)
  io.b.dataRead := mem.readWrite(io.b.address, io.b.dataWrite, true.B, io.b.write)
}

object NesCartRam {
  /** Battery / work RAM on the cartridge (MiSTer CARTRAM 0x3C0000): 32 KiB, the largest board RAM of the kept mappers (MMC1 SXROM). */
  val SizeBytes = 0x8000
  val AddressWidth = 15
}

/**
 * The cartridge RAM (PRG-RAM, battery RAM): 32 KiB of block RAM with the
 * core's byte port on port A, and port B shared by the host's `.sav` window
 * (32-bit words, like the PCE `BackupRam`: one lane per cycle) and the
 * save-state engine's byte port (while `ssActive`, i.e. while the core is
 * paused for a save state; the host only transfers the file during setup and
 * exit, so the two never meet).
 *
 * `dirty` is set by any core write and cleared by `clearDirty` (the glue
 * clears it when the host has read the save back).
 */
class NesCartRam extends Module {
  import NesCartRam._
  val io = IO(new Bundle {
    val core = new NesBytePort(AddressWidth)
    val ss = new NesBytePort(AddressWidth)
    val ssActive = Input(Bool())
    val host = new MemoryInterface(addressWidth = AddressWidth, dataWidth = 32)
    val dirty = Output(Bool())
    val clearDirty = Input(Bool())
  })

  val mem = SyncReadMem(SizeBytes, UInt(8.W))
  io.core.dataRead := mem.readWrite(io.core.address, io.core.dataWrite, true.B, io.core.write)

  object HostState extends ChiselEnum {
    val idle, access, drain, done = Value
  }
  val hostState = RegInit(HostState.idle)
  val hostWord = Reg(UInt((AddressWidth - 2).W))
  val hostWrite = Reg(Bool())
  val hostDataWrite = Reg(Vec(4, UInt(8.W)))
  val hostStrobe = Reg(UInt(4.W))
  val hostLane = Reg(UInt(2.W))
  val hostDataRead = Reg(Vec(4, UInt(8.W)))

  val hostAccess = hostState === HostState.access
  val portAddress = Mux(io.ssActive, io.ss.address, Cat(hostWord, hostLane))
  val portWrite = Mux(io.ssActive, io.ss.write, hostAccess && hostWrite && hostStrobe(hostLane))
  val portDataWrite = Mux(io.ssActive, io.ss.dataWrite, hostDataWrite(hostLane))
  val portDataRead = mem.readWrite(portAddress, portDataWrite, true.B, portWrite)
  io.ss.dataRead := portDataRead

  // Lane k's read data lands the cycle after its address: captured while
  // lane k + 1 is presented (lane 3 in `drain`).
  val captureValid = RegNext(hostAccess && !hostWrite, false.B)
  val captureLane = RegNext(hostLane)
  when (captureValid) {
    hostDataRead(captureLane) := portDataRead
  }
  switch (hostState) {
    is (HostState.idle) {
      when (io.host.enable && !io.ssActive) {
        hostWord := io.host.address(AddressWidth - 1, 2)
        hostWrite := io.host.write
        hostDataWrite := io.host.dataWrite.asTypeOf(Vec(4, UInt(8.W)))
        hostStrobe := io.host.writeStrobe
        hostLane := 0.U
        hostState := HostState.access
      }
    }
    is (HostState.access) {
      hostLane := hostLane + 1.U
      when (hostLane === 3.U) {
        hostState := HostState.drain
      }
    }
    is (HostState.drain) {
      hostState := HostState.done
    }
    is (HostState.done) {
      hostState := HostState.idle
    }
  }
  io.host.done := hostState === HostState.done
  io.host.dataRead := hostDataRead.asUInt

  val dirty = RegInit(false.B)
  when (io.clearDirty) {
    dirty := false.B
  }
  when (io.core.write) {
    dirty := true.B
  }
  io.dirty := dirty
}
