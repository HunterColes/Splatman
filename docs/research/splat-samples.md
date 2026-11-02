# Real Gaussian splat samples we may bundle (scout, card #5, 2026-11-01)

Question: which small, redistributable 3DGS files (standard PLY, and an SPZ if any) can ship in this MIT app?
Result: all CC0, none needs attribution (we credit anyway). Everything below was downloaded and its header read.

## Chosen files

| # | File | Format | Splats | Size | Licence | Role |
|---|---|---|---|---|---|---|
| 1 | cactus `cactus_splat3_30kSteps_142k_splats.ply` | 3DGS PLY, Postshot export, full SH3 (`f_dc_0-2`, `f_rest_0-44`, `opacity`, `scale_0-2`, `rot_0-3`, no normals) | 139,410 | 32.9 MB raw | CC0 | the one real object, full property set |
| 2 | `gsplat-measuring-tape.ply` | 3DGS PLY, SH0 only (has `nx,ny,nz`, `f_dc_*`, no `f_rest_*`) | 19,469 | 1.32 MB | CC0 | tiny standard PLY, no trimming needed |
| 3 | `gsplat-cactus.spz` | SPZ v3, gzip, SH degree 0 | 452,451 | 6.8 MB | CC0 | the SPZ sample (header: magic `NGSP`, version 3, numPoints, shDegree 0, fractionalBits 12) |
| opt | `gsplat-potted-plant.splat` | antimatter15 `.splat` (32 B per splat) | 51,278 (size/32) | 1.64 MB | CC0 | only if the importer takes `.splat` |
| opt | `gsplat-armchair.splat` | same | 49,817 | 1.59 MB | CC0 | same |

Total of 1 (trimmed) + 2 + 3 stays well under 40 MB.

## Sources and licence evidence

- Cactus: steam studio / 3D SCAN STUDIO iris, https://note.com/steam_studio/n/ne9736d94f162. The zip's own Readme.txt says "provided under the Creative Commons CC0 license ... free to use for commercial and non-commercial purposes", and asks (does not require) a credit link to https://www.steam-studio.jp. Verified. Zip: https://app.box.com/s/itozvq23jh4av2a5hg08d7qevdbi93ii (file `3DGS_PLY_sample_data.zip`, **1.45 GB**; the PLY is `PLY(postshot)/cactus_splat3_30kSteps_142k_splats.ply` inside). Direct fetch worked with `curl -L "https://app.box.com/index.php?rm=box_download_shared_file&shared_name=itozvq23jh4av2a5hg08d7qevdbi93ii&file_id=f_1827558942828"`. Other zip members are 0.14M to 2M splat variants (too big) and meshes. Captured: 427 photos, Nikon Z7II, Postshot 0.5.250. I read the full file: the 59-float records match the header exactly, positions span about 1.8 x 2.5 x 1.8 units, `opacity` spans -0.60..0.61 (raw, pre-sigmoid), so the data is sane.
- Cactus SPZ: Innerscene's conversion of the same CC0 scene, https://www.innerscene.com/tools/library/3d-parts/cactus-spz-b02130a8 (page says CC0-1.0, credits steam studio, "converted to SPZ by Innerscene"). Direct file: https://www.innerscene.com/samples/gsplat-cactus.spz. It has 452k points, so it is a different training run than the 139k PLY, not a conversion of it. The CC0 claim is Innerscene's relabel of a verified CC0 source: secondhand, fine for CC0.
- Measuring tape, potted plant, armchair: splats trained by DX.GL from Poly Haven CC0 models, listed by Innerscene as CC0-1.0 (pages under https://www.innerscene.com/tools/library/3d-parts/, files at https://www.innerscene.com/samples/<name>). The upstream dataset https://huggingface.co/datasets/dxgl/multiview-datasets carries `license:cc0-1.0` in its Hugging Face metadata (verified via the HF API). Innerscene is the only host of the trained splats found, so provenance is one hop.
- Same Innerscene set also has `gsplat-cash-register.spz` (47,819 points), `gsplat-led-light-bulb.spz` (46,290), `gsplat-multi-cleaner.spz` (35,478), plus `.ksplat` files; all CC0, all under 1 MB.

## Gaps and things to know

- **No small CC0/CC-BY scene exists that I could verify.** Sample 1 is a cactus object. If a scene is wanted, make one with the app itself later (capture -> train) and bundle that; do not fall back to Mip-NeRF 360 or INRIA scenes (research or non-commercial terms).
- Sample 1 is over the 15 MB cap. Trim it, which CC0 allows. The 3DGS convention stores `f_rest` planar (R coefficients 0-14, G 15-29, B 30-44). Keeping band 1 only (`f_rest_0-2, 15-17, 30-32`) gives 23 floats per splat, 12.8 MB; SH0 only gives 14 floats, 7.8 MB. Alternatively decimate to about 50k splats with `splat-transform` (MIT, https://github.com/playcanvas/splat-transform). Whatever is done, document it in ASSETS-LICENSES.md and keep one file with `f_rest_*` so the SH path gets tested.
- Rejected, with reason: Niantic's `samples/hornedlizard.spz` 18 MB and `racoonfamily.spz` 24 MB (https://github.com/nianticlabs/spz/tree/main/samples; repo is MIT but the assets are too big and have no asset licence); Spark's example splats on sparkjs.dev (code MIT, no asset licence, food scans from third parties); SuperSplat scenes (the licence menu is CC BY and stricter, no CC0, and no stable download API: https://blog.playcanvas.com/new-in-supersplat-downloadable-splats-licenses-and-social-links); ShapeSplats/ModelNet (cc-by-nc); Hugging Face re-uploads of INRIA scenes (unclear provenance); Pixel Lab "Ancient" pack (SPZ, no licence stated, newsletter gate); Sketchfab Scaniverse scans (CC-BY but login needed to download); khyron/Gaussian-Splatting (CC-BY repo licence, no files in Releases); Khronos `KHR_gaussian_splatting` has no sample assets I could find yet.
- The SPZ in sample 3 is SH0 only. A full-SH SPZ would have to be made from sample 1 with Niantic's MIT `spz` CLI (`cli_tools` in https://github.com/nianticlabs/spz); optional.
- The old `app/src/main/assets/models/Goat skull.ply` is the only goat asset; a grep found no other file or text naming it, but the builder should still grep the Kotlin for `models/` and the file name when deleting.

## Recommendation

Bundle samples 1 (trimmed to band 1, about 12.8 MB, or decimated), 2 and 3 under `app/src/main/assets/samples/`, with `ASSETS-LICENSES.md` giving for each the source URL, author (steam studio / 3D SCAN STUDIO iris; DX.GL from Poly Haven models; SPZ converted by Innerscene), and "CC0 1.0". Add the `.splat` pair only if the importer supports that format. The builder must download the 1.45 GB Box zip once (stream it to a temp dir, extract only the one PLY with `python3 -I`) to produce sample 1; if that is too heavy, use sample 2 plus 3 as the minimum and note that sample 1 is missing. For the unit-test fixture, generate a handful of splats with known values by a script in the repo, as the card says. The "small scene" part of the card cannot be met with verified licences today; ship objects only and open a follow-up to capture one with the app.
