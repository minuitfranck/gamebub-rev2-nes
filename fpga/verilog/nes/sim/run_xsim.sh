#!/bin/sh
# Simulate the vendored NES core with tb_nes.sv in the Vivado simulator.
# Usage: run_xsim.sh <rom.nes> <workdir> [xsim plusargs...]
#   e.g. run_xsim.sh "Balloon Fight (USA).nes" /tmp/nes-sim +frames40 +press_start20 +dump_from10 +dump_every10
# Needs Vivado's bin directory on PATH (xvhdl, xvlog, xelab, xsim).
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
RTL="${NES_RTL:-$HERE/../rtl}"
ROM="$1"; WORK="$2"; shift 2
mkdir -p "$WORK"
python "$HERE/make_rom_hex.py" "$ROM" "$WORK"
cd "$WORK"
if [ ! -f xsim.dir/tb_nes_snap/xsimk.exe ] && [ ! -f xsim.dir/tb_nes_snap/axsim.dll ] || [ -n "$REBUILD" ]; then
  xvhdl "$RTL/bus_savestates.vhd" "$RTL/t65/T65_Pack.vhd" "$RTL/t65/T65_ALU.vhd" "$RTL/t65/T65_MCode.vhd" \
        "$RTL/t65/T65.vhd" "$RTL/savestates.vhd" "$RTL/statemanager.vhd" > xvhdl.log
  xvlog -sv --relax "$RTL/regs_savestates.sv" "$RTL/apu.sv" "$RTL/ppu.sv" "$RTL/mappers/MMC1.sv" "$RTL/mappers/MMC2.sv" \
        "$RTL/mappers/MMC3.sv" "$RTL/mappers/generic.sv" "$RTL/cart.sv" "$RTL/nes.sv" "$HERE/tb_nes.sv" > xvlog.log
  xelab --relax -debug off -O3 tb_nes -s tb_nes_snap > xelab.log
fi
ARGS=$(cat rom.args)
xsim tb_nes_snap -R $(for a in $ARGS "$@"; do printf -- '-testplusarg %s ' "${a#+}"; done) > xsim.log
tail -5 xsim.log
