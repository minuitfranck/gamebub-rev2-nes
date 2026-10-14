#!/usr/bin/env python3
"""Convert tb_nes.sv frame dumps (frame_NNNN.hex) to PNG with MiSTer's Kitrinx palette.

Usage: frames_to_png.py <dir> [palette index 0-4]

Applies the palette and emphasis exactly like NesVideoCapture (video.sv), then
reduces to 5 bits per channel and expands back, so the PNG shows what the
Game Bub framebuffer holds.
"""
import re
import struct
import sys
import zlib
from pathlib import Path

HERE = Path(__file__).resolve().parent
NAMES = ["pal_kitrinx_lut", "pal_smooth_lut", "pal_wavebeam_lut", "pal_sonycxa_lut", "pal_pc10_lut"]


def palette(index: int) -> list[int]:
    s = (HERE.parent / "video.sv.reference").read_text()
    m = re.search(NAMES[index] + r"\[64\]\s*=\s*'\{(.*?)\};", s, flags=re.S)
    return [int(v, 16) for v in re.findall(r"'h([0-9A-Fa-f]{6})", m.group(1))]


def emphasize(r: int, g: int, b: int, e: int) -> tuple[int, int, int]:
    q = lambda x: x >> 2
    t = lambda x: x >> 3
    if e == 1: g, b = g - q(g), b - q(b)
    elif e == 2: r, b = r - q(r), b - q(b)
    elif e == 3: r, g, b = r - q(r), g - t(g), b - q(b) - t(b)
    elif e == 4: r, g = r - t(r), g - t(g)
    elif e == 5: r, g, b = r - t(r), g - q(g), b - t(b)
    elif e == 6: r, g, b = r - q(r), g - t(g), b - t(b)
    elif e == 7: r, g, b = r - q(r), g - q(g), b - q(b)
    return r & 0xFF, g & 0xFF, b & 0xFF


def write_png(path: Path, width: int, height: int, rows: list[bytes]) -> None:
    raw = b"".join(b"\x00" + row for row in rows)
    def chunk(tag: bytes, data: bytes) -> bytes:
        return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)
    png = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b"")
    path.write_bytes(png)


def main() -> None:
    d = Path(sys.argv[1])
    pal = palette(int(sys.argv[2]) if len(sys.argv) > 2 else 0)
    # tb_system.sv dumps what the framebuffer holds: RGB555 words.
    for f in sorted(d.glob("sysframe_*.hex")):
        rows = []
        for line in f.read_text().splitlines():
            row = bytearray()
            for v in line.split():
                x = int(v, 16)
                r, g, b = (x >> 10) & 31, (x >> 5) & 31, x & 31
                row += bytes(((r << 3) | (r >> 2), (g << 3) | (g >> 2), (b << 3) | (b >> 2)))
            rows.append(bytes(row))
        write_png(f.with_suffix(".png"), 256, len(rows), rows)
        print(f.with_suffix(".png"))
    for f in sorted(d.glob("frame_*.hex")):
        rows = []
        for line in f.read_text().splitlines():
            row = bytearray()
            for v in line.split():
                x = int(v, 16)
                color, emph = x & 0x3F, x >> 6
                if (color & 0xE) == 0xE:
                    emph = 0
                c = pal[color]
                r, g, b = emphasize(c >> 16, (c >> 8) & 0xFF, c & 0xFF, emph)
                r, g, b = [(v5 << 3) | (v5 >> 2) for v5 in (r >> 3, g >> 3, b >> 3)]
                row += bytes((r, g, b))
            rows.append(bytes(row))
        write_png(f.with_suffix(".png"), 256, len(rows), rows)
        print(f.with_suffix(".png"))


if __name__ == "__main__":
    main()
