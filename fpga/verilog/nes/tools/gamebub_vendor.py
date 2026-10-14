#!/usr/bin/env python3
"""Mechanical part of vendoring the MiSTer NES core for Game Bub (Vivado).

Usage: gamebub_vendor.py --upstream <NES_MiSTer checkout> [--out <fpga/verilog/nes/rtl>]

What it does (the hand edits to nes.v, ppu.sv, apu.sv, savestates.vhd and
statemanager.vhd are NOT done here; see ../README.md):

1. Mapper files (rtl/mappers/{MMC1,MMC2,MMC3,generic}.sv), copied from
   upstream and rewritten so that Vivado synthesizes them:
   - every `assign <port>_b = enable ? <value> : <N>'hZ;` gets a constant
     instead of high impedance when the mapper is disabled: all ones for
     prg_dout_b / chr_dout_b (the upstream `tri1` buses), zero otherwise
     (the `tri0` buses). The ports become `output` instead of `inout`.
     cart.sv then ORs (tri0) or ANDs (tri1) the per-mapper outputs, which
     is exactly the value the upstream tri-state resolution produces when at
     most one mapper is enabled. (Vivado neither resolves internal
     tri-states nor `wor`/`wand` nets correctly: checked with synth_design,
     2026-09-16.)
   - `always begin` (no event control; Quartus infers combinational logic,
     Vivado warns "always block has no event control") becomes
     `always @* begin`.
   - `import regs_savestates::*;` at the top: upstream relies on Quartus
     leaking ppu.sv's file-scope import into later files.
2. rtl/cart.sv, generated from upstream rtl/cart.sv with only the mapper
   instances listed in KEEP (the others live in files that are not
   vendored), each instance's tri-state bus connections renamed to private
   wires, and the bus resolution / save-state wired-OR rebuilt for the kept
   set. Everything else in the module (ports, `me` decode, SDRAM address
   translation, Vs. overrides) is kept verbatim; `wire [1023:0] me` becomes
   `reg` (it is assigned in an always block).

3. Both, finally, go through hoist_decls.py (declarations moved above their
   first use; Vivado otherwise loses `wire x = <expr>;` assignments whose net
   was first used in a port connection, and xsim refuses the files).

The output is deterministic; re-run it after updating the upstream checkout
and review the diff. The hand-edited files (nes.sv, ppu.sv, apu.sv) had
hoist_decls.py applied once, by hand.
"""
import argparse
import re
import sys
from pathlib import Path

MAPPER_FILES = ["MMC1.sv", "MMC2.sv", "MMC3.sv", "generic.sv"]

# Instances of cart.sv to keep: (module, instance name). Their modules must
# live in MAPPER_FILES.
KEEP = [
    ("MMC1", "mmc1"),         # 1, 155, 171
    ("Mapper28", "map28"),    # 0, 2, 3, 7, 28, 94, 97, 180, 185
    ("Mapper30", "map30"),    # 30 (UNROM 512)
    ("MMC2", "mmc2"),         # 9
    ("MMC3", "mmc3"),         # 4 and the MMC3 family
    ("MMC4", "mmc4"),         # 10
    ("Mapper13", "map13"),    # 13 (CPROM)
    ("Mapper34", "map34"),    # 34 (BNROM / NINA-001)
    ("Mapper66", "map66"),    # 66 (GxROM) and relatives
    ("Mapper71", "map71"),    # 71, 232 (Camerica)
    ("Mapper77", "map77"),    # 77
    ("Mapper78", "map78"),    # 78, 70, 152, 81
    ("Mapper79", "map79"),    # 79, 113, 133, 146, 148 (NINA-03/06)
    ("Mapper89", "map89"),    # 89, 93, 184 (Sunsoft)
    ("Mapper107", "map107"),  # 107
    ("Mapper165", "map165"),  # 165
    ("Rambo1", "rambo1"),     # 64, 158 (Tengen RAMBO-1)
    ("NesEvent", "nesev"),    # 105 (NWC 1990)
]

