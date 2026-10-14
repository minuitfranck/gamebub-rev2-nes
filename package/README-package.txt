NES / Famicom core (minuitfranck.NES) built for Game Bub rev2 (vertical) - package version 0.0.1+rev2.2 (2026-09-17 03:33)
Source: MiSTer-devel/NES_MiSTer @ 26e2efb (GPL-3.0; subset vendored in gamebub-rev2\core-nes\fpga\verilog\nes, changes in its README.md)
        + Game Bub glue in gamebub-rev2\core-nes (branch nes @ 34401ee, bitstream GitRev 846c78531733). Port by minuitfranck with Claude.
        Public build: github.com/minuitfranck/gamebub-rev2-nes. Design: core-nes\docs\nes-port-design.md; status: docs\nes-port-status.md.
Build:  Vivado 2023.2, xc7a100tcsg324-1, target gamebub_rev2, Performance_Explore;
        INFO: [Route 35-20] Post Routing Timing Summary | WNS=0.553  | TNS=0.000  | WHS=0.033  | THS=0.000  |
        | Slice LUTs                 | 29558 |     0 |          0 |     63400 | 46.62 |
        | Block RAM Tile    | 80.5 |     0 |          0 |       135 | 59.63 |
        | DSPs           |    6 |     0 |          0 |       240 |  2.50 |
        nes_rev2.bit sha256 aa1972b8921dc469c2423e1d1ec5e7a7ce5f3807b6f80eca6c8390d4d607ffee
Needs firmware v1.1.0-beta1 local7 or later for the save-state pop-ups (older firmware lists every entry, no pop-ups).

Supersedes 0.0.1+rev2.1: same logic (identical utilization), rebuilt from sources that also simulate correctly.
Verified in simulation (Vivado xsim), not yet on hardware (pictures in build\nes\sim-frames):
  - Vendored core alone, with memory data withheld except at the clock edges where the core samples it (what the
    stall control relies on): Balloon Fight title, then Phase 1 after Start (identical to a run with ideal memories);
    Kirby's Adventure (MMC3) intro and title screen (frame 380); A Journey Into The Unknown (UNROM 512, CHR-RAM)
    logo and story text after Start; Light From Within demo logos.
  - Whole core (glue + core, SRAM model, SDRAM path model) driven like the firmware, Balloon Fight: ROM transfer with
    the CHR splitter, header analysis, setup, title (pixel-identical to the core-only picture), Phase 1 after Start;
    0.01 % of core clocks stalled (PRG cache misses), no CHR stalls.
  - Save states, same setup: Save State (slot 2) on the title -> "saved" status, slot 2 marked valid; Start, Phase 1;
    Load State -> back to the title, pixel-identical to the saved frame. States file size answered: 512 KiB.
  - .sav window (Kirby): 8 KiB written and read back unchanged; size answered 8192.
  Not simulated at the whole-core level: MMC3 / mapper 30 games (the simulator's unknown power-up state gets in the
  way there; the core-only runs above cover those mappers), PAL, warm reset, game switching.

ROMs: .nes (iNES or NES 2.0) anywhere under roms\. Mappers built: 0 (NROM), 1 (MMC1), 2 (UxROM), 3 (CNROM), 4 (MMC3 family),
      7 (AxROM), 9/10 (MMC2/MMC4), 13, 28, 30 (UNROM 512), 34, 64, 66, 70, 71, 77-81, 89, 93-97, 105, 107, 113, 118, 119, 152,
      155, 158, 165, 171, 180, 184, 185, 232 and more MMC3/GxROM relatives. Not built: MMC5, VRC2/4/6/7, Namco 163, Sunsoft FME-7,
      FDS, NSF, Vs. System. An image the core cannot run (unbuilt mapper, trainer, CHR-ROM over 480 KiB, bad header) makes
      the core report an error instead of starting.
Saves: <game>.sav next to the ROM (battery games: 8 KiB for NES 2.0 images with 8 KiB NVRAM, else 32 KiB like MiSTer), written
      on Exit Core. UNROM 512 (mapper 30) "battery" = self-flashing PRG saves: NOT supported (the game runs, its saves are lost).
