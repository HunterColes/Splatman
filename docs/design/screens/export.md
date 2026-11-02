# Export sheet

A modal bottom sheet over the Viewer (a dialog, max 560 dp wide, at 600 dp and up). Also reached from the Library's
selection mode (Share). Always dark when over the Viewer. Copy keys: `exp_*`.

## Layout (phone, 360 dp)

```
┌──────────────────────────────┐
│            ───               │  drag handle
│  Share or save               │  titleMedium
│                              │
│  (●) PLY (standard 3DGS)     │  radio rows, 56 dp+, whole row tappable
│      Opens in most splat ... │  bodySmall, onSurfaceVariant
│      About 38 MB             │  bodySmall
│  ( ) .splat (compact)        │
│      Smaller, loads fast ... │
│      About 9 MB              │
│                              │
│  [⇧ Share ]  [⤓ Save to device]  two buttons, equal width: Share filled, Save tonal
└──────────────────────────────┘
```

- The selected format starts at Settings > Default export format (PLY out of the box).
- File name: the scan name made filesystem-safe, plus the extension (`Desk plant.ply`).
- **Share** writes to app cache and opens the Android share sheet through a `FileProvider` URI.
- **Save to device** opens the system save dialog (`ACTION_CREATE_DOCUMENT`) with the name prefilled; no storage
  permission.
- Multi-select from the Library: the title becomes "Share 3 scans", sizes show the total, Save becomes "Save to
  folder" (`ACTION_OPEN_DOCUMENT_TREE`).

## States

| State | What shows |
|---|---|
| Choose | as above |
| Writing | buttons replaced by a linear progress bar and `exp_writing`; the sheet cannot be dragged closed; system Back cancels the write |
| Shared | sheet closes when the share sheet opens; nothing else (Android shows its own result) |
| Saved | sheet closes; snackbar `exp_saved` on the Viewer |
| Error | the buttons return; an `errorContainer` row above them with `exp_error` |
| Low storage | before writing: the button row is disabled and `exp_low_storage` shows in `error` text under the size |

## Accessibility

The radio group is one focusable group with each option reading its name, description and size. Sizes use
the system's localized file size formatter.
