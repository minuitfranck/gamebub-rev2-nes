# Bitstreams and the external core packages (SNES, PC Engine).
#
#   make snes                build/snes/snes.bit and snes.bit.hs
#   make package             build/package/cores/jeremy.SNES/ from build/snes
#   make snes package        build, then package
#   make TARGET=gamebub_rev2 pce   build/pce/pce.bit and pce.bit.hs (the PCE
#                            package only lists a gamebub_rev2 bitstream)
#   make package-pce         build/package/cores/minuitfranck.PCE/ from build/pce
#   make all                 every bitstream (boot, gameboy, gba, snes, pce)
#   make TARGET=gamebub_rev3 gba
#
# A bitstream target always rebuilds (Vivado takes ~10 minutes; make cannot
# tell whether the source tree changed). `package` / `package-pce` only copy
# from an existing build. Commands run through `nix develop ..#vivado` unless
# vivado is already on PATH (i.e. you are inside that shell). On Windows the
# same build_core.py runs through the shims described in
# verilog/pce/tools/README-build.md instead of this Makefile.

TARGET ?= gamebub_rev4
# The PCE research core only ships a rev2 bitstream (cores/pce/core.json).
TARGET_pce := gamebub_rev2
BUILD ?= build
PACKAGE_OUT ?= $(BUILD)/package

CORES := boot gameboy gba snes pce
CLASS_boot := net.gamebub.core.boot.HandheldBoot
CLASS_gameboy := net.gamebub.core.gameboy.HandheldGameboy
CLASS_gba := net.gamebub.core.gba.HandheldGba
CLASS_snes := net.gamebub.core.snes.HandheldSnes
CLASS_pce := net.gamebub.core.pce.HandheldPce

ifeq ($(shell command -v vivado 2>/dev/null),)
RUN := nix develop ..\#vivado -c
endif

.PHONY: all $(CORES) package package-pce clean

all: $(CORES)

$(CORES):
	$(RUN) python3 scripts/build_core.py --target $(or $(TARGET_$@),$(TARGET)) --name $@ \
	    --core-class $(CLASS_$@) --build-root $(BUILD)/$@

package: $(BUILD)/snes/snes.bit
	python3 scripts/package_core.py --name snes --build-root $(BUILD)/snes \
	    --out $(PACKAGE_OUT)

$(BUILD)/snes/snes.bit:
	@echo "$@ is missing: run 'make snes' first" >&2; exit 1

# PC Engine package: fpga/cores/pce/ descriptors + build/pce/pce.bit copied as
# pce_rev2.bit (the filename core.json names; package_core.py falls back to
# <name>.bit).
package-pce: $(BUILD)/pce/pce.bit
	python3 scripts/package_core.py --name pce --build-root $(BUILD)/pce \
	    --out $(PACKAGE_OUT)

$(BUILD)/pce/pce.bit:
	@echo "$@ is missing: run 'make TARGET=gamebub_rev2 pce' first" >&2; exit 1

clean:
	rm -rf $(BUILD)
