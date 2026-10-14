# Third-party notices

This core is a port of other people's work. Every component keeps its own copyright headers and license text in
the source tree; this page lists them in one place. The port as a whole is distributed under the GNU General Public
License, version 3 (`LICENSE`), the license of the core it is built from.

| Component | Where | Authors | License |
| --- | --- | --- | --- |
| MiSTer NES core (`NES_MiSTer` @ 26e2efb, 2026-09-16; `rtl/nes.sv`, `ppu.sv`, `apu.sv`, `cart.sv`, `mappers/`) | `fpga/verilog/nes/rtl/` | Ludvig Strigeus (FPGANES, 2012 to 2013); the MiSTer port, mappers and APU rewrite: Sorgelig (Alexey Melnikov), Kitrinx (Jamie Blanks), greyrogue and the MiSTer contributors | GPL-3.0 (`fpga/verilog/nes/COPYING`; "GPL Licensed" and "GPLv3" file headers) |
| Save-state machinery (`savestates.vhd`, `statemanager.vhd`, `bus_savestates.vhd`, `regs_savestates.sv`) | `fpga/verilog/nes/rtl/` | Robert Peip (FPGAzumSpass) and the NES_MiSTer contributors | GPL-3.0, as part of NES_MiSTer |
| T65, 6502 CPU | `fpga/verilog/nes/rtl/t65/` | Daniel Wallner, Mike Johnson, Wolfgang Scherr, Morten Leikvoll (2002 to 2015) | BSD-style (the file headers) |
| NES palettes (five 64-entry tables reproduced from MiSTer's `video.sv`) | `fpga/src/main/scala/net/gamebub/core/nes/NesVideoCapture.scala` | Kitrinx; FirebrandX (Smooth, Sony CXA2025AS); nakedarthur (Wavebeam); the PlayChoice-10 palette as distributed with MiSTer | distributed with NES_MiSTer under its GPL-3.0 |
| Game Bub FPGA framework and boot design | `fpga/framework/`, `fpga/src/main/scala/platform/`, `lib/`, `net/gamebub/core/boot/`, `gameboy/`, `gba/`, `fpga/verilog/handheld/`, `picorv32.v` | Eli Lipsitz and Game Bub contributors | CERN-OHL-S-2.0 (`LICENSE-CERN-OHL-S`); firmware-side code GPL-3.0 (`LICENSE-GPL-V3`) |
| Asynchronous SRAM controller with the registered I/O stage, the byte-port bridge and the SRAM host adapter, and the SRAM pin constraints | `fpga/src/main/scala/lib/mem/sram/AsyncSramController.scala`, `BytePortBridge.scala`, `SramHostAdapter.scala`, `fpga/verilog/handheld/core_nes.xdc` | Jeremy O'Brien (neutralinsomniac), from his SNES port for the Game Bub | CERN-OHL-S-2.0, as part of the framework |
| PicoRV32 (the boot design's soft CPU) | `fpga/verilog/picorv32.v` | Claire Xenia Wolf | ISC |
| HDMI transmitter (submodule `fpga/verilog/hdmi`, Eli Lipsitz's fork of hdl-util/hdmi) | `fpga/verilog/hdmi/` | Sameer Puri and contributors | MIT |
| Chisel, mill and their dependencies | build time only | The Chisel and mill projects | Apache-2.0 and others |

The Game Bub glue for this core (`fpga/src/main/scala/net/gamebub/core/nes/`, the vendoring tool and benches under
`fpga/verilog/nes/tools/` and `fpga/verilog/nes/sim/`, the notes under `docs/`) was written for this port by
minuitfranck with Claude (Anthropic) and is released under the same GPL-3.0.

"Game Bub" is the name of Eli Lipsitz's project; this port is unofficial and uses neither the name nor the logo as
its own. "Nintendo", "NES" and "Famicom" are trademarks of Nintendo; the core contains no Nintendo code, no BIOS and
no games.
