# ADR 0014: erase brush (hit rule, mask, undo, file order)

Status: accepted (epic #322, card #326). The spec and the four build cards of #322 share it.

## Context

[ADR 0011](0011-crop.md) cut floaters with one box and rejected a brush. Epic #322 takes the brush up: the user
paints splats away before exporting. The cards must agree on one hit rule, one mask, one undo depth and one file
order. Splatman is still a scanner: no layers, no redo.

## Decision

1. **Hit rule: centre under the brush, through the depth.** A stroke is a polyline of touch points in surface
   pixels. A splat is hit when its centre p, projected with the renderer's current view and projection (surface
   pixels, not the offscreen render scale), has clip w > 0 and lies within the brush radius (surface pixels) of the
   nearest stroke segment (a capsule test; a single tap is a circle). Every splat under the brush is hit, near or
   far. Centre only, as 0011 rule 2. The radius is fixed on screen (zoom does not change it); its sizes in dp come
   from the spec. Reason: exact, cheap on the CPU, no GPU readback, and it matches what the live dim shows; the
   user orbits to see and Undo a stroke that went through the subject.
2. **Camera and strokes.** One finger paints, two fingers move the camera; the camera never moves during a stroke
   (a second finger ends the stroke and keeps its marks). Hit tests run on the default dispatcher, at most one pass
   in flight, chunked over cores; touch points that arrive during a pass go into the next. Target: a pass over 8M
   splats in about 60 ms on a mid phone (qa measures).
3. **Mask, CPU.** `class EraseMask(val count: Int)` in splat-core `com.huntercoles.splatman.splat.edit`, backed by
   one `ByteArray(count)`: 0 = kept, 1 = marked by an earlier stroke, 2 = marked by the last stroke.
   `beginStroke()` turns 2 into 1; `mark(i)` turns 0 into 2 (1 stays 1); `undo()` turns 2 into 0 and reports
   whether anything changed; `marked` and `lastMarked` counts are kept as it goes; a `version: Int` grows on every
   change. Hit test: `fun markUnderBrush(positions: FloatArray, viewProj: FloatArray /* 16, column-major */,
   widthPx: Int, heightPx: Int, points: FloatArray /* x,y pairs */, radiusPx: Float, mask: EraseMask): Int`
   (splats newly marked). Apply: `fun keepUnmarked(cloud: SplatCloud, mask: EraseMask): SplatCloud`, two passes,
   input order, fresh arrays, like `cropToBox`.
4. **Mask, GPU.** `SplatRenderer.setEraseMask(mask: EraseMask?)`: an `R8UI` texture, width 2048, one texel per
   splat at (i % 2048, i / 2048), read in the vertex shader by `texelFetch` with the instance's splat index; a
   nonzero texel draws the marked look from uniforms (the spec sets alpha and tint), so painting is no re-pack and
   no re-sort ([ADR 0006](0006-rendering.md)). The GL thread re-uploads the whole texture when `version` changed,
   at most once a frame; it reads the shared bytes without a lock (a byte is written whole, so a torn frame shows a
   half-done pass and the next upload fixes it). `null` frees the texture and a uniform skips the fetch.
5. **Memory.** 1 B per splat on the CPU and 1 B on the GPU, only while erase mode is open: 16 MB at 8M splats
   (8M + 8M), beside ADR 0006's 36 B per splat (288 MB). No per-splat screen positions are stored. Apply's peak is
   a crop's (one new cloud).
6. **Undo depth one.** Undo clears the last stroke's marks (the isolated pass counts as one stroke) and is then
   off until the next stroke; no redo; Undo waits for a pass in flight to end. Cancel drops every mark. After
   Apply, only Revert.
7. **Files: 0011 rule 6 unchanged.** Apply runs `keepUnmarked` on the default dispatcher, then the existing
   `CropStore.apply(source, cloud)`: the first Apply of any edit (crop or erase) keeps `splat.uncropped.ply` and no
   later Apply overwrites it, so the one existing Revert restores the true original, undoing every crop and erase
   together (crop, then erase, then Revert gives back the first original). Samples follow rule 7,
   `ScanChangeListener` rule 8, reload rule 9. A crop and an erase never share one Apply: each mode applies on its
   own (the spec says how a switch treats an unapplied box). Apply is disabled while nothing is marked; marking
   every splat is refused like an empty crop, nothing written. Documents are not offered, as crop.
8. **Remove isolated splats** (the TalkBack path, and a one-tap clean for everyone):
   `fun markIsolated(cloud: SplatCloud, mask: EraseMask): Int` in splat-core, run as one stroke. Cell size
   s = longest extent of `CropBox.fitCentral(cloud)` / 64 (at least 1 mm); a splat is isolated when the 27 cells
   around its cell (its own included) hold fewer than 4 centres. Counts live in a dense `IntArray` over the
   fitCentral box grown by half its extent on each side (at most 128 cells per axis, 8 MB) and a
   `HashMap<Long, Int>` for cells outside it; two O(n) passes. It only marks: the user sees the dim, may Undo, then
   Applies. The constants sit in one object; tuning them is not an ADR change.

## Rejected

- **Front-most hit**: needs a depth or ID readback, GLES has no cheap per-splat pick, and soft Gaussians have no
  single front surface.
- **Keep/restore paint, layers, a sphere brush, multi-step undo**: an editor, not a scanner.
- **Storing the mask beside the file and applying it at load**: 0011's reason, every reader would re-apply it.
- **A separate `splat.unerased.ply`**: two originals and two Reverts, and crop then erase could not get back to
  the true original.

## Consequences

- [ADR 0006](0006-rendering.md) gains the mask texture (one sampler, one uniform).
- splat-core tests cover the capsule edge, w <= 0, the 2/1/0 states, `keepUnmarked` order and the isolated rule on
  a fixture.
- The FileCropStore test covers crop then erase then Revert giving back the first original.
