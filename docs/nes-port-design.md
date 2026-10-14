# NES / Famicom port design for Game Bub rev2 — 2026-09-16

Personal-use build (GPL-3.0 core, never distributed as a bitstream). Template:
the PC Engine port (`docs/pce-port-design.md`, branch `pce`) and Jeremy's SNES
glue (`core-snes`, `HandheldSnes.scala`). Progress and build results live in
`docs/nes-port-status.md`.

## 0. Go / no-go

GO. Each point was checked in the sources on 2026-09-16:

1. **Clock.** The MiSTer NES core is one synchronous design on the NTSC master
   clock, 21.477272 MHz (`NES.sv` PLL `outclk_2`; `rtl/nes.sv` derives CPU, PPU
   and APU enables from counters: `div_cpu` 1..12, `div_ppu` 1..4). The SNES
   port's clock tree already produces exactly this rate as its system clock
   (1288.636 MHz / 60 = 21.4773 MHz) with the SDRAM at 2x through
   `PipelineMemoryBurstCdc` and the constraints of `core_snes.xdc`, proven on
   rev2 hardware. Reused verbatim.
2. **No wait input, but a stoppable clock.** The core expects memory data a
   fixed number of master clocks after a request (MiSTer serves it from an
   85.9 MHz SDRAM controller, `rtl/sdram.sv`). Like the SNES port, the glue
   gates the whole core clock (BUFGCE) while a read it cannot answer in time is
   outstanding; everything in the core (CPU, PPU, APU, mappers) stops
   together, so a stall is invisible to the game.
3. **Vivado.** The vendored subset elaborates with `synth_design -rtl` after
   mechanical fixes (section 1). Internal tri-state buses and `wor`/`wand` do
   not synthesize correctly in Vivado (checked with small test designs), so the
   mapper bus is rebuilt as OR / AND reductions.
4. **Resources.** Estimated below (section 4): about 90 of 135 BRAM tiles and
   well under half the LUTs.

## 1. Vendored RTL (`fpga/verilog/nes/`)

MiSTer-devel/NES_MiSTer @ 26e2efb (2026-09-16). Kept: `rtl/nes.sv` (was
`nes.v`), `ppu.sv`, `apu.sv`, `cart.sv` (generated subset), `mappers/{MMC1,
MMC2, MMC3, generic}.sv`, `t65/*.vhd`, `savestates.vhd`, `statemanager.vhd`,
`bus_savestates.vhd`, `regs_savestates.sv`, plus `NES.sv` and `video.sv` as
`.reference` files (read-only documentation of the MiSTer top level). Not
kept: MiSTer framework, SDRAM/DDR3 controllers, video/composite pipeline,
cheats, zapper/keyboard/miracle piano, FDS, NSF, expansion audio, and the
mapper files not listed (MMC5, VRC, Namco, Sunsoft, Sachen, JY, misc, 99).

Mechanical changes (`tools/gamebub_vendor.py`, re-runnable against an
upstream checkout): mapper `_b` outputs drive 0 (or all ones for the `tri1`
data buses) instead of `Z` when disabled; `inout` -> `output`; `always begin`
-> `always @* begin`; explicit `import regs_savestates::*;`; `cart.sv` keeps
18 mapper instances (mappers 0, 1, 2, 3, 4, 7, 9, 10, 13, 28, 30, 34, 64, 66,
70, 71, 77, 78, 79, 81, 89, 93, 94, 97, 105, 107, 113, 118, 119, 133, 146,
148, 152, 155, 158, 165, 171, 180, 184, 185, 232 and the MMC3 family: 4, 33,
37, 45, 47, 48, 52, 74, 76, 80, 82, 88, 95, 112, 154, 189, 191, 192, 194, 195,
196, 205, 206, 207, 208, 268) with per-instance wires resolved after them. Every
file also goes through `tools/hoist_decls.py`, which moves each declaration
that upstream uses before declaring it to the top of its module: Vivado
synthesis tolerates the forward references (warnings 8-6901 / 8-8895), the
Vivado simulator does not (it made implicit nets and dropped the later
`wire x = <expr>;` assignments).

