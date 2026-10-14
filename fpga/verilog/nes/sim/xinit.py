#!/usr/bin/env python3
"""Simulation-only copy of the vendored SystemVerilog with zero initializers.

Usage: xinit.py <rtl dir> <out dir>

xsim starts every variable without an initializer at X; the FPGA starts every
flip-flop at 0 (Vivado's default INIT), and MiSTer's RTL relies on that for a
few registers that no reset touches (xsim showed ppumem_read / ppumem_addr /
color at X long after reset; nes.sv's cpu_tick_count kept the save-state pause
condition X). This writes copies of the .sv files where each scalar or vector
`reg` declaration without an initializer gets `= '0` (memories, i.e.
declarations with an unpacked dimension, are left alone), and so does each
such `logic` declaration whose names are never the target of an `assign` or a
port connection in the file (a variable with a continuous driver cannot also
be initialized), so the simulation starts like the hardware. The synthesized
sources are not changed. The VHDL files (T65, save states) are not covered;
copy them next to the output.
"""
import re
import sys
from pathlib import Path

DECL = re.compile(r"^(\s*)(reg|logic)(\s+signed)?(\s*\[[^\]]+\])?\s+([A-Za-z_]\w*(?:\s*,\s*[A-Za-z_]\w*)*)\s*;(.*)$")


def continuously_driven(text: str, name: str) -> bool:
    """The name is assigned with `assign` or connected to an instance port somewhere in the file."""
    n = re.escape(name)
    if re.search(r"\bassign\s+" + n + r"\b", text):
        return True
    return bool(re.search(r"\.\w+\s*\(\s*" + n + r"\s*(\[[^\]]*\])?\s*\)", text))


def patch_line(line: str, text: str) -> str:
    m = DECL.match(line)
    if not m:
        return line
    names = [n.strip() for n in m.group(5).split(",")]
    if m.group(2) == "logic" and any(continuously_driven(text, n) for n in names):
        return line
    init = ", ".join(f"{n} = '0" for n in names)
    return f"{m.group(1)}{m.group(2)}{m.group(3) or ''}{m.group(4) or ''} {init};{m.group(6)}"


def main() -> None:
    src, out = Path(sys.argv[1]), Path(sys.argv[2])
    count = 0
    for f in sorted(src.rglob("*.sv")):
        text = f.read_bytes().decode("utf-8")
        nl = "\r\n" if "\r\n" in text else "\n"
        lines = text.split(nl)
        new = [patch_line(line, text) for line in lines]
        count += sum(1 for a, b in zip(lines, new) if a != b)
        dst = out / f.relative_to(src)
        dst.parent.mkdir(parents=True, exist_ok=True)
        dst.write_bytes(nl.join(new).encode("utf-8"))
    print(f"initialized {count} declarations")


if __name__ == "__main__":
    main()
