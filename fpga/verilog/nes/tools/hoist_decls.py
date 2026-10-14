#!/usr/bin/env python3
"""Move module-level declarations above their first use (Vivado / xsim).

Usage: hoist_decls.py [--check] <file.sv> ...

MiSTer's RTL uses many nets and variables before declaring them (Quartus looks
ahead). Vivado synthesis only warns ("used before its declaration", "already
implicitly declared"), but when the first use is in a port connection it
creates an implicit net, and a later `wire x = <expr>;` then loses its
assignment: xsim showed the PPU's `vram_r_ppudata` undriven (z). This tool
rewrites each module so that every module-level declaration (a line starting
at column 0 with `wire`, `reg` or `logic`) that is referenced earlier in the
module is declared right after the module's port list:

  - `wire [w] x = <expr>;` (possibly continued on following lines) becomes a
    bare `wire [w] x;` at the top and `assign x = <expr>;` in place;
  - any other declaration (`wire [w] a, b;`, `reg [w] x = <const>;`, memories)
    moves to the top unchanged.

A declaration whose width or unpacked dimensions mention an identifier that
is not a module parameter is reported and left alone (it would need a
parameter declared further down). Each moved declaration carries a trailing
`// Game Bub: hoisted` comment. With --check the files are not written and
the tool exits non-zero if anything would move.
"""
import re
import sys
from pathlib import Path

KEYWORDS = {"wire", "reg", "logic", "signed", "unsigned", "input", "output", "inout", "assign", "begin", "end",
            "if", "else", "case", "endcase", "always", "posedge", "negedge", "or", "and", "not", "module",
            "endmodule", "localparam", "parameter", "integer", "genvar", "generate", "endgenerate", "for", "function",
            "endfunction", "task", "endtask", "default", "initial", "casez", "casex", "unique", "priority"}
DECL_START = re.compile(r"^(wire|reg|logic)\b")
IDENT = re.compile(r"\b[A-Za-z_]\w*\b")


def strip_comments(text: str) -> str:
    """Blank out comments and strings, keeping offsets and newlines."""
    out = list(text)
    i = 0
    n = len(text)
    while i < n:
        if text.startswith("//", i):
            j = text.find("\n", i)
            j = n if j < 0 else j
            for k in range(i, j):
                out[k] = " "
            i = j
        elif text.startswith("/*", i):
            j = text.find("*/", i + 2)
            j = n if j < 0 else j + 2
            for k in range(i, j):
                if out[k] != "\n":
                    out[k] = " "
            i = j
        elif text[i] == '"':
            j = i + 1
            while j < n and text[j] != '"':
                j += 2 if text[j] == "\\" else 1
            for k in range(i + 1, min(j, n)):
                if out[k] != "\n":
                    out[k] = " "
            i = j + 1
        else:
            i += 1
    return "".join(out)


