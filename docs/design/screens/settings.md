# Settings and About

Reached from the Library overflow. Follow the system theme (or the Theme setting). Copy keys: `set_*`, `about_*`.

## Settings layout (phone, 360 dp)

A `LargeTopAppBar` ("Settings", Back) over a scrolling list of M3 list items, grouped with `titleSmall` headers in
`primary`. Content max 840 dp wide at 600 dp and up.

| Group | Item | Control |
|---|---|---|
| Appearance | `set_theme` | opens a dialog with three radio rows: `set_theme_system` (default), `set_theme_dark`, `set_theme_light`. The current value is the item's supporting text |
| | `set_haptics` | switch, on by default |
| Scanning | `set_keep_frames` | switch, on by default; supporting text `set_keep_frames_body` |
| | `set_tips` | plain item, opens Onboarding at page 2 |
| Export | `set_export_format` | dialog with PLY / .splat radio rows |
| Storage | `set_storage` | supporting text `set_storage_body`, no action |
| | `set_clear_frames` | plain item with `set_clear_frames_body`; confirm dialog, then a snackbar "Freed 1.2 GB" |
| | `set_about` | plain item, opens About |

Switch rows: the whole row toggles the switch (one 56 dp target), the switch is not a second focus stop.

## About layout

Top bar "About", Back. A centred block: app icon 72 dp, "Splatman", `about_version`, `about_tagline`; then list
items:

- `about_privacy` as a non-interactive item with a Lock icon (this is the promise; it belongs on screen).
- `about_source`: opens the public repository in the browser (an intent; the app has no internet permission).
- `about_licences`: a screen listing each open-source dependency with its licence, from a generated file
  (the builder picks the generator; no new runtime dependency).
- `about_samples`: one item per bundled sample (from #5) with its author, source link and licence.

## States

| State | What shows |
|---|---|
| Default | as above |
| Storage counting | `set_storage` supporting text shows "Counting…" until known |
| Nothing to clear | `set_clear_frames` disabled with supporting text "No frames kept" |
| Storage empty | 0 scans: `set_storage` supporting text shows `set_storage_empty` "No scans yet"; otherwise the `set_storage_body` plural ("1 scan", "3 scans") |
| Long text / 200% font | list items grow to fit; nothing truncates |
| Licences loading | while the licence file is read, a centred `CircularProgressIndicator` under the top bar |
| Licences missing | asset missing or empty: one centred `bodyLarge` line `about_licences_missing`, `onSurfaceVariant` |
| No sample credits | the About "Sample scans" `GroupHeader`, its divider and rows are hidden |
| No browser | a browser row that nothing can open shows the snackbar `about_no_browser` |

Browser rows (Source code, sample credits with a source, each licence with a URL) carry a trailing
`Icons.AutoMirrored.Rounded.OpenInNew` (decorative, `onSurfaceVariant`) and the click label `about_open_link`, so
TalkBack says "double tap to open in browser". Rows with no link get neither. "Open-source licences" opens an in-app
screen, so it gets no icon.