Save states (experimental, first build): Save State / Load State / State Slot in the core settings; <game>.ss (256 KiB per used
      slot) written on Exit Core. The core pauses itself at the next vblank to save or load (about 0.1 s).
Settings: Reset Core, Scaling (default 4:3), System (Auto from the NES 2.0 header / NTSC / PAL / Dendy), Palette (Kitrinx,
      Smooth, Wavebeam, Sony CXA, PC-10), Overscan (show all 240 lines / hide 8 top+bottom), Mask Left Edge, Extra Sprites
      (no 8-per-line limit, may glitch some games), Buttons (A/B or B/Y).
Controls: d-pad, A, B (or B/Y), Start, Select. X, L, R unused. Player 2 is not connected.

MORNING TEST CHECKLIST (in this order; note what you see for each):
 1. Balloon Fight (USA) - mapper 0, the simplest image. Cores > NES / Famicom > pick it.
    Expect: title screen within ~2 s, music, Start begins a game, flapping with A works, colours look right.
    If the core errors: note the message. If black screen with sound: video capture. If nothing at all: ROM path.
    Check the picture edges: no garbage column at the left/right (a 1-dot shift would show as a thin line).
 2. Kirby's Adventure (USA) (Rev 1) - MMC3, 512 KiB PRG + 256 KiB CHR, battery.
    Expect: intro, file select, play a level; status bar stable (MMC3 IRQ). Save in-game (the game auto-saves at doors),
    Exit Core, relaunch: the file shows progress. Kirby's Adventure.sav should be 8,192 bytes.
 3. Light From Within Demo (Rev-A) - UNROM 512 with CHR-RAM, vertical mirroring. Expect the demo to run with graphics.
 4. A Journey Into The Unknown - same mapper; play a bit. (Its flash saves are not kept.)
 5. Save states on Kirby: in a level, Home > core settings > Save State (pop-up "State saved"), move on, Load State
    (pop-up "State loaded", back to the saved spot). Try slot 2. Exit Core: Kirby's Adventure.ss (256 or 512 KiB).
    Relaunch and Load State again.
 6. Settings: Scaling modes, Palette, Overscan hide, Reset Core, Buttons B/Y.
 7. Switch games without leaving the core menu (load Balloon Fight after Kirby): the second game must start clean.
Useful to report: the SNES-style debug register dump 0x0100 (status) and 0x1000-0x1024 (stall statistics) if something hangs.

TEST LOG (hardware): not yet run.


SETTINGS UPDATE 2026-09-18: package 0.0.1+rev2.3 (settings.json only; the bitstream is unchanged, nes_rev2.bit sha256 aa1972b8921dc469c2423e1d1ec5e7a7ce5f3807b6f80eca6c8390d4d607ffee).
New menu entry: PPU Reset (Famicom / NES), the behavior of Reset Core; config bit 12, already in the bitstream.


SETTINGS UPDATE 2026-09-18 (for firmware local13): package 0.0.1+rev2.3 (descriptor files only; the bitstream is unchanged, nes_rev2.bit sha256 aa1972b8921dc469c2423e1d1ec5e7a7ce5f3807b6f80eca6c8390d4d607ffee).
Save State and Load State are tagged with "hotkey": with firmware local13 or later, hold Home and press R to save or L to load the current slot during play, with the usual pop-up.


REBUILD 2026-09-18 23:44: package 0.0.1+rev2.4 (was 0.0.1+rev2.3); core-nes branch nes @ b7945bab
Build:  Vivado 2023.2, xc7a100tcsg324-1, target gamebub_rev2; timing met, WNS 0.588 ns, WHS 0.020 ns
        Slice LUTs 29553 of 63400 (46.61%), Block RAM Tile 80.5 of 135 (59.63%), DSPs 6 of 240 (2.50%)
        nes_rev2.bit sha256 fce369429fc3cced7b83c56d50de2dea043cb003c25300aab8e64a3493814f2b
Jeremy's SDRAM precharge fix (f242532462): the burst-ending precharge now closes all banks, so save-state slots (SDRAM bank 2) can no longer read a stale row. Same features as 0.0.1+rev2.3 otherwise.
TEST LOG (hardware): not yet run for this build.
TEST LOG (hardware, 2026-09-19 00:05-00:09, firmware local13): Light From Within Demo: Save State (slot 2) "State saved" 156 ms, Load State "State loaded" 156 ms, Exit Core wrote the new .ss (512 KiB, slots 1-2), relaunch + Load State from the card "State loaded" 182 ms.


