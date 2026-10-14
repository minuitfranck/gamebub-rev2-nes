# NES / Famicom core for the Game Bub rev2

An FPGA core for the rev2 (vertical) [Game Bub](https://github.com/elipsitz/gamebub) handheld: the MiSTer NES core
(Ludvig Strigeus's FPGANES, extended by the MiSTer contributors) on the Game Bub's FPGA framework, with battery saves
and save states. Current build: **r2.13**.

Not affiliated with, endorsed by or supported by Eli Lipsitz or the Game Bub project. The rev4 has its own
official releases; this core runs on a rev2 with the rev2 custom firmware.

## AI disclosure

This port was made with an AI assistant. **Claude** (Anthropic) wrote the Game Bub glue, the changes to the vendored
core, the simulation benches, the build scripts and these documents, working from the direction of the maintainer,
minuitfranck, who chose what to build, ran every build on a rev2 handheld and decided what was kept. The vendored
MiSTer core, the CPU and the save-state machinery inside it were written by their authors listed under Credits.
Commits made with the assistant carry a `Co-Authored-By: Claude` line. Treat the port's own code as reviewed by
testing on one handheld and by simulation, not by a second engineer.

## What is in it

- **The MiSTer NES core** (`NES_MiSTer` @ 26e2efb, 2026-09-16): the 6502 (T65), the PPU, the APU, the machine, the
  mapper multiplexer with the mappers below, and MiSTer's save-state machinery. The changes made for this port are
  listed in [`fpga/verilog/nes/README.md`](fpga/verilog/nes/README.md). Not vendored: the Famicom Disk System, NSF
  playback, the expansion audio chips (MMC5, VRC6 / VRC7, Namco 163, Sunsoft 5B), the Zapper and other peripherals,
  and MiSTer's video pipeline, whose timing and palettes the glue reproduces.
- **Mappers built:** 0 (NROM), 1 (MMC1), 2 (UxROM), 3 (CNROM), the MMC3 family (4, 118, 119 and relatives), 7
  (AxROM), 9 and 10 (MMC2 / MMC4), 13, 28, 30 (UNROM 512), 34, 64, 66, 70, 71, 77 to 81, 89, 93 to 97, 105, 107, 113,
  152, 155, 158, 165, 171, 180, 184, 185, 232. An image the core cannot run (an unbuilt mapper, a trainer, CHR-ROM over
  480 KiB, a bad header) is reported as an error instead of starting.
- **The Game Bub glue** (Chisel, [`fpga/src/main/scala/net/gamebub/core/nes/`](fpga/src/main/scala/net/gamebub/core/nes/)):
  the `.nes` file in SDRAM behind a 32 KiB two-way line cache, with the core's clock gated at the edges where data
  has not arrived, so the core sees MiSTer's fixed memory latency; CHR in the SRAM chip; the `.sav` battery file; four
  save-state slots in SDRAM, written as one `.ss` file per game in MiSTer's format; the screen path with MiSTer's
  five palettes, NTSC, PAL and Dendy pacing; a sound filter; the settings.
- **Simulation benches** under [`fpga/verilog/nes/sim/`](fpga/verilog/nes/sim/): the core alone and the whole core
  driven as the firmware does (ROM transfer, saves, a save state and a load).

## Installing on a rev2

Needs a rev2 Game Bub running the rev2 custom firmware, which loads cores from the SD card. From the release
package, copy the folder `minuitfranck.NES` (with `nes_rev2.bit`, `core.json`, `files.json`, `settings.json`) into
`cores/` on the card. Games go in `roms/NES/` as `.nes` (iNES or NES 2.0); battery saves are written next to them
as `.sav`, save states as one `.ss` file per game.

In a game, Home opens the menu. Its settings page has Reset Core, PPU Reset (Famicom / NES behavior), Save State,
Load State, State Slot (1 to 4), Scaling (4:3, Fit, 1x, Stretch), Screen Filter (Off, Smooth), System (Auto from
the NES 2.0 header, NTSC, PAL, Dendy), Palette (Kitrinx, Smooth, Wavebeam, Sony CXA, PC-10), Overscan, Mask Left
Edge, Extra Sprites, Buttons (A / B or B / Y) and Sound Filter (Off, Soft, Softer, Speaker). Home + R saves a state
and Home + L loads it without opening the menu.

## Building

Vivado 2023.2, target `xc7a100tcsg324-1` (the rev2's FPGA). After cloning, fetch the HDMI submodule:

```
git submodule update --init
```

From `fpga/`:

```
./mill root.runMain platform.handheld.HandheldTop net.gamebub.core.nes.HandheldNes 2 --target-dir=build/nes-elab
```

elaborates the Chisel design; `fpga/scripts/build_core.py` builds the Vivado project from it and the Verilog under
`fpga/verilog/nes/`. The framework's own notes are in [`fpga/framework/README.md`](fpga/framework/README.md).

## Known limits

- No save states for cartridges with more than 32 KiB of CHR-RAM (the state holds 32 KiB).
- UNROM 512 (mapper 30) games run, but their self-flashing saves are not kept.
- One player; the second controller is not connected.
- PAL and Dendy games run at their 50 Hz, and the handheld's screen refreshes at that rate for them.
- The Famicom Disk System is not built; no BIOS file is used or included.

## Credits

- Ludvig Strigeus: FPGANES, the NES implementation this descends from.
- Sorgelig (Alexey Melnikov), Kitrinx (Jamie Blanks), greyrogue, Robert Peip and the MiSTer contributors: the MiSTer
  NES core, its mappers, the APU rewrite and the save states.
- Daniel Wallner, Mike Johnson, Wolfgang Scherr, Morten Leikvoll: T65, the 6502 core.
- The palettes come from MiSTer's video pipeline: Kitrinx's palette, FirebrandX's Smooth and Sony CXA2025AS,
  nakedarthur's Wavebeam, and the PlayChoice-10 palette as distributed with MiSTer.
- Eli Lipsitz: the Game Bub, its FPGA framework and firmware.
- Jeremy O'Brien (neutralinsomniac): the SRAM controller's registered I/O stage, the byte-port bridge, the SRAM
  host adapter and the pin constraints, from his SNES port for the Game Bub.
- minuitfranck (direction, hardware testing) with Claude, Anthropic's AI assistant (code, simulations, notes): the
  port, its fixes and these documents. See the AI disclosure above.

## License

GPL-3.0-only ([`LICENSE`](LICENSE)), the license of the core it is built from. Component licenses and copyright
notices: [`THIRD-PARTY.md`](THIRD-PARTY.md). The Game Bub framework files are under CERN-OHL-S-2.0
([`LICENSE-CERN-OHL-S`](LICENSE-CERN-OHL-S)); the T65 CPU keeps its BSD-style notice. No games and no BIOS files are
included.
