package net.gamebub.core.nes

import chisel3._
import chisel3.util._
import lib.mem.PipelineMemoryInterface

object NesSaveState {
  val Slots = 4
  /** One slot: 0x10000 DWORDs (statemanager.vhd SAVESTATESIZE). */
  val SlotSize = 0x4_0000
  /** SDRAM byte address of slot 0 (host window 0x31000000). */
  val SlotsBase = 0x100_0000
  val FullSize = Slots * SlotSize
  /** A valid slot's header, bytes 4..7 (savestates.vhd STATESIZE, the high half of the 64-bit header word). */
  val StateSize = 0x7000
}

/**
 * Adapter from the MiSTer NES save-state slot port (`SAVE_out_*` of
 * savestates.vhd: a one-cycle `ena` with a DWORD address, `rnw`, 64-bit data;
 * the engine then waits for a one-cycle `done`) to the SDRAM, after the SNES
 * port's `SaveStateMemoryPort`: each 64-bit word is two 32-bit accesses,
 * little-endian (low half at byte `SlotsBase + address * 4`, high half 4
 * bytes on).
 *
 * The engine runs in the gated core domain and the adapter in the system
 * domain. `ena` changes only at delivered core edges, so it is taken once, in
 * the first cycle after such an edge (`tick`). `done` is raised when the
 * transfer has completed and dropped at the next delivered core edge
 * (`coreRun` high in that cycle), so the engine sees it at exactly one edge;
 * `dataRead` is valid from then until the next request. No stall is needed:
 * the engine waits for `done` in a state of its own.
 *
 * `headerWritten` pulses when a write to the first DWORD of a slot completes:
 * the engine writes the header (state size and counter) last, so this marks a
 * finished save.
 */
class NesSaveStatePort extends Module {
  import NesSaveState._
  val io = IO(new Bundle {
    val ena = Input(Bool())
    val address = Input(UInt(26.W))
    val rnw = Input(Bool())
    val dataWrite = Input(UInt(64.W))
    val dataRead = Output(UInt(64.W))
    val done = Output(Bool())
    val tick = Input(Bool())
    val coreRun = Input(Bool())
    val busy = Output(Bool())
    val headerWritten = Output(Bool())
    val mem = Flipped(new PipelineMemoryInterface(addressWidth = 25, dataWidth = 32))
  })

  object State extends ChiselEnum {
    val idle, issue, data = Value
  }
  val state = RegInit(State.idle)
  val high = RegInit(false.B)
  val address = Reg(UInt(26.W))
  val write = Reg(Bool())
  val dataWrite = Reg(UInt(64.W))
  val dataRead = RegInit(0.U(64.W))
  val done = RegInit(false.B)

  io.dataRead := dataRead
  io.done := done
  io.busy := state =/= State.idle
  io.headerWritten := false.B

  // DWORD address x 4, plus 4 for the high half (the engine steps by 2 DWORDs per 64-bit word).
  val byteAddress = SlotsBase.U(25.W) + Cat(address(21, 0), 0.U(2.W)) + Mux(high, 4.U, 0.U)
  io.mem.enable := state === State.issue
  io.mem.address := byteAddress
  io.mem.isWrite := write
  io.mem.writeStrobe := "b1111".U
  io.mem.dataWrite := Mux(high, dataWrite(63, 32), dataWrite(31, 0))

  when (done && io.coreRun) {
    done := false.B
  }
  switch (state) {
    is (State.idle) {
      when (io.ena && io.tick) {
        address := io.address
        write := !io.rnw
        dataWrite := io.dataWrite
        high := false.B
        state := State.issue
      }
    }
    is (State.issue) {
      when (io.mem.ready) {
        state := State.data
      }
    }
    is (State.data) {
      when (io.mem.ready) {
        when (!write) {
          when (high) {
            dataRead := Cat(io.mem.dataRead, dataRead(31, 0))
          } .otherwise {
            dataRead := Cat(dataRead(63, 32), io.mem.dataRead)
          }
        }
        when (high) {
          done := true.B
          state := State.idle
          io.headerWritten := write && address(15, 0) === 0.U
        } .otherwise {
          high := true.B
          state := State.issue
        }
      }
    }
  }
}

/**
 * Finds which save-state slots in the SDRAM hold a state (header bytes 4..7 ==
 * `StateSize`), after the SNES port's `SaveStateSlotScanner`. `start` scans all
 * slots; those beyond `loadedSize` (the bytes of the states file the host
 * transferred; `FullSize` after a save) get that word cleared instead, so that
 * a stale state from another game cannot pass the core's own check. `valid`
 * and `usedSlots` (the last valid slot plus one) hold afterwards; `invalidate`
 * forgets every slot (a new ROM).
 */
class NesSaveSlotScanner extends Module {
  import NesSaveState._
  val io = IO(new Bundle {
    val start = Input(Bool())
    val loadedSize = Input(UInt(23.W))
    val invalidate = Input(Bool())
    val busy = Output(Bool())
    val valid = Output(UInt(Slots.W))
    val usedSlots = Output(UInt(log2Ceil(Slots + 1).W))
    val mem = Flipped(new PipelineMemoryInterface(addressWidth = 25, dataWidth = 32))
  })

  object State extends ChiselEnum {
    val idle, decide, clear, read, next = Value
  }
  val state = RegInit(State.idle)
  val slot = Reg(UInt(log2Ceil(Slots).W))
  val loadedSize = Reg(UInt(23.W))
  val valid = RegInit(0.U(Slots.W))
  val dataPhase = RegInit(false.B)

  io.busy := state =/= State.idle
  io.valid := valid
  io.usedSlots := (0 until Slots).map(i => Mux(valid(i), (i + 1).U(io.usedSlots.getWidth.W), 0.U)).reduce((a, b) => Mux(a > b, a, b))

  val access = state === State.clear || state === State.read
  io.mem.enable := access && !dataPhase
  io.mem.address := SlotsBase.U(25.W) + (slot << log2Ceil(SlotSize)) + 4.U
  io.mem.isWrite := state === State.clear
  io.mem.writeStrobe := "b1111".U
  io.mem.dataWrite := 0.U
  val done = access && dataPhase && io.mem.ready
  when (access && io.mem.ready) {
    dataPhase := !dataPhase
  }

  switch (state) {
    is (State.idle) {
      when (io.start) {
        loadedSize := io.loadedSize
        slot := 0.U
        state := State.decide
      }
    }
    is (State.decide) {
      val loaded = ((slot +& 1.U) << log2Ceil(SlotSize)) <= loadedSize
      state := Mux(loaded, State.read, State.clear)
    }
    is (State.clear) {
      when (done) {
        valid := valid & ~UIntToOH(slot, Slots)
        state := State.next
      }
    }
    is (State.read) {
      when (done) {
        val ok = io.mem.dataRead === StateSize.U
        valid := Mux(ok, valid | UIntToOH(slot, Slots), valid & ~UIntToOH(slot, Slots))
        state := State.next
      }
    }
    is (State.next) {
      when (slot === (Slots - 1).U) {
        state := State.idle
      } .otherwise {
        slot := slot + 1.U
        state := State.decide
      }
    }
  }
  when (io.invalidate) {
    valid := 0.U
  }
}
