#!/usr/bin/env python3
"""Prepare a .nes image for tb_nes.sv (xsim).

Usage: make_rom_hex.py <rom.nes> <outdir>

Writes <outdir>/prg.hex and <outdir>/chr.hex ($readmemh, one byte per line),
<outdir>/file_words.hex (the whole file as little-endian 32-bit words, for
tb_system.sv) and <outdir>/file_size.txt,
and <outdir>/rom.args with the plusargs the testbench takes: +flags<hex>
+prgmask<hex> +chrmask<hex>, computed exactly like MiSTer's GameLoader
(NES.sv lines 1404-1540) and the Game Bub NesRomInfo.
"""
import sys
from pathlib import Path


def mask(size2k: int) -> int:
    return sum(1 << i for i in range(10) if size2k > (1 << i))


def analyze(h: bytes) -> tuple[int, int, int, int, int]:
    prgrom, chrrom, chrram = h[4], h[5], h[11] & 0xF
    nes20 = (h[7] >> 2) & 3 == 2
    nes20_prg = nes20 and (h[9] & 0xF) == 0xF
    nes20_chr = nes20 and (h[9] >> 4) == 0xF
    has_chr_ram = (chrrom == 0) if not nes20 else chrram != 0
    prg_size2 = (((h[4] & 3) << 1 | 1) << (h[4] >> 2)) & 0x3FFFFF if nes20_prg else prgrom << 14
    chr_size2 = (((h[5] & 3) << 1 | 1) << (h[5] >> 2)) & 0x3FFFFF if nes20_chr else chrrom << 13
    chr_ram_size = (64 << chrram) & 0x3FFFFF if nes20 else 0x2000
    prg_mask = mask(prg_size2 >> 11)
    chr_mask = mask((chr_size2 if chr_size2 else chr_ram_size) >> 11)

    def code(pages: int) -> int:
        for c, limit in enumerate([1, 2, 4, 8, 16, 32, 64]):
            if pages <= limit:
                return c
        return 7

    dirty = not nes20 and (h[9] >> 1 != 0 or any(h[10:16]))
    mapper = (0 if dirty else (h[7] >> 4)) << 4 | (h[6] >> 4)
    ines2mapper = h[8] if nes20 else 0
    prgram = h[10] & 0xF if nes20 else 0
    prg_nvram = h[10] >> 4 if nes20 else 0
    piano = nes20 and (h[15] & 0x3F) == 0x19
    flags = 0
    flags |= (h[15] & 0x7F if nes20 else 0) << 53
    flags |= ((h[13] >> 4) if (nes20 and (h[7] & 3) == 1) else 0) << 49
    flags |= ((h[13] & 0xF) if (nes20 and (h[7] & 3) == 1) else 0) << 45
    flags |= ((h[7] & 3) if nes20 else 0) << 43
    flags |= ((h[12] & 3) if nes20 else 0) << 36
    flags |= int(nes20) << 35
    flags |= prg_nvram << 31
    flags |= int(piano) << 30
    flags |= prgram << 26
    flags |= ((h[6] >> 1) & 1) << 25
    flags |= ines2mapper << 17
    flags |= ((h[6] >> 3) & 1) << 16
    flags |= int(has_chr_ram) << 15
    flags |= (h[6] & 1) << 14
    flags |= code(chrrom) << 11
    flags |= code(prgrom) << 8
    flags |= mapper
    prg_bytes = ((h[9] & 0xF) << 8 | prgrom) * 0x4000 if nes20 and not nes20_prg else (prg_size2 if nes20_prg else prgrom * 0x4000)
    chr_bytes = ((h[9] >> 4) << 8 | chrrom) * 0x2000 if nes20 and not nes20_chr else (chr_size2 if nes20_chr else chrrom * 0x2000)
    return flags, prg_mask, chr_mask, prg_bytes, chr_bytes


def main() -> None:
    rom = Path(sys.argv[1]).read_bytes()
    out = Path(sys.argv[2])
    out.mkdir(parents=True, exist_ok=True)
    flags, prg_mask, chr_mask, prg_bytes, chr_bytes = analyze(rom[:16])
    prg = rom[16:16 + prg_bytes]
    chr_ = rom[16 + prg_bytes:16 + prg_bytes + chr_bytes]
    (out / "prg.hex").write_text("\n".join(f"{b:02x}" for b in prg) + "\n")
    (out / "chr.hex").write_text("\n".join(f"{b:02x}" for b in chr_) + "\n" if chr_ else "00\n")
    padded = rom + bytes((4 - len(rom) % 4) % 4)
    words = (f"{int.from_bytes(padded[i:i + 4], 'little'):08x}" for i in range(0, len(padded), 4))
    (out / "file_words.hex").write_text("\n".join(words) + "\n")
    (out / "file_size.txt").write_text(f"{len(rom)}\n")
    (out / "rom.args").write_text(f"+flags{flags:x} +prgmask{prg_mask:x} +chrmask{chr_mask:x}\n")
    print(f"flags=0x{flags:016x} prgmask=0x{prg_mask:x} chrmask=0x{chr_mask:x} prg={prg_bytes} chr={chr_bytes}")


if __name__ == "__main__":
    main()
