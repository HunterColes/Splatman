# Import

Not a screen of its own: a flow from the Library's Open-a-file button (and the empty state) through the system
picker into the Viewer. Also the target of "Open with" for `.ply` and `.splat` files from other apps (an intent
filter, so a file manager can hand a splat to Splatman). Copy keys: `imp_*`.

## Flow

1. Tap Open a file: the system picker (`ACTION_OPEN_DOCUMENT`, MIME `*/*`, since `.splat` has no registered type).
2. **Validating**: a modal, non-dismissable dialog with a linear progress bar and `imp_validating` (the file name).
   Back cancels. For small files this may flash: show it only if validation takes more than 300 ms.
3. Valid: the file is copied into app storage, a Library entry is made (name = file name without extension,
   "Imported" chip), and the Viewer opens with snackbar `imp_done`.
4. Invalid: the dialog turns into an error dialog with ErrorOutline, the message, and one "OK" button; the user
   stays in the Library.

## States

| State | Message |
|---|---|
| Point cloud PLY (has x, y, z and maybe colour, but no scale, rotation, opacity) | `imp_point_cloud` |
| Other extension or unknown header | `imp_unsupported` (with the extension) |
| Header fine, data truncated or NaNs | `imp_corrupt` |
| More splats than the device budget (renderer ticket sets it) | `imp_too_large` |
| Not enough space to copy | `exp_low_storage` wording reused |

## Accessibility

The validating dialog is announced; the error dialog takes focus on its message, then the OK button.