Hand changes (each marked `Game Bub:`; the README table lists them): `nes.sv`
imports the package, has no composite encoder and no cheat engine, gains two
outputs `gb_cpu_latch` / `gb_ppu_latch` (section 5), and its statemanager
gets base 0; `ppu.sv` declares five procedurally assigned nets as `logic`/`reg`
(Vivado rejects assignments to `wire`) and moves one declaration before its
use; `apu.sv` imports the package; `savestates.vhd` / `statemanager.vhd` get
the Game Bub state layout (section 10), and their `integer` ports bound
from Verilog become `std_logic_vector` (the simulator cannot bind them).

## 2. Clocks

As `HandheldSnes`: MMCM 50 MHz x 23.625 = 1181.25 MHz, /11 into a PLL x12 =
1288.636 MHz; PLL outputs /60 = 21.4773 MHz system clock (core, glue), /30 =
42.955 MHz SDRAM controller, /30 at 216 degrees (13.97 ns) forwarded to the
SDRAM pin; display /98 = 12.054 MHz and SPI /7 = 168.75 MHz from the MMCM.
`core_nes.xdc` = `core_snes.xdc` (same PLL output indices, same instance names
`pll`/`sdram`, the SDRAM CDC's multicycle constraints unchanged).

Core clock: `coreClock = BUFGCE(clock, coreRun)`, `tick = RegNext(coreRun)`:

    coreWant = (focus || hostReset || ssRunPending) && !stall
    coreRun  = coreWant && !palSkip

**PAL.** A PAL NES runs its master clock at 21.2813696 MHz. As the SNES port
does for PAL, the physical clock stays at the NTSC rate and a phase
accumulator withholds one edge in ~110 so the core sees the PAL rate on
average. The system type comes from the NES 2.0 header (byte 12 timing) or a
setting (Auto / NTSC / PAL / Dendy; Dendy keeps the NTSC clock like MiSTer).
Vs. System images run as NTSC consumer hardware (no Vs. palettes, coins, DIPs).

**Frame period.** NTSC: 262 lines x 341 dots x 4 master clocks = 357,368 (one
dot less on odd rendered frames): 16.639 ms, inside the ILI9488 driver's lock
window (the PCE's 16.72 ms locks on rev2 hardware).

## 3. ROM loading and header handling

`files.json` id 0 "ROM", extensions `nes`, address `0x30000000` (SDRAM window),
max_size `0x1000000` (16 MiB; the states file starts there), read_only. The host streams the whole
`.nes` file as 32-bit words.

**Header analysis while the file arrives** (`NesRomInfo`, a port of MiSTer's
`GameLoader` header logic, `NES.sv.reference` lines 1404-1540): the first
four words written at file offsets 0, 4, 8 and 12 are captured; from them the
module computes the same 64-bit `mapper_flags` as MiSTer (mapper incl. NES 2.0
plane bits, submapper, PRG/CHR size codes, CHR-RAM flag, mirroring, four-screen,
battery, PRG-RAM / NVRAM shifts, console type, timing), `prg_mask`,
`chr_mask`, the PRG and CHR sizes (iNES: 16 KiB / 8 KiB units; NES 2.0
exponent-multiplier form), the trainer flag and the "dirty iNES 1.0 header"
rule (upper mapper nibble ignored when bytes 9-15 hold garbage).
`FILE_WRITE_END 0` latches the result (register 0x0004 / 0x0008 / 0x000C) and
fails `SETUP_COMPLETE` for images the core cannot run: no `NES\x1A` magic, a
trainer (as MiSTer), CHR-ROM > 480 KiB, PRG > 15.9 MiB, a CHR start that is not
word aligned, or a mapper that is not built (the firmware then reports a core
error instead of showing a black screen).

**Where the bytes go.** PRG stays in the SDRAM at its file offset (byte 16).
CHR-ROM goes to the SRAM: the SDRAM host window is a splitter that writes each
word at file offset >= `16 + prgSize` to the SRAM (at `offset - 16 - prgSize`,
two 16-bit writes through `SramHostAdapter`) instead of the SDRAM. The header
is known long before the first CHR word (PRG >= 16 KiB), so no copy pass and
no SDRAM read-back are needed during setup, and the ROM transfer keeps its
proven path for PRG.

## 4. Memory map and budget

MiSTer addresses (`cart.sv`: PRG 0x000000-0x1FFFFF, CHR 0x200000-0x2FFFFF,
CPU-RAM 0x380000, CIRAM 0x3A0000, CARTRAM 0x3C0000, high cart RAM 0x3F8000)
are decoded by the glue into three memories:

| MiSTer range | Contents | Game Bub memory | Size | Access |
| --- | --- | --- | --- | --- |
| 0x000000-0x1FFFFF (CPU) | PRG-ROM | SDRAM, file offset 16 | up to 15.9 MiB | LineBuffer + LineReadCache, stall on miss |
| 0x380000-0x3807FF (CPU) | internal work RAM | BRAM (system clock) | 2 KiB | 1-cycle synchronous |
| 0x3C0000-0x3C7FFF (CPU) | PRG-RAM / battery RAM | BRAM (system clock, host port = `.sav`) | 32 KiB | 1-cycle synchronous |
| 0x200000-0x277FFF (PPU) | CHR-ROM or CHR-RAM | SRAM bytes 0x00000-0x77FFF | 480 KiB | AsyncSramController, ~2 cycles |
| 0x3F8000-0x3FFFFF (PPU) | UNROM 512 CHR-RAM, four-screen / TQROM / small CHR-RAM | SRAM bytes 0x78000-0x7FFFF | 32 KiB | same |
| 0x3A0000-0x3A07FF (PPU) | CIRAM (nametables) | BRAM (system clock) | 2 KiB | 1-cycle synchronous |
| other | (unused by the kept mappers) | reads 0xFF, writes dropped | | |

The latency-critical and correctness-critical reads never share a path with
other traffic while the game runs (the SNES lesson): CHR, nametables and RAM
are FPGA-internal or on the SRAM, which only the PPU uses at run time; the
SDRAM serves PRG alone at run time. The save-state slots are in the SDRAM
(section 10) but are only touched while the core is paused inside the MiSTer
pause, through the low-priority side of the SDRAM mux (it waits for the ROM
cache to be idle), the arrangement that the SNES port's save states use on
rev2 hardware. The ROM cache and line buffer are flushed at every ROM transfer
(a game switch), 255 cycles after FILE_WRITE_START so that a fill in flight
completes first; they are not flushed at run time (resetting them with an
SDRAM response in flight would be riskier than what it guards against), and
PRG reads are not forwarded at all while the core is held in reset, so no
line can be cached while the host still writes the SDRAM.

SDRAM (25-bit byte addresses): ROM file at 0; save-state slots at 0x1000000
(4 x 256 KiB). SRAM (18-bit 16-bit-word addresses): as the table.

**BRAM budget (RAMB36 equivalents, estimate):** framebuffers 2 x 256 x 240 x
15 bits = 60 (18-bit-wide primitives); ROM cache 2 ways x 1024 lines = 8;
PRG-RAM 32 KiB = 8; work RAM + CIRAM = 2; audio FIFO 1; framework (overlay
240 x 160 x 2, SPI FIFOs, display) about 8; total about 87 of 135. The core
keeps OAM, palette RAM and sprite buffers in registers / LUT RAM as upstream.
Fallbacks if needed: PRG-RAM 16 KiB (-4), 1-way ROM cache (-4).
Measured (first builds, 2026-09-16/17): 80.5 of 135 tiles (78 RAMB36 + 5 RAMB18),
29.6k LUTs (46.6 %), 6 DSPs.

## 5. Stall strategy (latency hiding)

The core's sample instants are exposed by the vendored core (`nes.sv`):

- `gb_cpu_latch` = `div_cpu` 10..12. A CPU bus cycle raises `cpumem_read` at
  `div_cpu` 6 (`cart_pre`); `cart_ce` (10) latches bus-conflict data for
  Mapper 3, the open/internal bus registers sample at every edge but
  `cpu_ce`, and `cpu_ce` (12) is where the T65, DMA and the APU's DMC take it.
- `gb_ppu_latch` = `ppu_ce`. A PPU VRAM read holds `vram_r` for one PPU cycle
  (4 master clocks) and latches `vram_pins` at the next `ppu_ce`.

Requests are detected in the system domain from the core's level outputs
exactly like the SNES `BytePortBridge` (`read && (!RegNext(read) ||
address changed)`): outputs of the gated domain only change at delivered edges,
so each request fires once.

    stall = (romPending && !respValid && gb_cpu_latch)  // SDRAM miss not yet answered
         || (chrPort.pendingRead && gb_ppu_latch)        // SRAM read not yet answered
         || chrPort.full                                 // SRAM write queue full
         || ssSramPort.pending                           // save-state engine on SRAM

(The slot port needs no stall: the save-state engine waits for `done` in a
state of its own.)

A LineBuffer hit answers in the request cycle, a cache hit one cycle later;
with 4 master clocks between request and first latch only misses stall. The
SRAM answers a read in 2 cycles (request cycle + access; `done` in the third)
when idle, before the PPU latch 4 edges later, so CHR normally never stalls;
a read queued behind a CHR-RAM write simply stalls one or two cycles. BRAM
reads never stall. Writes: PRG-RAM, work RAM and CIRAM are written in place
(level-sensitive, idempotent while the core is stopped); CHR-RAM writes go
through a small posted-write queue on the SRAM (reads queued behind them see
the new data). Writes to PRG-ROM (UNROM 512 self-flashing) are dropped
(section 13).

`cpumem_din` is the byte of the last completed PRG read (as MiSTer's `ch1_dout`
holds it) or the BRAM output for RAM addresses; `ppumem_din` likewise for the
SRAM and the CIRAM. The selects decode the current address (it is stable from
the request to the latch edge).

## 6. Video capture (256 x 240)

The PPU's `color` (6 bits), `emphasis` (3 bits), `cycle` and `scanline` feed
`NesVideoCapture` in the core domain, which reproduces the MiSTer pipeline of
`video.sv` ("full" overscan mode: 240 lines x 256 dots): pixels are taken at
`pix_ce` (`nes_div == 0` registered, as `video.sv`), visible when the delayed
`cycle` is 1..256 and `scanline` 0..239, i.e. pixel x = color at `cycle` x + 2
(the PPU output lags its counter by two dots). Colour = the selected palette
(Kitrinx 34 default; Smooth, Wavebeam, Sony CXA, PC-10 as settings; tables from
`video.sv`) with MiSTer's emphasis attenuation, reduced to RGB 5:5:5 (the
framebuffer depth the firmware's screenshot code already handles for the
SNES). hblank rises after each 256-pixel row and vblank covers scanlines
240..pre-render, so the framework's row/frame counters restart exactly. Pulses
are qualified with `tick`. While the core is paused for a save state
(`corepaused`) nothing is written: the LCD keeps the last frame.

Settings: Overscan (show all 240 lines / hide 8 lines top and bottom, drawn
black), Mask Edges (off / left 8 px, the PPU's own `mask`), Extra Sprites
(`ex_sprites`), Palette. The LCD scaling register 0xF1001018 (Scaling) as for
the SNES/PCE.

Screen Filter: Smooth (r2.6). The framework's LCD screen filter register
0xF100101C (GB-MiSTer's Off / LCD Grid / Scanlines, plus 3 = Smooth); the NES
menu offers Off and Smooth only (the grid and scanlines need a whole-number
scale of 2 or more, and the NES is at 1x there). Smooth is sharp bilinear in
4:3, Fit and Stretch: every NES pixel stays crisp and only the one screen
pixel on each NES-pixel boundary blends the two neighbors (weights in
eighths), so the uneven 1/2-pixel widths of nearest-neighbor disappear. 1x is
unchanged; HDMI is unchanged. See `HandheldSmoothScaler` in the framework
(`fpga/src/main/scala/platform/handheld/`): far-edge reads, a one-clock
delay along the LCD's scan and a line buffer (one RAMB18) across it.

## 7. Audio

`sample` (16-bit offset binary: APU mix through the mapper's audio
pass-through) is converted to signed (`^ 0x8000`), low-passed and decimated
by a 3-stage CIC (ratio 512 delivered core clocks: ~41.95 kHz) in the system
domain qualified by `tick`, and handed to `AudioRateAdapter`
(nominal period 512 system clocks, FIFO 256) like the SNES, which smooths the
jitter from stalls and PAL pacing. Muted without focus.

Sound Filter (r2.10, `NesAudio.scala`): between the CIC and the rate adapter,
a selectable low-pass on the decimated samples: Off (the CIC's samples as
before, 26 clocks later), Soft 10 kHz (default), Softer 6 kHz, Speaker
3.5 kHz, each a 2nd-order Butterworth (bilinear, pre-warped, both zeros at
z = -1) at 41.95 kHz. The NES's own output has a 14 kHz low-pass; without
one the APU's square edges, triangle steps and noise reached the speaker
unsoftened (heard as harsh in Kirby's Adventure on the unit, 2026-09-26).
`NesAudioFilter` is core-ngpc's NgpcAudioFilter (r2.4, checked on the unit's
speaker and headphones) line for line: all three
filters run all the time on one time-multiplexed DSP48E1, so every setting's
output is settled, and a change cross-fades over 256 samples (6.1 ms). The
NES is mono: both channels carry the same sample, and the NGPC's Sound Level
input is tied off. PAL's `palPace` gives 0.9 % fewer samples a second, which
lowers the cut-offs by as much. The CIC itself droops 2.5 dB at 10 kHz; that
was always there and is unchanged.

## 8. Joypad

`NesJoypad` in the core domain replicates `NES.sv`'s serial latch: while
`joypad_out[0]` (strobe) is high it loads `{16'hFFFF, R, L, D, U, Start,
Select, B, A}`, and each falling edge of `joypad_clock[0]` shifts; bit 0 is
`joypad1_data[0]`. Port 2 reads eight zeros then ones (no controller), as
MiSTer. Buttons pass through `ButtonFilter`. Mapping setting: A/B (default) or
B/Y on the Game Bub's face buttons (Y/X then act as B/A). No turbo, zapper,
mic or Famicom expansion devices.

## 9. Battery RAM (.sav)

`files.json` id 1 "Save", `sav`, address `0x40000000`, max_size `0x8000`,
optional, dependent_on_0, initialize. The window maps the 32 KiB PRG-RAM BRAM
(32-bit words; `BackupRam`-style dual port). `FILE_READ_START 1` answers the
size to write back: 0 when the header has no battery flag; 8 KiB when it is a
NES 2.0 image with NVRAM shift 7 (8 KiB); 32 KiB otherwise (MiSTer writes 32
KiB for iNES 1.0 battery images, `NES.sv.reference` save_sz), so `.sav` files
move between MiSTer and Game Bub unchanged. A missing `.sav` is filled with
0xFF by the firmware, like a fresh battery RAM on MiSTer without "RAM clear".

## 10. Save states

MiSTer's machinery is used as is (`savestates.vhd`, `statemanager.vhd`, the
per-module `eReg_SavestateV` registers): on a request the core pauses itself
at the next vblank at an instruction boundary (`pausecore <= sleep_savestate`),
then `savestates` streams 64 internal register words and the memories through
two ports, and resumes. Game Bub layout (vendored table, section 1):

| Type | MiSTer address | Size | Game Bub memory |
| --- | --- | --- | --- |
| OAM | internal | 256 | PPU |
| mapper RAM | internal | 8 KiB | (zeros for the kept mappers) |
| CHR | 0x200000 | 32 KiB | SRAM (written back only for CHR-RAM images) |
| CHR-VRAM | 0x3A0000 | 2 KiB | BRAM |
| CPU-RAM | 0x380000 | 2 KiB | BRAM |
| CARTRAM | 0x3C0000 | 32 KiB | BRAM |
| high cart RAM | 0x3F8000 | 32 KiB | SRAM |

A state is 111,368 bytes; `STATESIZE` (the header's size field and validity
magic) is 0x7000 DWORDs. The RAM port (`Savestate_SDRAM*`, one byte per 8
clocks) is decoded like the CPU/PPU buses. The slot port (`SAVE_out_*`: a
one-cycle `ena`, a one-cycle `done`, 64-bit words at DWORD addresses) goes
through `NesSaveStatePort` (after the SNES `SaveStateMemoryPort`: two 32-bit
SDRAM accesses per word on the SDRAM mux's side port; the engine waits in a
state of its own until `done`, delivered for exactly one core edge) to
SDRAM 0x1000000 + DWORD address x 4. Slot n starts at 0x1000000 + n x 256 KiB.

`files.json` id 2 "States", `ss`, address `0x31000000`, max_size `0x100000`.
Registers and status match the SNES layout, so the firmware's settings.json
`status` / `visible_if` conventions work unchanged:

- 0x0014 control: bit 0 save, bit 1 load, slot bits 3:2 or (bit 4) register 0x0018; 0x11 / 0x12 from settings.
- 0x0018 slot (bits 1:0).
- 0x0100 status: bit 6 SS_AVAIL (ROM running with a mapper that supports states), bit 7 SS_BUSY (savestate engine busy, stretched to at least 100 ms so the 25 ms poll sees it), bit 8 save done (cleared by a save request, set when the slot header is written), bit 9 request not yet taken by the core, bit 10 request in progress (core runs without focus), bits 14:11 slots holding a state.

Slot validity is scanned (`NesSaveSlotScanner`: header bytes 4..7 == 0x7000)
at `FILE_WRITE_END 2` (slots beyond the transferred size get their header
cleared) and after every save; a load of an empty slot is refused;
`FILE_READ_START 2` answers (last valid slot + 1) x 256 KiB. The request runs
the core without focus until the engine is idle (5 s timeout). The load path
also restores CHR-RAM and the high cart RAM on the SRAM, gated off for CHR-ROM
images.

## 11. Host protocol, reset

Command FSM as the PCE/SNES glue: `FILE_WRITE_START 0` holds the core in reset
(cold: T65 `pwr_n`, APU cold reset), clears the ROM analysis, flushes the ROM
cache (after 255 cycles) and invalidates all save slots; `FILE_WRITE_END 0` latches the analysis;
`FILE_WRITE_END 1` records the loaded save size; `FILE_WRITE_END 2` starts the
slot scan (busy until done); `SETUP_COMPLETE` fails for unsupported images,
otherwise sets setup; `GET_STATUS` reports setup until the scanner is idle;
`CoreRun` / `CoreHalt` release / hold reset; `NotifyFocus` gates the clock;
`FILE_READ_START 1/2` answer the sizes above. Warm reset: register 0x2000 bit 0
(settings "Reset Core"), 255 delivered core clocks of `reset_nes`.

## 12. Registers (0x0xxx_xxxx) and settings

- 0x0000 config (rw): bits 1:0 system type (0 auto, 1 NTSC, 2 PAL, 3 Dendy);
  bits 5:2 palette; bit 6 hide overscan; bit 7 mask left edge; bit 8 extra
  sprites; bit 9 buttons B/Y; bits 10/11 ROM early answers / eager issue
  (default on); bit 12 PPU reset behaviour (0 Famicom, 1 NES); bits 14:13
  Sound Filter (0 Off, 1 Soft, 2 Softer, 3 Speaker; r2.10). Reset value
  0x2C00.
- 0x0004 (r) mapper flags low word, 0x0008 (r) high word, 0x000C (r) ROM info
  (PRG size KiB, CHR size KiB, supported, header valid, CHR-RAM, battery).
- 0x0010 (r) ROM file size; 0x0014 / 0x0018 / 0x0100 save states (above);
  0x1000.. statistics (write clears); 0x2000 (w) warm reset.
- settings.json: Reset Core, Save State, Load State, State Slot, Scaling,
  Screen Filter (Off / Smooth, r2.6), Sound Filter (Off / Soft / Softer /
  Speaker, default Soft, r2.10), System, Palette, Overscan, Mask Left Edge,
  Extra Sprites, Buttons, PPU Reset.

## 13. Mapper 30 (UNROM 512) battery flag

On UNROM 512 the battery flag means self-flashing PRG saves (the game writes
its own PRG flash); MiSTer then saves the whole PRG image as the `.sav`.
**Not supported in this port:** writes to PRG-ROM are dropped (reads keep the
original bytes), so such games run but their flash saves do not persist and
do not even survive within a session. Reasons: the ROM path is a read-only
cache in front of the SDRAM (a write needs a cache flush per programmed byte),
and loading a full-PRG `.sav` through a fixed `files.json` address safely
needs a staging area plus validation, because the firmware fills a missing
optional file with 0xFF and would otherwise overwrite the game's code. The
`.sav` size answered for these images is 0, so no misleading file is written.
A later milestone can add a staging window and PRG write-through.

## 14. Verification

- Treadle specs (through `PceTreadle`): `NesRomInfoSpec` (the four staged
  images' headers and edge cases), `NesJoypadSpec`, `NesAudioSpec` (Sound
  Filter: core-ngpc's filter cases with the NES coefficients against a
  bit-exact model and the designed biquads, plus the Off path and the output
  timing).
- xsim (`fpga/verilog/nes/sim`): `tb_nes.sv` runs the vendored core with
  ideal memories and dumps frames; `+garbage1` drives 0xA5 on both memory
  buses at every edge except the `gb_*_latch` ones, which checks the stall
  assumption of section 5. `system/tb_system.sv` runs the generated
  HandheldNes (glue included) against an async SRAM model and a behavioral
  SDRAM path, driving the host protocol like the firmware, and can exercise
  a save and a load. Results in `docs/nes-port-status.md`.
- Vivado build (timing met, UserID B0100002), package. Hardware tests are the
  owner's (checklist in the package README).
