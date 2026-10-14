# NES port status (resume here)

Worktree `gamebub-rev2/core-nes`, branch `nes` (from `pce` @ b536256). Design:
`docs/nes-port-design.md`. Upstream: MiSTer-devel/NES_MiSTer @ 26e2efb, clone in
the session scratchpad `refs/NES_MiSTer`.

State on 2026-09-17 04:20: package **0.0.1+rev2.2** staged at
`gamebub-rev2/build/sdcard/cores/minuitfranck.NES` (not on the card), bitstream
sha256 `aa1972b8921dc469c2423e1d1ec5e7a7ce5f3807b6f80eca6c8390d4d607ffee`,
timing met. Not yet run on hardware; the morning test checklist is in the
package's `README-rev2.txt` (Balloon Fight first).

## Done

- Vendored RTL subset in `fpga/verilog/nes/` (README documents every change;
  `tools/gamebub_vendor.py` regenerates the mechanical part, `tools/hoist_decls.py`
  moves forward-referenced declarations). `synth_design -rtl -top NES`
  elaborates without use-before-declaration warnings; strict `xvlog` clean.
- Chisel glue `fpga/src/main/scala/net/gamebub/core/nes/`: HandheldNes,
  NesCore (ExtModule `NES`), NesRomInfo, NesMemories, NesJoypad,
  NesVideoCapture (MiSTer palettes), NesSaveState (slot port + scanner).
- `build_core.py` entry `net.gamebub.core.nes.HandheldNes`, `core_nes.xdc`
  (= core_snes.xdc), `fpga/cores/nes/{core,files,settings}.json`.
- Tools: `~/tools/gamebub-build/fpga-build.sh nes` builds, `stage-rebuilt-core.py`
  packages (the build record goes into the package README).
- Specs (treadle2 via PceTreadle): NesRomInfoSpec, NesJoypadSpec pass
  (`./mill root.test.testOnly net.gamebub.core.nes.NesRomInfoSpec net.gamebub.core.nes.NesJoypadSpec`).
- Simulation (below), build, package.

## Build results

| When | Commit | WNS / WHS | LUT | BRAM | Notes |
| --- | --- | --- | --- | --- | --- |
| 2026-09-16 23:58 | 133f57e | +0.454 / +0.011 | 29,563 (46.6%) | 80.5 / 135 | first build; core clock slack +12.6 ns, SDRAM pin paths +5.1 ns |
| 2026-09-17 00:08 | 0cf6e2f | +0.473 / +0.024 | 29,558 (46.6%) | 80.5 / 135 | packaged as 0.0.1+rev2.1 (sha af2fcc38...) |
| 2026-09-17 00:53 | 846c785 (= e5d346e + docs) | +0.553 / +0.033 | 29,558 (46.6%), 14,643 FF | 80.5 / 135, 6 DSP | hoisted RTL; UserID B0100002; **packaged as 0.0.1+rev2.2** (sha aa1972b8...) |

The hoisting (e5d346e) was needed by the simulator only: Vivado synthesis
connected the forward-referenced nets before too (`synth_design -rtl` of the
pre-fix sources shows them driven; identical utilization), so rev2.1 was most
likely the same logic.

## Simulation results (xsim; run dirs in the session scratchpad `xsim-h-*`, pictures in `gamebub-rev2/build/nes/sim-frames`)

- Core only (`sim/tb_nes.sv`), `+garbage1` = memory data valid only at
  gb_cpu_latch / gb_ppu_latch, i.e. what the stall control relies on:
  Balloon Fight title and Phase 1 after Start, frames identical to
  `+garbage0`; Kirby's Adventure (MMC3) intro and title with sprites
  (frame 380); A Journey Into The Unknown (mapper 30, CHR-RAM) logo and story
  text after Start; Light From Within logos. PASS.
- Whole core (`sim/system/tb_system.sv`: generated HandheldNes, async SRAM
  model, behavioral SDRAM path, host protocol as the firmware drives it),
  Balloon Fight, whole file through the CHR splitter: ROM info / flags / sizes
  right, title at frame 18 pixel-identical to the core-only capture, Phase 1
  after Start; 12.1M core clocks, 1,154 stalled (PRG cache misses only), no
  CHR stalls; FILE_READ_START 1/2 -> 0. PASS.
- Save states, whole core, Balloon Fight (`NES_RTL=<xinit.py copy>`
  `+clearx1 +savestate20 +load_after12 +press_start22`): status 0x41 before,
  0x1151 after the save (slot 2 valid, save done, not busy); Start -> Phase 1;
  load accepted (0x1541) and done (0x1151); the frame after the load is
  byte-identical to the saved title frame. FILE_READ_START 2 -> 524288. PASS.
- `.sav` window, whole core, Kirby: 8 KiB pattern read back unchanged,
  FILE_READ_START 1 = 8192. PASS.
- Not usable: Kirby at the whole-core level. xsim starts the vendored core
  with X state (the FPGA with 0); in Kirby an X read / address reaches the
  CHR bridge (the PPU latches the low address byte from its data bus), and
  from there the stall logic and the gated clock. `+clearx2` / `+xrun1` get
  the run going but its CHR path stays X (white screens where the core-only
  run shows the intro text). Not a hardware finding.
- Simulator X issues handled along the way (none is a hardware issue): nets
  used before declaration (hoist_decls.py); nes.sv `cpu_tick_count` has no
  reset, which kept the save-state pause condition X (xinit.py copy); reads
  issued from X during the cold reset (+clearx1 / +clearx2).

## Known risks / not covered

- Hardware untested. Whole-core sim covers mapper 0 only; MMC3 / mapper 30
  are covered by the core-only runs.
- Save states share the SDRAM path with the PRG cache (the SNES
  arrangement); PAL pacing, warm reset, game switching, audio level and the
  stall rate of big games are unsimulated.
- UNROM 512 flash saves unsupported (design doc section 13).

## Next

1. Hardware test by the owner (package README checklist).
2. If Kirby misbehaves on hardware: statistics registers 0x1000-0x1024
   (stalls by cause), then the whole-core sim with an X-free core start
   (e.g. a funcsim netlist of the NES module).
