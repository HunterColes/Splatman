"""Write the tiny synthetic 3DGS PLY used by unit tests (card #5).

    python3 -I docs/samples/make_fixture.py library-feature/src/test/resources/splats/tiny-sh1.ply

Four splats with SH degree 1 and hand-picked values, so a test can check every field exactly. Splat i (0-based) has:
  position  (i, 2i, -i)
  f_dc      (0.1 i, 0.2, -0.3)
  f_rest_k  i + k / 10 for k in 0..8
  opacity   i - 1.5            (raw, pre-sigmoid)
  scale     (-1 - i, -2, -3)   (log scale)
  rot       (1, 0, 0, 0) for even i, (0, 1, 0, 0) for odd i
All values are exact in float32 except the tenths, which a test compares with a small tolerance.
Rerunning it must give a byte-identical file. Standard library only.
"""

import struct
import sys

PROPS = (["x", "y", "z", "f_dc_0", "f_dc_1", "f_dc_2"] + [f"f_rest_{k}" for k in range(9)]
         + ["opacity", "scale_0", "scale_1", "scale_2", "rot_0", "rot_1", "rot_2", "rot_3"])
COUNT = 4


def splat(i):
    rot = (1.0, 0.0, 0.0, 0.0) if i % 2 == 0 else (0.0, 1.0, 0.0, 0.0)
    return ([float(i), 2.0 * i, -float(i), 0.1 * i, 0.2, -0.3] + [i + k / 10 for k in range(9)]
            + [i - 1.5, -1.0 - i, -2.0, -3.0] + list(rot))


def main(dst):
    head = ["ply", "format binary_little_endian 1.0", f"element vertex {COUNT}"]
    head += [f"property float {p}" for p in PROPS] + ["end_header"]
    with open(dst, "wb") as o:
        o.write(("\n".join(head) + "\n").encode("ascii"))
        for i in range(COUNT):
            o.write(struct.pack("<%df" % len(PROPS), *splat(i)))


if __name__ == "__main__":
    main(sys.argv[1])
