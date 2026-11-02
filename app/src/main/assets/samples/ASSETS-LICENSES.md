# Bundled Gaussian splat samples

All three files are CC0 1.0 (public domain dedication, https://creativecommons.org/publicdomain/zero/1.0/). CC0
needs no attribution; we credit the authors anyway. Research and provenance: `docs/research/splat-samples.md`.

| File | Format | Splats | Author | Source | Licence |
|---|---|---|---|---|---|
| `cactus-sh1.ply` | 3DGS PLY, SH degree 1 (23 floats a splat) | 139,410 | steam studio / 3D SCAN STUDIO iris (https://www.steam-studio.jp) | https://note.com/steam_studio/n/ne9736d94f162, file `PLY(postshot)/cactus_splat3_30kSteps_142k_splats.ply` in the zip https://app.box.com/s/itozvq23jh4av2a5hg08d7qevdbi93ii | CC0 1.0 (the zip's Readme.txt) |
| `measuring-tape.ply` | 3DGS PLY, SH degree 0 (with `nx,ny,nz`) | 19,469 | DX.GL, trained from a Poly Haven CC0 model; hosted by Innerscene | https://www.innerscene.com/samples/gsplat-measuring-tape.ply | CC0 1.0 |
| `cactus.spz` | SPZ v3, SH degree 0 | 452,451 | steam studio's cactus capture, converted to SPZ by Innerscene | https://www.innerscene.com/samples/gsplat-cactus.spz (page https://www.innerscene.com/tools/library/3d-parts/cactus-spz-b02130a8) | CC0 1.0 |

## Changes made

- `cactus-sh1.ply`: the original has full SH degree 3 (59 floats a splat, 32.9 MB). It was trimmed to SH band 1 by
  `python3 -I docs/samples/trim_ply.py <original> cactus-sh1.ply`, which keeps `f_rest_0-2, 15-17, 30-32` (the
  first three coefficients of each colour) as `f_rest_0-8` and copies every other property unchanged.
- `measuring-tape.ply` and `cactus.spz` are byte-for-byte the downloaded files, renamed.

SHA-256: cactus original `95ba6aeb2825674814543d930c1a7e1c65bbabd57a735a669ae71ca24178e1e5`, `cactus-sh1.ply`
`fa4380c380daee622514e9e1a2b1515ec8a3d41505e96c52162bf032ec7de155`, `measuring-tape.ply`
`1f6323c7ac68c8a4076789fdf0d67d6a7459412de4971c2f1e6a4b7470feff10`, `cactus.spz`
`ddf7b1f8a2e9b9f2edf5fdafc8d9fc77ddd96fde87cf5695f490076039e3d974`.
