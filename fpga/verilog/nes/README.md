# NES / Famicom core (vendored from MiSTer)

The NES emulation core of
[MiSTer-devel/NES_MiSTer](https://github.com/MiSTer-devel/NES_MiSTer)
(Ludvig Strigeus's FPGA NES, extended by Sorgelig, Kitrinx, greyrogue, Robert
Peip and others), vendored at commit
`26e2efb1688c4cd8e7d200a7e4800308cd2cd230` (2026-09-16; see `VENDORED.txt`).

**License: GPL-3.0** (`COPYING`, upstream's license file; the files carry
"GPL Licensed" / "GPLv3" headers). The bitstreams built from it are for the
owner's personal use and are not distributed. `UPSTREAM-README.md` is
upstream's README.

Only the emulation core is here: the 6502 (`rtl/t65`), PPU (`rtl/ppu.sv`), APU
(`rtl/apu.sv`), the machine (`rtl/nes.sv`), the mapper multiplexer
(`rtl/cart.sv`, a generated subset) with the mapper files it uses
(`rtl/mappers`), and MiSTer's save-state machinery (`rtl/savestates.vhd`,
`statemanager.vhd`, `bus_savestates.vhd`, `regs_savestates.sv`). Not vendored:
the MiSTer framework (`sys/`), the MiSTer top level (`NES.sv`, kept as
`NES.sv.reference` for reading) and its video pipeline (`video.sv`, kept as
`video.sv.reference`: the glue's `NesVideoCapture` reproduces its timing and
palettes), the SDRAM / DDR3 controllers, composite video, cheats, FDS, NSF,
expansion audio chips, peripherals (zapper, keyboard, Miracle piano) and the
mapper files MMC5, VRC, Namco, Sunsoft, Sachen, JYCompany, misc and Mapper99.
Their roles are played by the Chisel glue in
`fpga/src/main/scala/net/gamebub/core/nes/` (design:
`docs/nes-port-design.md`).

## Local modifications

Two kinds, both needed for Vivado 2023.2 (Quartus accepts the upstream code):

**Generated / mechanical** (`tools/gamebub_vendor.py --upstream <checkout>`,
re-run it when updating; the files start with a `Game Bub: rewritten by`
line):

| File | Change |
| --- | --- |
| `rtl/mappers/MMC1.sv`, `MMC2.sv`, `MMC3.sv`, `generic.sv` | Every `assign <port>_b = enable ? <value> : <N>'hZ;` drives a constant instead of `Z` when the mapper is disabled: all ones for `prg_dout_b` / `chr_dout_b` (upstream `tri1` buses), zero for the others (`tri0`). Those ports are `output` instead of `inout`. `always begin` (no event control) is `always @* begin`. `import regs_savestates::*;` at the top. |
| all of `rtl/*.sv`, `rtl/mappers/*.sv` | `tools/hoist_decls.py`: every module-level declaration that upstream uses before declaring it is moved right after the port list (`wire x = <expr>;` becomes `wire x;` there and `assign x = <expr>;` in place; a body `localparam` a moved dimension needs moves too), marked `// Game Bub: hoisted`. Upstream relies on Quartus looking ahead. Vivado synthesis only warns ("used before its declaration", "already implicitly declared") and connects the nets correctly (checked on the pre-change sources: `synth_design -rtl` shows e.g. `ppu/vram_r_ppudata`, `ppu/vram_din`, `ppu/color0` driven, and the full build's utilization is identical before and after), but the Vivado simulator does not: where the first use is a port connection, xelab makes an implicit net and the later `wire x = <expr>;` loses its assignment (the PPU's `vram_r_ppudata` stayed z). gamebub_vendor.py applies it to the files it generates; it was applied by hand to `nes.sv`, `ppu.sv`, `apu.sv`. With it, `xvlog -sv` accepts the sources in strict mode, xsim runs them, and Vivado reports neither warning. |
| `rtl/cart.sv` | Generated from upstream `cart.sv`: only the mapper instances whose modules are vendored are kept (MMC1, Mapper28, Mapper30, MMC2, MMC3, MMC4, Mapper13, 34, 66, 71, 77, 78, 79, 89, 107, 165, Rambo1, NesEvent: mappers 0, 1, 2, 3, 4, 7, 9-11, 13, 28, 30, 33, 34, 37, 38, 45-48, 52, 64, 66, 70, 71, 74, 76-82, 86-89, 93-95, 97, 101, 105, 107, 112, 113, 118, 119, 133, 140, 144-146, 148, 149, 152, 154, 155, 158, 165, 171, 180, 184, 185, 189, 191, 192, 194-196, 205-208, 232, 268). Each instance's connections to the shared `tri0` / `tri1` buses go to private wires, resolved by OR (`tri0`) or AND (`tri1`) after the instances, which equals upstream's tri-state resolution with at most one mapper enabled (Vivado neither resolves internal tri-states nor `wor` / `wand` correctly; checked with small synth_design tests). The save-state wired-OR is re-indexed for the kept instances; mapper-internal RAM state reads 0; Mapper 413 extra ROM, FDS disk side and Mapper 16 EEPROM signals are constant 0. `wire [1023:0] me` is `reg` (assigned in an always block). Everything else is upstream's text. |

