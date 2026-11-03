# Viewer

Full-bleed splat render on `ViewerBackground`, always dark, any orientation. Chrome floats on scrims and hides on a
single tap. Copy keys: `view_*`.

## Layout (phone, 360 dp)

```
┌──────────────────────────────┐
│ (←) Desk plant         (ⓘ)(⋮)│  top bar on a top-to-transparent OverlayScrim gradient
│                              │
│                              │
│         [ splat render ]     │  GL surface, full screen behind everything
│                              │
│                              │
│                              │
│  (⟲)  (360)  (⌗)    [ Share ]│  bottom bar on a bottom gradient: Reset view, Auto-rotate
└──────────────────────────────┘  toggle, Crop (icon buttons 48 dp), Share as a filled button
```

- **Top bar**: Back, the scan name (`titleLarge`, one line, ellipsis; tap to rename), Details (Info icon), overflow
  (Rename, Revert crop, Delete; Revert crop only while a crop's original is kept, see [crop mode](crop.md#revert-crop)).
- **Bottom bar**: Reset view (`view_reset`), Auto-rotate toggle (`view_autorotate`, filled when on, off by default),
  Crop (`crop_action`) opens [crop mode](crop.md), Share (`view_share`) opens the [export sheet](export.md).
- **Gestures**: one finger drag orbits around the scan's centre; two fingers pan; pinch zooms (clamped so the scan
  cannot vanish or be passed through); double-tap animates back to the initial view (`DurationLong`,
  emphasized); single tap toggles chrome. Fling continues the orbit with friction.
- **Initial view**: the front three-quarter view the thumbnail uses, framed so the scan fills about 70% of the short
  edge.
- **Chrome hidden**: bars fade out (`DurationShort`), system bars hide (immersive, swipe to reveal). Chrome never
  auto-hides; only the user's tap hides it.
- **Wide / landscape**: same bars; content description and layout unchanged; bars respect cutouts.

The old axis widget and scene info overlay are developer tools: not shown in v1.

## Room scans

A scan whose bundle says `splatman_mode: "room"` opens from inside, where it was captured: orbiting a room from
outside shows the backs of its walls. Object scans, samples and imported files (no mode) open exactly as above.

- **Start pose**: the eye at the mean of the capture's camera centres, looking along the first keyframe's view
  direction, levelled (no roll, the first keyframe's pitch kept). Same field of view; no framing of the bounds.
- **Gestures**: one finger drag turns the view in place (look around: yaw about world up, pitch clamped short of
  straight up and down); two fingers slide the eye sideways and up or down; pinch steps forward or back along the
  view, clamped to the room's bounds so the eye cannot leave the room. Fling keeps turning with friction. Double-tap
  and Reset view animate back to the start pose (`DurationLong`, emphasized).
- **Auto-rotate**: a slow turn in place about world up at the eye, same speed as the orbit's.
- **Chrome**: unchanged; no room badge on the render. The bottom bar keeps the same buttons and order.
- **Gesture hint**: the same card with the room wording (`view_hint_room_look`, `view_hint_room_move`,
  `view_hint_room_walk`, same three icons). Shown on the first room scan opened, once, separately from the object
  hint: a user who has seen one has not learned the other.
- **Details sheet**: one more line after the frames, `view_info_room`.
- **Accessibility**: the render reads `view_a11y_render_room` ("3D view inside Living room, 1.2 million splats"), and
  the custom actions are renamed for what they do inside: "Turn left", "Turn right", "Look up", "Look down", "Step
  in", "Step back", "Reset view". Arrow keys turn, `+`/`-` step, `0` resets.
- **Crop**: crop mode keeps its own framing ([crop mode](crop.md)); leaving it returns to the room's pose.

## States

| State | What shows |
|---|---|
| Loading | `ViewerBackground`, centred determinate circular progress (bytes read / file size) and `view_loading`; Back works |
| Ready | the render and chrome |
| Gesture hint | first open of any scan only: a bottom card above the bar with three icon rows for `view_hint` and a "Got it" text button; dismissed for good by the button or by any gesture |
| Details sheet | modal bottom sheet (`surfaceContainer`, `CornerLarge`): name, date, `view_info_splats`, `view_info_size`, `view_info_frames` (or `view_info_imported`), the format, and `view_info_room` for a room scan. Text only, selectable |
| Rename | dialog with an outlined text field prefilled and selected, Cancel and Save; Save disabled when blank; max 80 characters with a counter past 60 |
| Delete confirm | dialog `view_delete_title` with the same body as the Library's, Cancel and Delete (`error`); after delete, back to Library with an Undo snackbar |
| Open error | centred ErrorOutline in `error`, `view_error_title`, `view_error_body` (or `view_error_memory`), button "Back to library" |
| Huge scan | renders, and a one-time snackbar "Large scan: showing a lighter version" if the renderer decimates (the renderer ticket decides when) |

## Accessibility

Every gesture has a button: orbit and zoom are available as TalkBack custom actions on the render ("Rotate left",
"Rotate right", "Tilt up", "Tilt down", "Zoom in", "Zoom out", "Reset view"), and the D-pad / arrow keys orbit, `+`/`-`
zoom, `0` resets. The render's content description is "3D view of Desk plant, 1.2 million splats". The chrome is
not hidden while TalkBack is on.
