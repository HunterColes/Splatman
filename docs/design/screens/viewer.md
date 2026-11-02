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
│  (⟲)   (360)        [ Share ]│  bottom bar on a bottom gradient: Reset view, Auto-rotate
└──────────────────────────────┘  toggle (icon buttons 48 dp), Share as a filled button
```

- **Top bar**: Back, the scan name (`titleLarge`, one line, ellipsis; tap to rename), Details (Info icon), overflow
  (Rename, Delete).
- **Bottom bar**: Reset view (`view_reset`), Auto-rotate toggle (`view_autorotate`, filled when on, off by default),
  Share (`view_share`) opens the [export sheet](export.md).
- **Gestures**: one finger drag orbits around the scan's centre; two fingers pan; pinch zooms (clamped so the scan
  cannot vanish or be passed through); double-tap animates back to the initial view (`DurationLong`,
  emphasized); single tap toggles chrome. Fling continues the orbit with friction.
- **Initial view**: the front three-quarter view the thumbnail uses, framed so the scan fills about 70% of the short
  edge.
- **Chrome hidden**: bars fade out (`DurationShort`), system bars hide (immersive, swipe to reveal). Chrome never
  auto-hides; only the user's tap hides it.
- **Wide / landscape**: same bars; content description and layout unchanged; bars respect cutouts.

The old axis widget and scene info overlay are developer tools: not shown in v1.

## States

| State | What shows |
|---|---|
| Loading | `ViewerBackground`, centred determinate circular progress (bytes read / file size) and `view_loading`; Back works |
| Ready | the render and chrome |
| Gesture hint | first open of any scan only: a bottom card above the bar with three icon rows for `view_hint` and a "Got it" text button; dismissed for good by the button or by any gesture |
| Details sheet | modal bottom sheet (`surfaceContainer`, `CornerLarge`): name, date, `view_info_splats`, `view_info_size`, `view_info_frames` (or `view_info_imported`), and the format. Text only, selectable |
| Rename | dialog with an outlined text field prefilled and selected, Cancel and Save; Save disabled when blank; max 80 characters with a counter past 60 |
| Delete confirm | dialog `view_delete_title` with the same body as the Library's, Cancel and Delete (`error`); after delete, back to Library with an Undo snackbar |
| Open error | centred ErrorOutline in `error`, `view_error_title`, `view_error_body` (or `view_error_memory`), button "Back to library" |
| Huge scan | renders, and a one-time snackbar "Large scan: showing a lighter version" if the renderer decimates (the renderer ticket decides when) |

## Accessibility

Every gesture has a button: orbit and zoom are available as TalkBack custom actions on the render ("Rotate left",
"Rotate right", "Tilt up", "Tilt down", "Zoom in", "Zoom out", "Reset view"), and the D-pad / arrow keys orbit, `+`/`-`
zoom, `0` resets. The render's content description is "3D view of Desk plant, 1.2 million splats". The chrome is
not hidden while TalkBack is on.
