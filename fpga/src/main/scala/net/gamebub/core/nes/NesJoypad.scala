package net.gamebub.core.nes

import chisel3._
import chisel3.util._
import net.gamebub.framework.interface.InputV0

/**
 * The two controller ports' serial shift registers, as MiSTer's top level
 * (`NES.sv.reference`, `joypad_bits` / `joypad_bits2`), in the core's clock
 * domain so that strobes and clocks are seen in core time.
 *
 * While the strobe (`joypad_out[0]`) is high, port 1 loads
 * `{16'hFFFF, Right, Left, Down, Up, Start, Select, B, A}` and port 2 (no
 * controller) `{16'hFFFF, 8'h00}`; each falling edge of a port's clock
 * (`joypad_clock[n]`, high while the CPU reads $4016 / $4017) shifts that port
 * right, a later shift winning over a load in the same cycle as upstream. Bit
 * 0 is what the CPU reads, so after the eight buttons a pad reads ones.
 *
 * Button layout: NES A / B on the Game Bub's A / B (default, MiSTer's
 * "Nintendo" layout), or with `swapAB` on B / Y (the bottom-left pair, as the
 * NES pad's B-A row; MiSTer's "positional" layout). X, L and R are unused.
 */
class NesJoypad extends Module {
  val io = IO(new Bundle {
    val buttons = Input(new InputV0.Buttons)
    val swapAB = Input(Bool())
    /** `joypad_out` from the core (bit 0 = strobe). */
    val strobe = Input(Bool())
    /** `joypad_clock` from the core. */
    val clocks = Input(UInt(2.W))
    val port1 = Output(UInt(5.W))
    val port2 = Output(UInt(5.W))
  })

  val b = io.buttons
  val nesA = Mux(io.swapAB, b.b, b.a)
  val nesB = Mux(io.swapAB, b.y, b.b)
  val pad1 = Cat(b.right, b.left, b.down, b.up, b.start, b.select, nesB, nesA)

  val bits1 = RegInit(0.U(24.W))
  val bits2 = RegInit(0.U(24.W))
  val lastClocks = RegInit(0.U(2.W))
  when (io.strobe) {
    bits1 := Cat(0xFFFF.U(16.W), pad1)
    bits2 := Cat(0xFFFF.U(16.W), 0.U(8.W))
  }
  when (!io.clocks(0) && lastClocks(0)) {
    bits1 := Cat(0.U(1.W), bits1(23, 1))
  }
  when (!io.clocks(1) && lastClocks(1)) {
    bits2 := Cat(0.U(1.W), bits2(23, 1))
  }
  lastClocks := io.clocks

  io.port1 := Cat(0.U(4.W), bits1(0))
  io.port2 := Cat(0.U(4.W), bits2(0))
}