# Shared tri-state nets in upstream cart.sv: net -> (width, default).
# default 0 = tri0 (OR-resolved), 1 = tri1 (AND-resolved).
BUSES = {
    "prg_allow_b": (1, 0),
    "vram_a10_b": (1, 0),
    "vram_ce_b": (1, 0),
    "chr_allow_b": (1, 0),
    "irq_b": (1, 0),
    "prg_addr_b": (22, 0),
    "chr_addr_b": (22, 0),
    "flags_out_b": (16, 0),
    "audio_out_b": (16, 0),
    "prg_dout_b": (8, 1),
    "chr_dout_b": (8, 1),
}

HEADER = "// Game Bub: rewritten by fpga/verilog/nes/tools/gamebub_vendor.py (see fpga/verilog/nes/README.md)\n"


def rewrite_mapper(text: str) -> str:
    nl = "\r\n" if "\r\n" in text else "\n"

    def fix_assign(m: re.Match) -> str:
        port, space, value, width = m.group(1), m.group(2), m.group(3), int(m.group(4))
        ones = port in ("prg_dout_b", "chr_dout_b")
        if width == 1:
            const = "1'b1" if ones else "1'b0"
        else:
            const = f"{width}'h" + ("F" * ((width + 3) // 4) if ones else "0")
        return f"assign {port}{space}= enable ? {value} : {const};"

    text = re.sub(r"assign\s+(\w+_b)(\s*)=\s*enable\s*\?\s*(.+?)\s*:\s*(\d+)'h[zZ]\s*;", fix_assign, text)
    if re.search(r"'h[zZ]\b", text):
        sys.exit("unhandled high-impedance constant left in a mapper file")
    text = re.sub(r"\binout(\s+(?:\[[^\]]+\]\s*)?\w+_b\b)", r"output\1", text)
    if re.search(r"\binout\b", text):
        sys.exit("unhandled inout port left in a mapper file")
    text = re.sub(r"\balways(\s+)begin\b", r"always @*\1begin", text)
    return HEADER.replace("\n", nl) + "import regs_savestates::*;" + nl + text


def find_instance(lines: list[str], module: str, inst: str) -> tuple[int, int]:
    start = None
    pat = re.compile(rf"^{re.escape(module)}\s+{re.escape(inst)}\s*\(")
    for i, line in enumerate(lines):
        if pat.match(line):
            start = i
            break
    if start is None:
        sys.exit(f"instance {module} {inst} not found in cart.sv")
    end = start
    while not lines[end].startswith(");"):
        end += 1
    # Back up over the preamble: banner comment and wire declarations,
    # up to the previous instance's end (or the declarations before the first).
    first = start
    j = start - 1
    while j >= 0 and not lines[j].startswith(");") and not lines[j].startswith("tri") and "Better known as" not in lines[j]:
        j -= 1
    first = j + 1
    return first, end


def generate_cart(upstream: str) -> str:
    nl = "\r\n" if "\r\n" in upstream else "\n"
    lines = upstream.split(nl)
    # Module header: up to (not including) the tri-state declarations.
    tri_start = next(i for i, l in enumerate(lines) if l.startswith("tri0 prg_allow_b"))
    head = lines[:tri_start]
    # Declarations between the tri nets and the (unused, not kept) MMC0 instance's comment.
    decl_end = next(i for i, l in enumerate(lines) if l.startswith("// This mapper used to be default"))
    decls = [l for l in lines[tri_start:decl_end] if not l.startswith("tri0 ") and not l.startswith("tri1 ")]

    out = list(head)
    out.append("// Game Bub: the upstream tri0 / tri1 buses shared by all mapper instances are")
    out.append("// replaced by one private wire per instance, resolved below (OR for tri0, AND")
    out.append("// for tri1); only the mapper instances Game Bub builds are kept.")
    out.extend(decls)

    ss_index = 0
    bus_members: dict[str, list[str]] = {b: [] for b in BUSES}
    wire_decls: list[str] = []
    blocks: list[str] = []
    for k, (module, inst) in enumerate(KEEP):
        first, end = find_instance(lines, module, inst)
        block = lines[first:end + 1]
        text = nl.join(block)
        for bus, (width, _) in BUSES.items():
            private = f"{bus}_{inst}"
            new_text, n = re.subn(rf"\(\s*{bus}\s*\)", f"({private})", text)
            if n:
                text = new_text
                bus_members[bus].append(private)
                wire_decls.append(f"wire {'' if width == 1 else f'[{width - 1}:0] '}{private};")
        def reindex(m: re.Match) -> str:
            nonlocal ss_index
            s = f"SaveStateBus_wired_or[{ss_index}]"
            ss_index += 1
            return s
        text = re.sub(r"SaveStateBus_wired_or\[\s*\d+\s*\]", reindex, text)
        if "SaveStateRAM_wired_or" in text:
            sys.exit(f"{inst}: mapper RAM save states are not supported by the generator")
        blocks.append(text)

    # The private wires must be declared before the instances use them.
    out.append("")
    out.append("// Game Bub: per-instance bus wires (resolved after the instances).")
    out.extend(wire_decls)
    for text in blocks:
        out.append("")
        out.extend(text.split(nl))

    out.append("")
    out.append("// Game Bub: bus resolution (tri0 -> OR, tri1 -> AND).")
    for bus, (width, default) in BUSES.items():
        members = bus_members[bus]
        op = " & " if default else " | "
        dims = "" if width == 1 else f"[{width - 1}:0] "
        if members:
            out.append(f"wire {dims}{bus} = {op.join(members)};")
        else:
            const = (f"{width}'h" + "F" * ((width + 3) // 4)) if default else f"{width}'h0"
            out.append(f"wire {dims}{bus} = {const};")
    out.append("// Game Bub: not built (Mapper 413 extra ROM, FDS disk sides, Mapper 16 EEPROM).")
    out.append("assign prg_aoute_m413 = 3'd0;")
    out.append("wire [1:0] fds_diskside = 2'd0;")
    out.append("wire map16_prg_write = 1'b0, map16_ovr = 1'b0;")
    out.append("wire [7:0] map16_data_out = 8'd0;")
    out.append("wire [17:0] map16_mapper_addr = 18'd0;")
    out.append("")

    # Tail: from `wire [1023:0] me;` to the end, with the save-state wired-OR rebuilt.
    me_line = next(i for i, l in enumerate(lines) if l.startswith("wire [1023:0] me;"))
    ss_line = next(i for i, l in enumerate(lines) if l.startswith("localparam SAVESTATE_MODULES"))
    tail = lines[me_line:ss_line]
    tail[0] = "reg [1023:0] me; // Game Bub: reg (assigned in the always block below; upstream `wire`)"
    out.extend(tail)
    out.append(f"localparam SAVESTATE_MODULES    = {ss_index};")
    out.append("wire [63:0] SaveStateBus_wired_or[0:SAVESTATE_MODULES-1];")
    out.append("")
    out.append("assign SaveStateBus_Dout  = " + " | ".join(f"SaveStateBus_wired_or[{i}]" for i in range(ss_index)) + ";")
    out.append("")
    out.append("// Game Bub: no kept mapper has mapper-internal RAM in the save state.")
    out.append("assign Savestate_MAPRAMReadData = 8'h00;")
    out.append("")
    out.append("endmodule")
    out.append("")
    return HEADER.replace("\n", nl) + "import regs_savestates::*;" + nl + nl.join(out)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--upstream", required=True, type=Path)
    parser.add_argument("--out", type=Path, default=Path(__file__).resolve().parent.parent / "rtl")
    args = parser.parse_args()
    up = args.upstream / "rtl"
    (args.out / "mappers").mkdir(parents=True, exist_ok=True)
    for name in MAPPER_FILES:
        src = (up / "mappers" / name).read_bytes().decode("utf-8")
        (args.out / "mappers" / name).write_bytes(rewrite_mapper(src).encode("utf-8"))
    cart = (up / "cart.sv").read_bytes().decode("utf-8")
    (args.out / "cart.sv").write_bytes(generate_cart(cart).encode("utf-8"))
    # Declarations used before their position (see hoist_decls.py).
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    import hoist_decls
    for f in [args.out / "mappers" / name for name in MAPPER_FILES] + [args.out / "cart.sv"]:
        hoist_decls.process(f, check=False)
    print("ok")


if __name__ == "__main__":
    main()