**By hand** (marked `Game Bub:` in the files):

| File | Change |
| --- | --- |
| `rtl/nes.v` -> `rtl/nes.sv` | Renamed and compiled as SystemVerilog so that it can `import regs_savestates::*;` (upstream relies on Quartus leaking `ppu.sv`'s file-scope import into later files). |
| `rtl/nes.sv` | The composite encoder (`composite_board`, not vendored) is gone: `composite_a` / `composite_b` are 0. |
| `rtl/nes.sv` | The cheat engine (`CODES`, not vendored) is gone: `genie_ovr` = 0, `gg_avail` = 0 (upstream's data path with cheats off). |
| `rtl/nes.sv` | New outputs `gb_cpu_latch` (`div_cpu` 10..12) and `gb_ppu_latch` (`ppu_ce`): the edges at which the core samples external memory data, for the glue's stall control. |
| `rtl/nes.sv` | `statemanager #(0, 0)` (upstream `#(58720256, 33554432)`): slots at DWORD 0 of the glue's slot port, no rewind. |
| `rtl/statemanager.vhd` | `SAVESTATESIZE` 16#10000# DWORDs = 256 KiB per slot (upstream 16#80000#, 2 MiB). |
| `rtl/savestates.vhd` | Game Bub state layout: `STATESIZE` 16#7000# (the header size field and validity magic; upstream 331776); `SAVETYPESCOUNT` 7; CHR 32 KiB (upstream 1 MiB), CARTRAM 32 KiB (upstream 256 KiB) and a new 32 KiB entry at 0x3F8000 (the high cart RAM window, upstream inside CARTRAM). |
| `rtl/savestates.vhd`, `rtl/statemanager.vhd` | The `integer` ports bound from Verilog (`savestate_address`, `savestate_number`, `request_address`) are `std_logic_vector` (32 / 2 / 32 bits) with the conversions inside: the Vivado simulator cannot bind Verilog nets to VHDL `integer` ports (synthesis could). |
| `rtl/ppu.sv` | `vblank_start_sl`, `vblank_end_sl`, `vsync_start_sl`, `skip_en` (ClockGen), `load_temp` (SpriteAddressGenEx, also declared before its first use), `vram_a` (PPU) declared `logic`; `new_color` (debug_dots) and `vram_r_ex` (PPU) ports `output logic`; `Savestate_OAMReadData` (OAMEval) `output reg`: Vivado rejects procedural assignments to nets. |
| `rtl/apu.sv` | `import regs_savestates::*;` at the top. |

Before hoist_decls.py (builds 133f57e and 0cf6e2f, package 0.0.1+rev2.1) Vivado
warned about identifiers used before their declaration; synthesis resolved them
correctly, only simulation was affected (see the hoist_decls.py row).

## Checking the sources without a full build

Non-project Vivado Tcl (as used on 2026-09-16):

```
read_vhdl -library work [list rtl/bus_savestates.vhd rtl/t65/T65_Pack.vhd rtl/t65/T65_ALU.vhd rtl/t65/T65_MCode.vhd rtl/t65/T65.vhd rtl/savestates.vhd rtl/statemanager.vhd]
read_verilog -sv [list rtl/regs_savestates.sv rtl/apu.sv rtl/ppu.sv rtl/mappers/MMC1.sv rtl/mappers/MMC2.sv rtl/mappers/MMC3.sv rtl/mappers/generic.sv rtl/cart.sv rtl/nes.sv]
synth_design -rtl -top NES -part xc7a100tcsg324-1
```