def process(path: Path, check: bool) -> int:
    raw = path.read_bytes().decode("utf-8")
    nl = "\r\n" if "\r\n" in raw else "\n"
    text = raw.replace("\r\n", "\n")
    clean = strip_comments(text)
    lines = text.split("\n")
    clean_lines = clean.split("\n")
    moved_total = 0

    # Module spans (line indices): header end = first line after "module" ending in ");".
    modules = []
    i = 0
    while i < len(lines):
        if re.match(r"^\s*module\b", clean_lines[i]):
            start = i
            j = i
            depth = 0
            header_end = None
            # The port list ends at the first ");" that closes the parenthesis opened after the name.
            seen_paren = False
            while j < len(lines):
                for ch in clean_lines[j]:
                    if ch == "(":
                        depth += 1
                        seen_paren = True
                    elif ch == ")":
                        depth -= 1
                if seen_paren and depth == 0 and ";" in clean_lines[j]:
                    header_end = j
                    break
                if not seen_paren and ";" in clean_lines[j]:
                    header_end = j
                    break
                j += 1
            k = header_end + 1
            while k < len(lines) and not re.match(r"^\s*endmodule\b", clean_lines[k]):
                k += 1
            modules.append((start, header_end, k))
            i = k + 1
        else:
            i += 1

    edits = []  # (header_end, insert_lines, [(line index range to replace, new lines)])
    for start, header_end, end in modules:
        header = "\n".join(clean_lines[start:header_end + 1])
        params = set(re.findall(r"\b(?:parameter|localparam)\s+(?:\[[^\]]*\]\s*)?(?:\w+\s+)?([A-Za-z_]\w*)", header))
        body_first = header_end + 1
        # Body localparams (single-line constants) that a hoisted declaration's dimensions may need.
        body_params = {}
        for bi in range(body_first, end):
            pm = re.match(r"^\s*localparam\s+(?:\[[^\]]*\]\s*)?([A-Za-z_]\w*)\s*=[^;]*;\s*$", clean_lines[bi])
            if pm:
                body_params[pm.group(1)] = bi
        hoisted = []
        hoisted_params = set()
        replacements = {}
        li = body_first
        while li < end:
            cl = clean_lines[li]
            m = DECL_START.match(cl)
            if not m:
                li += 1
                continue
            # The declaration statement: up to the first ';' (may span lines).
            last = li
            while ";" not in clean_lines[last]:
                last += 1
            stmt = "\n".join(clean_lines[li:last + 1])
            stmt = stmt[:stmt.index(";") + 1] if ";" in stmt else stmt
            body = stmt[len(m.group(1)):].rstrip(";")
            # Split off an initializer (single name) at the first top-level '='.
            init_at = None
            depth = 0
            for idx, ch in enumerate(body):
                if ch in "([{":
                    depth += 1
                elif ch in ")]}":
                    depth -= 1
                elif ch == "=" and depth == 0 and (idx + 1 >= len(body) or body[idx + 1] != "=") and body[idx - 1] not in "=!<>":
                    init_at = idx
                    break
            decl_part = body if init_at is None else body[:init_at]
            names_part = re.sub(r"\[[^\]]*\]", " ", decl_part)
            names_part = re.sub(r"\b(signed|unsigned)\b", " ", names_part)
            names = [x for x in re.findall(r"[A-Za-z_]\w*", names_part)]
            # Only the names before any unpacked dimension matter; drop keywords.
            names = [x for x in names if x not in KEYWORDS]
            dims_idents = set(re.findall(r"[A-Za-z_]\w*", " ".join(re.findall(r"\[([^\]]*)\]", decl_part)))) - KEYWORDS
            earlier = "\n".join(clean_lines[body_first:li])
            used_before = [x for x in names if re.search(r"\b" + re.escape(x) + r"\b", earlier)]
            if used_before:
                needed = dims_idents - params
                if needed and not needed <= set(body_params):
                    print(f"{path}:{li + 1}: NOT hoisted ({', '.join(used_before)}): dimensions use {sorted(needed - set(body_params))}")
                    li = last + 1
                    continue
                # A body localparam the dimensions need moves up first (it is a constant).
                for pname in sorted(needed):
                    if pname not in hoisted_params:
                        bi = body_params[pname]
                        hoisted.append(lines[bi] + " // Game Bub: hoisted")
                        replacements[bi] = []
                        hoisted_params.add(pname)
                if init_at is not None and m.group(1) == "wire":
                    if len(names) != 1:
                        print(f"{path}:{li + 1}: NOT hoisted: initialized multi-name declaration")
                    else:
                        orig = lines[li]
                        kw_end = orig.index(m.group(1)) + len(m.group(1))
                        # bare declaration: text of the original up to '=' on the first line (single-line decl part)
                        first_line_clean = clean_lines[li]
                        eq_pos = first_line_clean.find("=")
                        if eq_pos < 0:
                            print(f"{path}:{li + 1}: NOT hoisted: initializer not on the first line")
                        else:
                            bare = orig[:eq_pos].rstrip() + ";"
                            hoisted.append(bare + " // Game Bub: hoisted (assigned below)")
                            new_first = "assign " + names[0] + " " + orig[eq_pos:]
                            replacements[li] = [new_first]
                            moved_total += 1
                else:
                    block = lines[li:last + 1]
                    block[-1] = block[-1] + " // Game Bub: hoisted"
                    hoisted.extend(block)
                    for d in range(li, last + 1):
                        replacements[d] = []
                    moved_total += 1
            li = last + 1
        if hoisted:
            edits.append((header_end, hoisted, replacements))

    if not edits:
        return 0
    if check:
        return moved_total
    new_lines = []
    edit_by_header = {e[0]: e for e in edits}
    all_repl = {}
    for e in edits:
        all_repl.update(e[2])
    for idx, line in enumerate(lines):
        if idx in all_repl:
            new_lines.extend(all_repl[idx])
        else:
            new_lines.append(line)
        if idx in edit_by_header:
            new_lines.append("// Game Bub: declarations used before their original position (tools/hoist_decls.py)")
            new_lines.extend(edit_by_header[idx][1])
    path.write_bytes(nl.join(new_lines).encode("utf-8"))
    return moved_total


def main() -> None:
    args = sys.argv[1:]
    check = "--check" in args
    files = [Path(a) for a in args if a != "--check"]
    total = 0
    for f in files:
        n = process(f, check)
        if n:
            print(f"{f}: {n} declaration(s) {'to hoist' if check else 'hoisted'}")
        total += n
    if check and total:
        sys.exit(1)


if __name__ == "__main__":
    main()