REBUILD 2026-09-24 10:20: package r2.5 (was r2.4); core-nes branch nes @ c96b2097
Build:  Vivado 2023.2, xc7a100tcsg324-1, target gamebub_rev2; timing met, WNS 0.484 ns, WHS 0.030 ns
        Slice LUTs 29717 of 63400 (46.87%), Block RAM Tile 80.5 of 135 (59.63%), DSPs 6 of 240 (2.50%)
        nes_rev2.bit sha256 bf20e7f4891851e4bf28ee6ea6c9ec9a725942e445feae60c108de081c4d6f01
Sound: MiSTer's DC blocker in the framework (about 15 Hz at 48 kHz): the output is centered on zero (no constant offset, fewer pops where the sound starts or stops). Saves: the framework's SPI receiver fix (a transfer that starts while an earlier one is still queued is no longer misread).
TEST LOG (hardware): not yet run for this build.


REBUILD 2026-09-25 11:31: package r2.6 (was r2.5); core-nes branch nes @ 8f7de0c5
Build:  Vivado 2023.2, xc7a100tcsg324-1, target gamebub_rev2; timing met, WNS 0.130 ns, WHS 0.030 ns
        Slice LUTs 30574 of 63400 (48.22%), Block RAM Tile 82 of 135 (60.74%), DSPs 12 of 240 (5.00%)
        nes_rev2.bit sha256 0a1301f6a54cb144798de667a266834a2e0226cfc7fa86bbfd1ad77c8f14d8e3
Screen Filter: Smooth (r2.6). New core setting Screen Filter (Off / Smooth, default Off): in 4:3, Fit and Stretch the picture is drawn sharp bilinear, every NES pixel crisp and only the one screen pixel on each NES-pixel boundary blending its two neighbors (weights in eighths), so nearest-neighbor's uneven 1- and 2-pixel-wide NES pixels are gone. 1x and HDMI are unchanged; Off shows exactly what r2.5 showed. Framework: GB-MiSTer's screen filter register 0xF100101C plus value 3 = Smooth (HandheldSmoothScaler: far-edge reads, a one-clock delay along the LCD scan and a one-RAMB18 line buffer across it).
TEST LOG (hardware): not yet run for this build.


REBUILD 2026-09-25 16:30: package r2.7 (was r2.6); core-nes branch nes @ 5315ab24
Build:  Vivado 2023.2, xc7a100tcsg324-1, target gamebub_rev2; timing met, WNS 0.374 ns, WHS 0.025 ns
        Slice LUTs 30592 of 63400 (48.25%), Block RAM Tile 82 of 135 (60.74%), DSPs 12 of 240 (5.00%)
        nes_rev2.bit sha256 74e8b1cefd5c600b07040221b24d6512f9cdca7753d66dd45247c533fc119034
Screen Filter: Smooth without latency (fixes the garbage strip on the first 2 rows of every LCD scan line, seen with Off too); HandheldLcdScanSpec checks every displayed pixel.
TEST LOG (hardware): not yet run for this build.


REBUILD 2026-09-26 11:04: package r2.8 (was r2.7); core-nes branch nes @ 3d662af5
Build:  Vivado 2023.2, xc7a100tcsg324-1, target gamebub_rev2; timing met, WNS 0.374 ns, WHS 0.047 ns
        Slice LUTs 30586 of 63400 (48.24%), Block RAM Tile 82 of 135 (60.74%), DSPs 12 of 240 (5.00%)
        nes_rev2.bit sha256 58bd930057d826d081cb142adf7ea0236625e50f3f9b17bed84a67ece5849c41
r2.7 + one change: the screen driver never cuts a refresh short (a 50 Hz / PAL game, or pictures faster than the panel, could leave a ghost on the screen). No visible change at the normal rate.
TEST LOG (hardware): not yet run for this build.


