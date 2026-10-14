# Changelog

Builds of the NES / Famicom core for the rev2 Game Bub, newest first. Each build was run on a rev2 handheld before
it was kept, unless noted. The full working history of the rev2 project, including the tests behind each line here,
is in the project's changelog.

## r2.13 (9 October 2026)
- The SRAM pins (CHR and the high cartridge RAM) go through the controller's registered I/O stage with the SNES
  port's pin constraints; placement-dependent SRAM timing had corrupted the SNES port's work RAM once. One more cycle
  per SRAM access, inside the PPU's budget.
- PAL and Dendy games: the LCD driver keeps a second, longer scan line for the 50 Hz frame and switches to it when
  the pictures arrive at that rate, so every picture is shown once (before, one in five was shown twice and the repeat
  overlapped the next picture). Checked in simulation; the handheld check is pending.

## r2.12 (9 October 2026)
- No save states for a cartridge with more than 32 KiB of CHR-RAM: the state holds 32 KiB, and a load restored the
  registers but not the tiles above (seen with Kira Kira Star Night DX). The Save State and Load State rows are hidden
  for such games.

## r2.11 (29 September 2026)
- HDMI lanes and polarity for the production Game Bub dock. The handheld's own screen does not use these pins.

## r2.10 (26 September 2026)
- Sound Filter setting (Off, Soft, Softer, Speaker; default Soft): a second-order low-pass on the decimated samples,
  like the NES's own 14 kHz output filter. Before, the APU's square edges and noise reached the speaker unsoftened.

## r2.9 (26 September 2026)
- Screen refresh counters for the log. No visible change.

## r2.8 (26 September 2026)
- The screen driver never cuts a refresh short (a 50 Hz game, or pictures faster than the panel, could leave a ghost
  band on the screen).

## r2.7 (25 September 2026)
- Screen Filter: Smooth without latency (fixes a garbage strip on the first two rows of every LCD scan line, seen
  with Off too).

## r2.6 (25 September 2026)
- Screen Filter setting (Off, Smooth): sharp bilinear scaling in 4:3, Fit and Stretch.

## r2.5 (24 September 2026)
- MiSTer's DC blocker on the sound output; the framework's SPI receiver fix (a transfer that starts while an earlier
  one is still queued is no longer misread).

## r2.4 (18 September 2026)
- The SDRAM precharge fix from Jeremy O'Brien's SNES port: the burst-ending precharge closes all banks, so save-state
  slots can no longer read a stale row. Save states verified on the handheld on 19 September: save, load, the `.ss`
  file written at exit, relaunch and load again.

## 0.0.1+rev2.3 (18 September 2026)
- Settings only: PPU Reset (Famicom / NES behavior of Reset Core); Save State and Load State as hotkeys (Home + R,
  Home + L).

## 0.0.1+rev2.2 (17 September 2026)
- First build from sources that also simulate: the core alone and the whole core (ROM transfer, header analysis,
  battery save, a save state and a load) in xsim. Mappers 0, 1, 2, 3, the MMC3 family, 7, 9 / 10, 28, 30 and the
  others listed in the README. Save states: four slots of 256 KiB, written as `<game>.ss` at exit.
