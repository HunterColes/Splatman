"""Trim a 3DGS PLY with SH degree 3 down to SH degree 1 (card #5).

    python3 -I docs/samples/trim_ply.py <in.ply> <out.ply>

3DGS stores f_rest planar: red coefficients f_rest_0..14, green 15..29, blue 30..44. Band 1 is the first three of
each colour, so the kept columns are f_rest_0-2, 15-17, 30-32, written back as f_rest_0..8 (the layout a degree-1
3DGS export has). Every other property is copied unchanged and in order. Only float properties are supported.
Standard library only.
"""

import struct
import sys

KEEP_REST = [0, 1, 2, 15, 16, 17, 30, 31, 32]


def main(src, dst):
    with open(src, "rb") as f:
        head = b""
        while not head.endswith(b"end_header\n"):
            line = f.readline()
            if not line:
                raise SystemExit("no end_header")
            head += line
        lines = head.decode("ascii").splitlines()
        assert lines[1] == "format binary_little_endian 1.0", lines[1]
        count = int(next(l for l in lines if l.startswith("element vertex")).split()[2])
        props = [l.split()[2] for l in lines if l.startswith("property")]
        assert all(l.split()[1] == "float" for l in lines if l.startswith("property"))
        assert [f"f_rest_{i}" for i in range(45)] == [p for p in props if p.startswith("f_rest_")]
        keep = [i for i, p in enumerate(props)
                if not p.startswith("f_rest_") or int(p[7:]) in KEEP_REST]
        names, n = [], 0
        for i in keep:
            if props[i].startswith("f_rest_"):
                names.append(f"f_rest_{n}")
                n += 1
            else:
                names.append(props[i])
        stride = 4 * len(props)
        body = f.read()
    assert len(body) == stride * count, (len(body), stride * count)
    out_head = ["ply", "format binary_little_endian 1.0", f"element vertex {count}"]
    out_head += [f"property float {p}" for p in names] + ["end_header"]
    rec = struct.Struct("<%df" % len(props))
    with open(dst, "wb") as o:
        o.write(("\n".join(out_head) + "\n").encode("ascii"))
        for r in rec.iter_unpack(body):
            o.write(struct.pack("<%df" % len(keep), *(r[i] for i in keep)))
    print(f"{count} splats, {len(props)} -> {len(keep)} floats each")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
