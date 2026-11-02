# Library (home)

The start destination. Follows the system theme. Copy keys: `lib_*`.

## Layout (phone, 360 dp)

```
┌──────────────────────────────┐
│ Splatman          [⇪] [⋮]    │  TopAppBar (large on first screen, collapses on scroll)
├──────────────────────────────┤  ⇪ = Open a file (FileOpen), ⋮ = Settings, About
│ ┌───────────┐ ┌───────────┐  │
│ │ thumbnail │ │ thumbnail │  │  LazyVerticalGrid, adaptive min 160 dp, 12 dp gutters,
│ │   4:5     │ │ ▓▓ 42%    │  │  16 dp edge margin; thumbnail 4:5, CornerMedium
│ ├───────────┤ ├───────────┤  │
│ │Desk plant │ │Scan 1 Nov │  │  titleMedium, 2 lines max, ellipsis
│ │1 Nov·38 MB│ │Building…  │  │  bodySmall, onSurfaceVariant
│ └───────────┘ └───────────┘  │
│                              │
│                 [◈  Scan ]   │  ExtendedFloatingActionButton, primary, bottom end
└──────────────────────────────┘
```

- **Card**: `surfaceContainerLow`, `CornerMedium`. Thumbnail is a still rendered when processing finishes (front
  three-quarter view on `ViewerBackground`). Below it: name, then "date · size". The whole card is one 48 dp+ target;
  tap opens the Viewer.
- **Order**: newest first. No sort or search in v1.
- **FAB**: extended ("Scan") at the top of the list; shrinks to the icon-only FAB once the user scrolls down, grows
  back on scroll up. Content description "Start a scan".
- **Wide (600 dp+)**: grid gets more columns (adaptive), edge margin 24 dp, content max 840 dp centred; the FAB stays
  bottom end of the window.

## States

| State | What shows |
|---|---|
| Loading | first load only: 4 placeholder cards with a slow shimmer (none with animations off). Never a bare spinner |
| Empty | centred column, max 360 dp: illustration (decorative), `lib_empty_title` (headlineSmall), `lib_empty_body`, filled button "Start a scan", tonal button `lib_import`, text button `lib_empty_sample` (opens a bundled sample from #5 in the Viewer). The FAB is hidden while the empty state shows its own button |
| List | the grid |
| Processing card | thumbnail area shows the live preview frame if available, else a `surfaceContainerHighest` block; a linear progress bar along its bottom edge and `lib_processing` as the metadata line. Tap opens Processing |
| Failed card | thumbnail dimmed to 38%; an `errorContainer` badge "Couldn't build" with ErrorOutline icon over the top start. Tap opens Processing in its failed state (Try again / Delete) |
| Imported card | small "Imported" label chip on the thumbnail, `labelMedium` |
| Selection | long-press a card enters selection mode: the top bar becomes `primaryContainer` with Close, `lib_selected`, Share and Delete; cards show a check circle at top end; tap toggles. Back exits selection |
| Delete confirm | dialog: `lib_delete_confirm_title` / `_body`, buttons Cancel and Delete (`error` text). After deleting: snackbar `lib_deleted` with `lib_undo` (5 s) |
| Long names | 2 lines then ellipsis; the full name is in the card's content description |
| AR unavailable | FAB shown with 38% content alpha; tap shows a snackbar with `ar_unsupported_title` and action "Details" (opens the onboarding AR page) |

## Overflow menu

Settings, About. (Import is a top-bar icon, not hidden in the menu: it is a v1 feature.)

## Accessibility

Each card reads "Desk plant, 1 November, 38 megabytes" (processing: "Scan 1 Nov, building, 42 percent"; failed:
"..., couldn't build"). Selection mode announces "Selection mode, 1 selected". Custom actions on each card for
TalkBack: Rename, Share, Delete, so long-press is not the only way in.
