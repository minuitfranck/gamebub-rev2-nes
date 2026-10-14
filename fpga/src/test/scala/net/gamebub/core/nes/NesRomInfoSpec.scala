package net.gamebub.core.nes

import net.gamebub.core.pce.PceTreadle
import org.scalatest.freespec.AnyFreeSpec

/**
 * The header analysis against the four test images staged for the owner's
 * card (their first 16 bytes and file sizes, copied here) plus edge cases,
 * with the expected MiSTer `mapper_flags` / masks worked out by hand from
 * NES.sv's GameLoader. Runs on treadle2 through [[PceTreadle]] (no
 * verilator on this machine).
 */
class NesRomInfoSpec extends AnyFreeSpec {
  private case class Expect(
    flags: BigInt, prgMask: Int, chrMask: Int, prgBytes: Int, chrBytes: Int, chrOffset: Int,
    chrRam: Boolean, battery: Boolean, nes20: Boolean, nvramShift: Int, mapper: Int, supported: Boolean,
  )

  private def hex(s: String): Seq[Int] = s.split(" ").toSeq.map(Integer.parseInt(_, 16))

  private def run(sim: PceTreadle.Sim, header: Seq[Int], fileSize: Int): Unit = {
    sim.poke("io_clear", 1); sim.step(); sim.poke("io_clear", 0)
    for (w <- 0 until 4) {
      val word = (0 until 4).map(i => BigInt(header(4 * w + i)) << (8 * i)).sum
      sim.poke("io_stream_valid", 1)
      sim.poke("io_stream_address", BigInt(4 * w))
      sim.poke("io_stream_data", word)
      sim.step(3) // the host holds a write for a few cycles
    }
    // A PRG word after the header must not disturb it.
    sim.poke("io_stream_address", BigInt(16))
    sim.poke("io_stream_data", BigInt(0xDEADBEEFL))
    sim.step()
    sim.poke("io_stream_valid", 0)
    sim.step(2)
    assert(sim.peekBool("io_headerComplete"))
    sim.poke("io_fileSize", BigInt(fileSize))
    sim.poke("io_finish", 1); sim.step(); sim.poke("io_finish", 0)
    sim.step(2)
    assert(sim.peekBool("io_valid"))
  }

  private def check(sim: PceTreadle.Sim, e: Expect): Unit = {
    sim.expect("io_result_flags", e.flags, "flags")
    sim.expect("io_result_prgMask", e.prgMask, "prgMask")
    sim.expect("io_result_chrMask", e.chrMask, "chrMask")
    sim.expect("io_result_prgBytes", e.prgBytes, "prgBytes")
    sim.expect("io_result_chrBytes", e.chrBytes, "chrBytes")
    sim.expect("io_result_chrOffset", e.chrOffset, "chrOffset")
    sim.expect("io_live_chrOffset", e.chrOffset, "live chrOffset")
    sim.expect("io_result_chrRam", if (e.chrRam) 1 else 0, "chrRam")
    sim.expect("io_result_battery", if (e.battery) 1 else 0, "battery")
    sim.expect("io_result_nes20", if (e.nes20) 1 else 0, "nes20")
    sim.expect("io_result_nvramShift", e.nvramShift, "nvramShift")
    sim.expect("io_result_mapper", e.mapper, "mapper")
    sim.expect("io_result_supported", if (e.supported) 1 else 0, "supported")
  }

