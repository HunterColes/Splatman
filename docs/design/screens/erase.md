# Erase mode (Viewer)

A mode of the [Viewer](viewer.md), beside [crop mode](crop.md): paint over floaters and stray splats with one
finger, see them marked, Apply to cut them away and save. It is not an editor: one brush, three sizes, one step of
Undo, no layers, no keep paint. Always dark, like the Viewer. Copy keys: `erase_*` ([copy.md](../copy.md#erase)).
The hit rule, mask, undo depth and file order are fixed by ADR 0014 (epic #322); this page covers only what the user
sees, touches and feels, and never contradicts the ADR.

## Entry: the Erase action

- **Where**: the Viewer's bottom bar only, a fourth icon button after Crop: `(⟲) (360) (⌗) (✎)   [ Share ]`.
  Icon `Icons.Rounded.Brush` (Material Symbols, `material-icons-extended`, already a dependency; no new asset),
  48 dp (`TouchTarget`), content description `erase_action`. It is **not** offered inside crop mode.
- **Fit at 360 dp**: 360 − 2 × 16 (`SpacingDefault` margins) = 328 dp; four 48 dp icons with three `SpacingSmall`
  gaps take 216 dp, a `SpacingSmall` gap before Share leaves 104 dp for the filled Share button (about 92 dp at 100%
  font). When Share's label does not fit (large font), Share becomes a 48 dp `FilledIconButton` with
  `Icons.Rounded.Share` and content description `view_share`; the four icons never shrink below 48 dp.
- **Enabled** only in the Viewer's Ready state (not in Loading, Open error, crop mode or while the export sheet is
  open), as Crop.
- **On tap**: auto-rotate stops (and stays off after), the gesture hint is dismissed, the top and bottom bars
  crossfade (`DurationShort`) into the erase chrome below. The camera does not move. Single tap paints a dot; it no
  longer hides chrome.
- **An unapplied crop box**: there is none to handle. Erase opens only from the Viewer bar, and crop mode replaces
  that bar, so the user leaves crop mode first: Apply saves the box, Cancel, Close or Back drop it (crop's own rules).
  A crop and an erase never share an Apply, and no box ever waits across a switch.
- **The other way**: the Viewer bar (and so Crop) is hidden in erase mode; Apply, Cancel, Close or Back leaves erase
  mode first. Unapplied marks never carry into crop mode.

## Layout (phone portrait, 360 dp)

```
┌──────────────────────────────┐
│ (×) Erase                    │  top bar on the OverlayScrim gradient: Close (= Cancel), title
│      ( 1,240 splats marked ) │  count pill: OverlayScrim, CornerSmall, bodyMedium tabular, OverlayOnScrim
│                              │
│      ░░░▒▒▒▒                 │  marked splats: the marked look (below)
│         ▒▒▒▒▒◯               │  ◯ = brush ring under the finger; ▒ = the stroke trail
│        [ splat render ]      │
│                              │
│ Paint over what to remove.   │  erase_hint, bodyMedium, OverlayOnScrim, on the bottom scrim
│ [ Small | Medium | Large ]   │  brush size: SegmentedButton, single-select, 48 dp tall
│ [✦ Remove isolated splats  ] │  tonal button, full width, 48 dp
│ (↶ Undo)      [Cancel][Apply]│  text button left; outlined Cancel and filled Apply right
└──────────────────────────────┘
```

The bottom panel is crop's: a bottom-to-transparent `OverlayScrim` gradient, padded `SpacingDefault` at the sides
and above the navigation bar inset, rows `SpacingSmall` apart. Every button is at least 48 dp tall with a text label
(`labelLarge`); none is icon-only except Close, which has a content description.

## Brush

- **Shape**: a circle fixed on screen (ADR 0014 rule 1): zoom does not change it. The user zooms in for fine work.
- **Sizes** (diameter, dp; the ADR's radius is half, converted to surface pixels with the display density):

  | Size | Diameter | For |
  |---|---|---|
  | Small (`erase_size_small`) | 32 dp | thin floaters beside the subject; about a fingertip |
  | Medium (`erase_size_medium`) | 56 dp | the default; about a thumb pad, phone portrait |
  | Large (`erase_size_large`) | 96 dp | clearing sky, floor or a wide haze |

- **Picking**: a three-segment M3 `SingleChoiceSegmentedButtonRow` (`erase_size_picker`), segments equal width,
  48 dp tall, the selected one `secondaryContainer` with a check. Medium on first entry; the last pick is kept while
  the app process lives. On a pick, a ring of the new size shows at the centre of the render for `DurationLong`, then
  fades out (`DurationShort`), so the size is seen before painting.
- Constants live in one place in the Viewer UI (`EraseBrush`: 32, 56, 96 dp); tuning them is not a spec change.

## Gestures

- **One finger** paints a stroke from touch-down to lift. A single tap marks one brush circle.
- **Two fingers** orbit-pan-zoom as in the Viewer (room scans: look, slide, step, as [viewer.md](viewer.md#room-scans)).
  A second finger landing during a stroke ends the stroke there, keeps its marks, and from then on the gesture is a
  camera gesture until every finger lifts. The camera never moves during a stroke.
- One-finger orbit, fling and double-tap reset are off in erase mode (one finger paints). Reset view is not in the
  erase chrome; two fingers and pinch move the camera.
- Painting near a screen edge: the render sets `systemGestureExclusion` on its lower and side edges within the
  platform's 200 dp limit, so a stroke near the edge is not taken as system Back. Back from the edge still works
  above that strip.

## Stroke preview

- **Brush ring**: while a finger is down, a circle of the brush diameter centred on the touch point: 2 dp
  `OverlayOnScrim` line with a 1 dp `scrim` outline both sides (3:1 on white or black splats, as crop's box edges).
  For Small the ring is mostly under the fingertip; that is accepted (it is shown on picking).
- **Trail**: the stroke's capsule (the polyline at brush width) filled `primary` at 25% alpha, drawn in the Compose
  overlay from the touch points, under the ring. It stays while the finger is down and fades out (`DurationMedium`)
  after lift, by when the hit pass has drawn the marked look underneath.
- The marked look appears as passes land (ADR 0014 rule 2: one pass in flight, points queue), so it can trail the
  finger by a frame or two on a large scan; the trail covers that gap.

## The marked look

Marked splats (mask 1 or 2) draw from two renderer uniforms, set once:

- **`ERASE_TINT = #FF3D71`** (a hot pink no scan colour is likely to be, unlike the violet `primary`), mixed into the
  splat's colour at **`ERASE_TINT_MIX = 0.6`**.
- **`ERASE_ALPHA = 0.5`**: the splat's alpha multiplied by 0.5.

On a white splat the result reads as pale pink, on a black one as dark rose; both differ from crop's grey dim
(`CROP_DIM_ALPHA = 0.2`, no tint) and from unmarked splats. Last-stroke and earlier marks look the same (Undo says
what it will clear). Unmarked splats draw as normal.

## Button row and count

| Control | Style | Enabled | Does |
|---|---|---|---|
| Undo (`erase_undo`, `Icons.AutoMirrored.Rounded.Undo`) | text button | a last stroke exists (mask holds 2s) and no Applying | clears the last stroke's marks (ADR rule 6); then disabled until the next stroke |
| Remove isolated splats (`erase_isolated`, `Icons.Rounded.AutoFixHigh`) | tonal, full width | not Applying, not already running | marks isolated splats as one stroke (ADR rule 8); it only marks |
| Cancel (`erase_cancel`) | outlined | not Applying | leaves erase mode, every mark dropped, nothing saved; no confirm (the button says what it does) |
| Apply (`erase_apply`) | filled `primary` | at least one splat marked, no pass in flight | cuts the marked splats and saves (see states) |

**Apply is disabled while nothing is marked** (ADR rule 7): no tap does nothing silently.

**Count pill** (top centre, as crop's size pill): `erase_count` (plural: "1 splat marked", "1,240 splats marked"),
localized grouping, tabular figures. Hidden while nothing is marked (the hint is the message then). Updates when a
pass lands.

## States

| State | What shows | Enabled |
|---|---|---|
| Nothing marked (entry, or after Undo of the only stroke) | `erase_hint`; no count pill | size, isolated, Cancel; Undo and Apply off |
| Painting | finger down: ring and trail; marks land as passes finish; pill counts up; hint stays | size picker off while a finger is down; buttons stay as before the stroke |
| Marked | count pill; hint changes to `erase_hint_marked` | all four (Undo while the last stroke has marks) |
| Finding isolated | the isolated button shows a 24 dp circular indeterminate progress in place of its icon and reads `erase_isolated_running`; painting is ignored until it ends | Cancel only |
| Isolated none | the pass found nothing: no stroke recorded (Undo unchanged); snackbar `erase_isolated_none` (TalkBack reads it) | as before the pass |
| Applying | the size, isolated and button rows are replaced by a linear indeterminate progress bar (`primary` on `surfaceContainerHighest`) and `erase_applying`; the marked look stays; every touch on the render is ignored; Back and Close do nothing (the file is never half written). TalkBack announces `erase_applying` | none |
| Refused: all marked | every splat is marked: nothing is written; back to Marked with marks kept; an `errorContainer` row above the button row (`onErrorContainer` text, `CornerSmall`): `erase_all`; `REJECT` buzz; the row goes on the next stroke or Undo | as Marked |
| Refused: save error | the store failed (storage full, I/O): back to Marked, marks kept, `errorContainer` row `erase_error` | as Marked |
| Applied | erase mode closes; the Viewer reloads through its Loading state (`view_loading`); then Ready in the same camera pose, a `CONFIRM` buzz and snackbar `erase_done` (removed count, new total); details sheet shows the new count and size; Revert edits is now in the overflow | Viewer |
| Leave confirm | Back or Close with marks: dialog `erase_discard_title`, `erase_discard_body`, `erase_keep` (text) and `erase_discard` (text, `error`) | dialog |

Cancel's button never confirms; Back and Close confirm only when marks exist (an edge swipe or a stray tap on ×
should not lose a minute of painting). With nothing marked, Back and Close leave at once.

A second erase works on the result: entering Erase again starts with nothing marked.

## Revert edits (one label for crop and erase)

ADR 0014 rule 7 keeps one original and one Revert, which undoes every crop and every erase together. The overflow
item and its dialog are renamed for both, always, whatever edits were made: after a crop only, an erase only, or a
crop plus an erase, the label is **"Revert edits"**. No variant label (counting edit kinds would need a record the
ADR does not keep).

Renamed `crop_*` texts (keys stay, so no code moves for the rename; the build card changes only the strings):

| Key | Was | Now |
|---|---|---|
| `crop_revert` | Revert crop | Revert edits |
| `crop_revert_body` | This undoes every crop on this scan, not just the last one. | This undoes every crop and erase on this scan, not just the last one. |

`crop_revert_title` ("Go back to the original?"), `crop_revert_confirm`, `crop_reverting`, `crop_revert_error` stay.
After Revert: Ready with the original, snackbar `crop_reverted` ("Original restored"), Revert edits leaves the
overflow; the same for a revert of an erase. Shown when an original is kept, as crop.md says (an erase-only scan
has `splat.uncropped.ply` too).

## Rotation, resize, process

- **Rotate or resize** (not Applying): marks, the last stroke, the brush size and the camera pose are kept
  (ViewModel); a stroke in progress ends at the rotation and keeps its marks.
- **During Applying**: Apply keeps running; the new layout shows Applying.
- **Process death**: marks are lost; the Viewer reopens in Ready. During Applying the file is the old or the new one.
- **App to background**: comes back with the same marks.

## Wide and landscape

As crop: at 600 dp and up, or a phone in landscape (height under 480 dp), the bottom panel moves to a 360 dp end-edge
side panel on `OverlayScrim`, rows in the same order (hint, size, isolated, button row); the count pill stays
top-centre of the render area; painting uses the remaining area. Insets and cutouts respected.

## Long content and large text

- The title is `erase_title`; the scan name is not shown, so long names cannot crowd it.
- Count pill: grouping separators, tabular figures, one line; at 200% font it may widen but stays one line
  (the longest is "8,000,000 splats marked").
- At 200% font: the hint wraps to two lines; the segment labels stay one line each (Small, Medium, Large are short;
  at 360 dp each segment is about 109 dp); when Undo, Cancel and Apply do not fit, Undo moves to its own row above
  (Cancel and Apply never split). Nothing clips.
- A huge scan the renderer decimates: marks and Apply act on the full loaded cloud (ADR rule 1 tests every centre);
  the draw shows the marked look on the splats it draws.

## Haptics and sound

`CONFIRM` when an erase is saved; `REJECT` on the all-marked refusal. Off with Settings > Haptics. No sound. No buzz
per stroke.

## Accessibility

- **Painting is not a TalkBack path.** With TalkBack on, the render does not paint on a double-tap-and-hold; the
  TalkBack path is Remove isolated splats (ADR rule 8), then Apply. The render's content description is
  `erase_render_desc` and its custom actions are the Viewer's orbit and zoom ones (orbit by action works here; only
  touch orbit is two-finger).
- **Labels**: Close reads `erase_close_desc` ("Close erase"); size segments read
  their label, "Selected" and the group `erase_size_picker`; Remove isolated reads its label (and
  `erase_isolated_running` while running); Undo reads `erase_undo_desc` ("Undo last stroke"); Cancel and Apply read
  their labels; a disabled Apply reads its label and "Disabled" (the hint says why).
- **Count pill**: polite live region, `erase_count`, announced at most once per second while it changes.
- **Focus order**: Close, count pill, the render, size segments (Small, Medium, Large), Remove isolated splats, Undo,
  Cancel, Apply. On entry focus goes to Remove isolated splats when TalkBack is on (the path that works), else to the
  render. After Applied, focus goes to the snackbar, then the Viewer's usual order. Error rows are read when they
  appear (polite).
- **Contrast**: `OverlayOnScrim` on `OverlayScrim` 5:1 or more over any splat; M3 pairs from
  [tokens.md](../tokens.md) for the segments, tonal and filled buttons and `errorContainer`; the brush ring holds 3:1
  through its `scrim` outline.
- **Keyboard / D-pad**: everything is focusable in the order above; Enter or Space activates. Painting needs touch
  (or a mouse drag, which is one pointer).
- **Remove animations**: the chrome crossfade, the size preview ring and the trail fade become instant.
