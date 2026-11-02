"""Write the frame-suite fixtures (card #143) from the bundled CC0 samples, no download.

    python3 -I docs/samples/make_frame_fixtures.py

Reads app/src/main/assets/samples/ and writes splat-train/src/test/resources/frames/:

  cactus-up.ply      cactus-sh1.ply, every 12th splat (11,618), every property kept (f_rest_0-8 too, so the
                     reader's SH frame path runs). The up fixture: an upright cactus in a pot.
  tape-hand.ply      measuring-tape.ply, every 2nd splat (9,735), normals dropped (the reader skips them). The
                     handedness fixture: its render differs from its own left-right mirror.

Splats keep their source order. The tests pick the known parts from the raw file coordinates (FrameFixtures.kt):
known-down = the 20 % with the highest file y (file y points down, ADR 0003), known-left = the 20 % with the
lowest file x (file x is world x). Rerunning gives byte-identical files. Standard library only; little-endian
float32 PLYs in, the same out.
"""

import os
import struct
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SRC = os.path.join(ROOT, "app", "src", "main", "assets", "samples")
DST = os.path.join(ROOT, "splat-train", "src", "test", "resources", "frames")


def read_ply(path):
    with open(path, "rb") as f:
        data = f.read()
    end = data.index(b"end_header\n") + len(b"end_header\n")
    lines = data[:end].decode("ascii").split("\n")
    if lines[1] != "format binary_little_endian 1.0":
        sys.exit(f"{path}: not a little-endian binary PLY")
    count = None
    props = []
    for line in lines:
        words = line.split()
        if words[:2] == ["element", "vertex"]:
            count = int(words[2])
        elif words[:1] == ["property"]:
            if words[1] != "float":
                sys.exit(f"{path}: property {words[2]} is {words[1]}, only float is handled")
            props.append(words[2])
    stride = 4 * len(props)
    if count is None or len(data) - end != count * stride:
        sys.exit(f"{path}: body is {len(data) - end} bytes, header wants {count} x {stride}")
    rows = [struct.unpack_from("<%df" % len(props), data, end + i * stride) for i in range(count)]
    return props, rows


def write_ply(path, props, rows):
    head = ["ply", "format binary_little_endian 1.0", f"element vertex {len(rows)}"]
    head += [f"property float {p}" for p in props] + ["end_header"]
    fmt = "<%df" % len(props)
    with open(path, "wb") as o:
        o.write(("\n".join(head) + "\n").encode("ascii"))
        for r in rows:
            o.write(struct.pack(fmt, *r))


def fixture(src, dst, step, keep):
    """Every [step]th row of [src], with only the [keep] properties, in source order."""
    props, rows = read_ply(os.path.join(SRC, src))
    cols = [props.index(p) for p in keep]
    out = [tuple(r[c] for c in cols) for r in rows[::step]]
    write_ply(os.path.join(DST, dst), keep, out)
    print(f"{dst}: {len(out)} splats, {os.path.getsize(os.path.join(DST, dst))} bytes")


def main():
    os.makedirs(DST, exist_ok=True)
    base = ["x", "y", "z", "f_dc_0", "f_dc_1", "f_dc_2"]
    tail = ["opacity", "scale_0", "scale_1", "scale_2", "rot_0", "rot_1", "rot_2", "rot_3"]
    rest = [f"f_rest_{k}" for k in range(9)]
    fixture("cactus-sh1.ply", "cactus-up.ply", 12, base + rest + tail)
    fixture("measuring-tape.ply", "tape-hand.ply", 2, base + tail)


if __name__ == "__main__":
    main()