REBUILD 2026-09-26 14:40: package r2.9 (was r2.8); core-nes branch nes @ b1feb344
Build:  Vivado 2023.2, xc7a100tcsg324-1, target gamebub_rev2; timing met, WNS 0.608 ns, WHS 0.025 ns
        Slice LUTs 30636 of 63400 (48.32%), Block RAM Tile 82 of 135 (60.74%), DSPs 12 of 240 (5.00%)
        nes_rev2.bit sha256 1eda0c2ac5f3cb522e54cf49a0f25f89753ddf0b1eee83c3dcf07b2938b4b074
r2.8 + one change: counts screen refreshes for the log (repeats 0xF1002008, refreshes 0xF100200C) to check the screen fix. No visible change.
TEST LOG (hardware): not yet run for this build.


REBUILD 2026-09-26 22:18: package r2.10 (was r2.9); core-nes branch nes @ 325d3c81
Build:  Vivado 2023.2, xc7a100tcsg324-1, target gamebub_rev2; timing met, WNS 0.550 ns, WHS 0.013 ns
        Slice LUTs 31170 of 63400 (49.16%), Block RAM Tile 82 of 135 (60.74%), DSPs 13 of 240 (5.42%)
        nes_rev2.bit sha256 94f0929297b5406a0fec47242bc7799fb49e31d7cb1efc8847166b4fe788ff75
Sound Filter setting (Off / Soft / Softer / Speaker, default Soft): core-ngpc's low-pass at 41.95 kHz between the CIC and the rate adapter (core-nes 325d3c81); the new settings.json has id 13.
TEST LOG (hardware): not yet run for this build.


REBUILD 2026-09-29 00:00: package r2.11 (was r2.10); core-nes branch nes @ 100d0bb7
Build:  Vivado 2023.2, xc7a100tcsg324-1, target gamebub_rev2; timing met, WNS 0.277 ns, WHS 0.028 ns
        Slice LUTs 31168 of 63400 (49.16%), Block RAM Tile 82 of 135 (60.74%), DSPs 13 of 240 (5.42%)
        nes_rev2.bit sha256 fc558493c2ad2566e5318e329f9ae2c3998c182f67f2e3a9c17b043d9d1d8167
HDMI lanes and polarity for the production dock (fpga e63fe592): the pin map only, nothing else changed
TEST LOG (hardware): not yet run for this build.


REBUILD 2026-10-09 09:57: package r2.12 (was r2.11); core-nes branch nes @ 788f06e1
Build:  Vivado 2023.2, xc7a100tcsg324-1, target gamebub_rev2; timing met, WNS 0.487 ns, WHS 0.024 ns
        Slice LUTs 31180 of 63400 (49.18%), Block RAM Tile 82 of 135 (60.74%), DSPs 13 of 240 (5.42%)
        nes_rev2.bit sha256 c2df2f586fdd99095109e33647a1de3ceb285b74b19e273e0bdcc0891c5ef9a1
From the 2026-10-09 audit (build/audit-2026-10-09/nes.md): no save states for a cartridge with more than 32 KiB of CHR-RAM (the state keeps 32 KiB; a load restored the registers but not the tiles above, Kira Kira Star Night DX). Rumble strength PWM in the framework (no rumble on this core).
TEST LOG (hardware): not yet run for this build.


REBUILD 2026-10-09 14:13: package r2.13 (was r2.12); core-nes branch nes @ dbb13554
Build:  Vivado 2023.2, xc7a100tcsg324-1, target gamebub_rev2; timing met, WNS 0.208 ns, WHS 0.001 ns
        Slice LUTs 31191 of 63400 (49.20%), Block RAM Tile 82 of 135 (60.74%), DSPs 13 of 240 (5.42%)
        nes_rev2.bit sha256 ee816045e95a9439b4277d9af48539326aefd009bda34897d89496a36ca97b25
Two changes (2026-10-09 audit): (1) the SRAM pins (CHR and the high cartridge RAM) go through the controller's I/O-block register stage with the SNES core's pin constraints (cfab7caa; placement-dependent SRAM timing corrupted SNES WRAM once; one more cycle per SRAM access, inside the PPU's budget, unsimulated); (2) PAL / Dendy games: the LCD driver keeps a second, longer scan line for the 50.007 Hz frame and switches to it when the pictures arrive at that rate (every picture shown once, no repeat, no tear). If graphics look wrong in every game, (1) is the suspect; if only PAL games differ, (2).
TEST LOG (hardware): not yet run for this build.
