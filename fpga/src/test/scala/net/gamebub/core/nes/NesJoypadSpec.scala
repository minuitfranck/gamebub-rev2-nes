package net.gamebub.core.nes

import net.gamebub.core.pce.PceTreadle
import org.scalatest.freespec.AnyFreeSpec

/**
 * The controller ports as the CPU reads them ($4016 strobe, then clocked
 * reads): port 1 returns A, B, Select, Start, Up, Down, Left, Right, then
 * ones; port 2 (no pad) eight zeros then ones; the B / Y layout; a strobe
 * held high keeps returning A. Runs on treadle2 through [[PceTreadle]].
 */
class NesJoypadSpec extends AnyFreeSpec {
  private val Order = Seq("a", "b", "select", "start", "up", "down", "left", "right")
  private val All = Seq("a", "b", "x", "y", "up", "down", "left", "right", "l", "r", "start", "select")

  private def press(sim: PceTreadle.Sim, pressed: Set[String], swap: Boolean): Unit = {
    for (b <- All) sim.poke(s"io_buttons_$b", pressed(b))
    sim.poke("io_swapAB", swap)
  }

  /** Strobe, then read `n` bits from `port` (1 or 2) the way the CPU does: clock high for a few cycles, then low. */
  private def readPort(sim: PceTreadle.Sim, port: Int, n: Int): Seq[Int] = {
    sim.poke("io_strobe", 1); sim.poke("io_clocks", 0); sim.step(3)
    sim.poke("io_strobe", 0); sim.step(3)
    (0 until n).map { _ =>
      val bit = (sim.peek(s"io_port$port") & 1).toInt
      sim.poke("io_clocks", if (port == 1) 1 else 2); sim.step(4)
      sim.poke("io_clocks", 0); sim.step(4)
      bit
    }
  }

  "serial order, idle port 2, layouts and a held strobe" in {
    val sim = PceTreadle(new NesJoypad, "NesJoypadSpec")
    sim.poke("reset", 1); sim.poke("io_strobe", 0); sim.poke("io_clocks", 0); press(sim, Set(), swap = false)
    sim.step(); sim.poke("reset", 0)

    for (b <- Order) {
      press(sim, Set(b), swap = false)
      val bits = readPort(sim, 1, 10)
      val want = Order.map(o => if (o == b) 1 else 0) ++ Seq(1, 1)
      assert(bits == want, s"pressing $b: got $bits, want $want")
    }
    press(sim, Set("start", "right"), swap = false)
    assert(readPort(sim, 2, 10) == Seq(0, 0, 0, 0, 0, 0, 0, 0, 1, 1), "port 2 without a pad")

    // B / Y layout: B is NES A, Y is NES B, A is unused.
    press(sim, Set("b"), swap = true)
    assert(readPort(sim, 1, 2) == Seq(1, 0), "B / Y: B -> A")
    press(sim, Set("y"), swap = true)
    assert(readPort(sim, 1, 2) == Seq(0, 1), "B / Y: Y -> B")
    press(sim, Set("a"), swap = true)
    assert(readPort(sim, 1, 2) == Seq(0, 0), "B / Y: A unused")

    // While the strobe is high every read returns A.
    press(sim, Set("a"), swap = false)
    sim.poke("io_strobe", 1); sim.step(3)
    for (_ <- 0 until 3) {
      assert((sim.peek("io_port1") & 1) == 1, "strobe high: A")
      sim.poke("io_clocks", 1); sim.step(4); sim.poke("io_clocks", 0); sim.step(4)
    }
    sim.finish()
  }
}
