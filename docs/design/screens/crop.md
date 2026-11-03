# Crop mode (Viewer)

A mode of the [Viewer](viewer.md), not a route: one axis-aligned box over the splat, six face handles, a nudge panel
and a button row. Everything outside the box dims live; Apply cuts it away and saves. It is not an editor: one box,
no rotate handle, no paint, no layers, no undo stack (Revert crop brings back the original). Always dark, like the
Viewer. Copy keys: `crop_*` ([copy.md](../copy.md#crop)). Data shapes and the file order are in the crop ADR (#180);
this page covers only what the user sees, touches and feels.

## Entry: the Crop action

- **Where**: the Viewer's bottom bar, a third icon button after Reset view and Auto-rotate:
  `(⟲) (360) (⌗)   [ Share ]`. Icon `Icons.Rounded.Crop` (Material Symbols, from `material-icons-extended`, already
  a dependency; no new asset), 48 dp (`TouchTarget`), content description `crop_action`. At 360 dp the bar holds
  three 48 dp icons with `SpacingSmall` gaps, `SpacingDefault` edge margins and the Share button with room to spare.
- **Enabled** only in the Viewer's Ready state (not in Loading, Open error or while the export sheet is open).
- **On tap**: auto-rotate stops (and stays off after), the gesture hint is dismissed, the top and bottom bars
  crossfade (`DurationShort`) into the crop chrome below, and the box appears at the start box. The camera does not
  move. Single tap no longer hides chrome while in crop mode.
- **Start box**: per-axis 5th..95th percentile of splat centres (the central 90%), for every source, aligned to the
  world axes W (+Y up). Reset box returns to exactly this box.

## Layout (phone portrait, 360 dp)

```
┌──────────────────────────────┐
│ (×) Crop                     │  top bar on the OverlayScrim gradient: Close (= Cancel), title
│      ( 42 × 30 × 25 cm )     │  size pill: OverlayScrim, CornerSmall, bodyMedium tabular, OverlayOnScrim
│                              │
│        ●─────────────┐       │  the box: 12 edges, 2 dp primary with a 1 dp scrim outline either side
│       /│      ●     /│       │  ● = face handle at each face centre (6), 24 dp visible, 48 dp touch
│      ┌─┼───────────┐ │       │
│      │ ●   splat   │ ●       │  splats outside the box: alpha × CROP_DIM_ALPHA (0.2)
│      │ └───────●───┼─┘       │
│      └─────────────┘         │
│                              │
│  Drag a dot to move a side.  │  crop_hint, bodyMedium, OverlayOnScrim, on the bottom scrim
│  [Top][Bottom][Front][Back]… │  face picker: single-select FilterChip row, scrolls sideways
│  [ − In ]        [ Out + ]   │  nudge: two tonal buttons, equal width, 48 dp tall
│  Reset box   [Cancel] [Apply]│  text button left; outlined Cancel and filled Apply right
└──────────────────────────────┘
```

The bottom panel sits on a bottom-to-transparent `OverlayScrim` gradient (as the Viewer's bottom bar), padded
`SpacingDefault` at the sides and above the navigation bar inset, rows `SpacingSmall` apart. Every button is at
least 48 dp tall with a text label (`labelLarge`); none is icon-only except Close, which has a content description.

## The box

- **Look**: twelve edges, 2 dp `primary` lines, each with a 1 dp `scrim` outline on both sides, so an edge holds
  3:1 against a white or a black splat. Drawn on top of the splat with no depth test (the far edges stay visible;
  a wireframe, no face fill). Drawn in the Compose overlay from the projected corners, not in the GL shader.
- **Handles**: one per face, at the face centre projected to the screen. Visible: a 24 dp circle filled `primary`
  with a 2 dp `scrim` ring. Touch target: 48 dp (`TouchTarget`) centred on it. A handle whose face points away from
  the camera is drawn as a 24 dp `primary` stroke ring with no fill, and is still draggable. Where two touch targets
  overlap, the handle nearer the camera wins.
- **Selected**: the face picked in the chip row (or last dragged) has a 4 dp `onPrimary` inner dot in its handle,
  and its four edges draw at 4 dp. One face is always selected; Bottom on entry.
- **Dragging**: touching a handle selects its face; the handle grows to 32 dp (`DurationShort`, `EasingStandard`)
  and the face's four edges draw at 4 dp. The face moves along its own world axis by the finger's travel projected
  on that axis's screen direction; the opposite face stays fixed. The extent never goes under 1 cm, and a face never
  moves past the cloud's full bounding box (the min and max of all centres), so the box cannot be lost off screen.
  At either limit the face stops and the phone gives one `REJECT` buzz per drag. The size pill updates every frame.
  Lift the finger: handle back to 24 dp.
- **Touching off a handle**: the Viewer's gestures as usual: one finger orbits, two pan, pinch zooms, double-tap
  resets the view (not the box). A fling does not continue while a handle is held.

## Live dim

Splats whose centre is outside the box are drawn with their alpha multiplied by **`CROP_DIM_ALPHA = 0.2`**, one
renderer constant beside the box uniform (no re-pack, no re-sort). Inside, splats are drawn as normal. The inside
test is the same as the crop function's (centre-inside, boundary inclusive), so what looks kept is what is kept.

## Nudge: the alternative to every drag

- **Face picker**: a single-select row of six `FilterChip`s in this order: Top, Bottom, Front, Back, Left, Right
  (`crop_face_*`). Selected chip: `primaryContainer` fill with a check. The row scrolls sideways when it does not fit
  (large font, 360 dp); the selected chip is scrolled into view.
- **In / Out**: two tonal buttons (`crop_in`, `crop_out`, with `Icons.Rounded.Remove` / `Icons.Rounded.Add` as
  leading icons). Each tap moves the selected face **1 cm** toward the centre (In) or away from it (Out), along its
  axis, with the same 1 cm and bounding-box limits as a drag. Holding a button repeats at the platform key-repeat
  rate (`ViewConfiguration.getKeyRepeatTimeout` then `getKeyRepeatDelay`). At a limit the button that would cross it
  is disabled (In at 1 cm, Out at the bounding box) and TalkBack announces `crop_limit`.
- **Keyboard / D-pad**: everything is focusable in the focus order below; Enter or Space activates; In and Out
  repeat while the key is held.

## Button row

| Button | Style | Does |
|---|---|---|
| Reset box (`crop_reset`) | text button | the box returns to the start box; the camera does not move; Bottom stays selected if it was |
| Cancel (`crop_cancel`) | outlined | leaves crop mode, the box is dropped, nothing is saved; no confirm (nothing is lost but a box) |
| Apply (`crop_apply`) | filled `primary` | crops the loaded scan to the box and saves (see states) |

Close (×) in the top bar and system **Back** do exactly what Cancel does.

## States

| State | What shows |
|---|---|
| Editing | as above: box, handles, dim, size pill, hint, picker, nudge, button row |
| Applying | the picker, nudge and button rows are replaced by a linear indeterminate progress bar (`primary` on `surfaceContainerHighest`) and `crop_applying`; handles hide; every gesture on the render is ignored, the box stays drawn and the dim stays; Back and Close do nothing (an Apply runs to the end so the file is never half written). TalkBack announces `crop_applying` |
| Refused: empty | the result would hold no splats: nothing is written; back to Editing with the box unchanged and one line above the button row in an `errorContainer` row (`onErrorContainer` text, `CornerSmall`): `crop_empty`; `REJECT` buzz; the line goes when the box next changes |
| Nothing cut | the result keeps every splat: nothing is written, crop mode closes, snackbar `crop_nothing`; no Revert crop appears if there was none |
| Save error | writing failed (storage full, I/O): back to Editing, box kept, `errorContainer` row with `crop_error` |
| Applied | crop mode closes; the Viewer reloads its source through its usual Loading state (`view_loading`); then Ready with the cropped splat in the same camera pose, a `CONFIRM` buzz and the snackbar `crop_done` with the new splat count; the details sheet shows the new count and size; Revert crop is now in the overflow |
| Revert confirm | overflow > Revert crop opens a dialog: `crop_revert_title`, `crop_revert_body`, Cancel (text) and `crop_revert_confirm` (text, `primary`) |
| Reverting | the Viewer's Loading layout with `crop_reverting` instead of `view_loading`; Back works only after the restore finishes (the rename is instant; the reload is what shows) |
| Reverted | Ready with the original, snackbar `crop_reverted`; Revert crop leaves the overflow |
| Revert error | stays on the current splat; snackbar `crop_revert_error` |

A second crop works on the cropped result: entering Crop again starts from the central 90% of the current splat.

## Revert crop

- **Where**: the Viewer's top-bar overflow, after Rename and before Delete: Rename, Revert crop, Delete.
- **When**: only while an original is kept: a scan that has `splat.uncropped.ply`, or a sample that has a file in
  `sample-crops/`. Never shown for a scan never cropped. Imports are scans, so they behave the same.
- It always goes back to the original capture or file, not to the previous crop (the body text says so).

## Rotation, resize, process

- **Rotate or resize during Editing** (orientation, split screen, fold): the box, the selected face, the camera
  pose and any `errorContainer` line are kept (ViewModel state); the panel re-lays out for the new size.
- **During Applying**: the crop keeps running (it is not tied to the composition); the new layout shows Applying.
- **Process death during Editing**: the box is lost; the Viewer reopens in Ready. During Applying the file is
  either the old or the new one (the ADR's temp-and-rename order).
- **App to background during Editing**: comes back in Editing with the same box.

## Wide and landscape

- **Width 600 dp and up, or landscape on a phone** (height under 480 dp): the bottom panel moves to a side panel on
  the end edge, 360 dp wide, full height, on `OverlayScrim` (no gradient), padded `SpacingDefault`, rows in the same
  order (hint, picker as a wrapping chip group, nudge, button row). The size pill stays top-centre of the remaining
  render area. The box and handles use the remaining area, so the panel never covers a handle.
- Respects cutouts and system bar insets, as the Viewer's bars do.

## Long content and large text

- The scan name is not shown in crop mode (the title is `crop_title`), so long names cannot crowd it.
- Size pill: under 1 m an axis shows whole cm (`42 cm`); from 1 m it shows metres with 2 decimals (`1.20 m`);
  each axis keeps its own unit (`1.20 m × 30 cm × 25 cm`). Tabular figures (`fontFeatureSettings = "tnum"`) so it
  does not jitter while dragging.
- At 200% font: the hint wraps to two lines; the chip row scrolls; when the button row does not fit on one line,
  Reset box moves to its own row above Cancel and Apply (Cancel and Apply never split). Nothing clips.
- A huge scan the renderer decimates ("lighter version") is cropped in full: the crop runs on the loaded cloud,
  not the decimated draw list.

## Haptics and sound

`CONFIRM` when a crop is saved; `REJECT` on an empty-result refusal and once per drag at a limit. Both off with
Settings > Haptics. No sound.

## Accessibility

- **Face names** come from the Viewer's **initial view** (the front three-quarter view the thumbnail uses), so a
  scan's faces have the same names every time: +Y is Top and -Y is Bottom. Of the four horizontal W axis directions
  (+X, -X, +Z, -Z), the one nearest the direction from the box centre to the initial camera, flattened onto the
  ground, is Front, its opposite is Back; the one nearest the initial camera's right vector is Right, its opposite is
  Left. Names do not change as the user orbits.
- **Handles** are semantics nodes over the render with content description `crop_face_*_desc` ("Top face"), state
  "Selected" on the selected one, a click that selects that face, and two custom actions `crop_in` and `crop_out`
  ("Move in", "Move out" read with the face name, `crop_in_desc` / `crop_out_desc`). Hidden from TalkBack while
  Applying.
- **Size pill**: content description `crop_size_desc` ("Box 42 cm wide, 30 cm deep, 25 cm tall"; wide is Left to
  Right, deep is Front to Back, tall is Bottom to Top), a polite live region, announced at most once per second
  while it changes.
- **Buttons**: Close `crop_cancel`; chips read their name, "Selected" and the group `crop_face_picker`; In and Out
  read `crop_in_desc` / `crop_out_desc` with the selected face ("Move Top face in 1 cm"); Reset box, Cancel and Apply
  read their labels.
- **Render**: content description `crop_render_desc`; the Viewer's orbit and zoom custom actions still work.
- **Focus order**: Close, size pill, the six handles (Top, Bottom, Front, Back, Left, Right), the render, face
  chips (same order), In, Out, Reset box, Cancel, Apply. On entry, focus goes to the Bottom chip. After Applied,
  focus goes to the snackbar, then the Viewer's usual order. The error row is read when it appears (polite).
- **Contrast**: `OverlayOnScrim` on `OverlayScrim` 5:1 or more over any splat; `onErrorContainer` on
  `errorContainer`, `onPrimary` on `primary` and chip colours are the M3 pairs from [tokens.md](../tokens.md); box
  edges and handles hold 3:1 through their `scrim` outline.
- **Remove animations**: the handle grow and the chrome crossfade become instant.