  "header analysis of the staged test ROMs and edge cases" in {
    val sim = PceTreadle(new NesRomInfo, "NesRomInfoSpec")
    sim.poke("reset", 1); sim.step(); sim.poke("reset", 0)

    // Balloon Fight (USA): NES 2.0, mapper 0, PRG 16 KiB, CHR 8 KiB, horizontal, expansion device 1.
    run(sim, hex("4e 45 53 1a 01 01 00 08 00 00 00 00 00 00 00 01"), 24592)
    check(sim, Expect(
      flags = (BigInt(1) << 53) | (BigInt(1) << 35),
      prgMask = 0x7, chrMask = 0x3, prgBytes = 0x4000, chrBytes = 0x2000, chrOffset = 0x4010,
      chrRam = false, battery = false, nes20 = true, nvramShift = 0, mapper = 0, supported = true))
    sim.expect("io_result_autoSysType", 0)

    // Kirby's Adventure (USA) (Rev 1): NES 2.0, mapper 4, PRG 512 KiB, CHR 256 KiB, battery, NVRAM 8 KiB.
    run(sim, hex("4e 45 53 1a 20 20 42 08 00 00 70 00 00 00 00 01"), 786448)
    check(sim, Expect(
      flags = (BigInt(1) << 53) | (BigInt(1) << 35) | (BigInt(7) << 31) | (BigInt(1) << 25) | (5 << 11) | (5 << 8) | 4,
      prgMask = 0xFF, chrMask = 0x7F, prgBytes = 0x80000, chrBytes = 0x40000, chrOffset = 0x80010,
      chrRam = false, battery = true, nes20 = true, nvramShift = 7, mapper = 4, supported = true))

    // Light From Within Demo / A Journey Into The Unknown: iNES, mapper 30, PRG 512 KiB, CHR-RAM, vertical, battery.
    run(sim, hex("4e 45 53 1a 20 00 e3 10 00 00 00 00 00 00 00 00"), 524304)
    check(sim, Expect(
      flags = (BigInt(1) << 25) | (1 << 15) | (1 << 14) | (5 << 8) | 30,
      prgMask = 0xFF, chrMask = 0x3, prgBytes = 0x80000, chrBytes = 0, chrOffset = 0x80010,
      chrRam = true, battery = true, nes20 = false, nvramShift = 0, mapper = 30, supported = true))

    // A dirty iNES 1.0 header ("DiskDude!" in bytes 7-15): the upper mapper nibble is ignored.
    run(sim, hex("4e 45 53 1a 08 10 41 44 69 73 6b 44 75 64 65 21"), 16 + 0x20000 + 0x20000)
    sim.expect("io_result_mapper", 4, "dirty header mapper")
    sim.expect("io_result_supported", 1)

    // Trainer present: refused (as MiSTer).
    run(sim, hex("4e 45 53 1a 02 01 04 00 00 00 00 00 00 00 00 00"), 16 + 512 + 0x8000 + 0x2000)
    sim.expect("io_result_trainer", 1)
    sim.expect("io_result_supported", 0, "trainer")

    // An unbuilt mapper (5, MMC5): refused.
    run(sim, hex("4e 45 53 1a 10 10 50 00 00 00 00 00 00 00 00 00"), 16 + 0x40000 + 0x20000)
    sim.expect("io_result_mapper", 5)
    sim.expect("io_result_mapperSupported", 0)
    sim.expect("io_result_supported", 0, "mapper 5")

    // No magic: refused. Truncated file: refused.
    run(sim, hex("00 45 53 1a 01 01 00 00 00 00 00 00 00 00 00 00"), 24592)
    sim.expect("io_result_supported", 0, "magic")
    run(sim, hex("4e 45 53 1a 02 01 00 00 00 00 00 00 00 00 00 00"), 16 + 0x4000)
    sim.expect("io_result_supported", 0, "truncated")

    // NES 2.0 PAL timing and a PRG size in the exponent-multiplier form (2^16 x 1 = 64 KiB).
    run(sim, hex("4e 45 53 1a 40 01 00 08 00 0f 00 00 01 00 00 00"), 16 + 0x10000 + 0x2000)
    sim.expect("io_result_prgBytes", 0x10000, "exponent PRG")
    sim.expect("io_result_autoSysType", 1, "PAL")
    sim.expect("io_result_supported", 1)

    // CHR-ROM too large for the SRAM's CHR area (512 KiB): refused.
    run(sim, hex("4e 45 53 1a 10 40 40 00 00 00 00 00 00 00 00 00"), 16 + 0x40000 + 0x80000)
    sim.expect("io_result_supported", 0, "CHR 512 KiB")
    sim.finish()
  }
}
