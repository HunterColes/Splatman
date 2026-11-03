# Library (home)

The start destination. Follows the system theme. Copy keys: `lib_*`.

## Layout (phone, 360 dp)

```
┌──────────────────────────────┐
│ Splatman      [⌕] [⇪] [⋮]    │  TopAppBar (large on first screen, collapses on scroll)
├──────────────────────────────┤  ⌕ = Search, ⇪ = Open a file (FileOpen), ⋮ = New folder, Settings, About
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
- **Order**: the remembered sort (newest first until the user picks another); see [Search, sort and folders](#search-sort-and-folders).
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

New folder, Settings, About. (Import is a top-bar icon, not hidden in the menu: it is a v1 feature.)

## Accessibility

Each card reads "Desk plant, 1 November, 38 megabytes" (processing: "Scan 1 Nov, building, 42 percent"; failed:
"..., couldn't build"). Selection mode announces "Selection mode, 1 selected". Custom actions on each card for
TalkBack: Rename, Share, Delete, so long-press is not the only way in.

## Search, sort and folders

Ticket #301 ("Later, ranked" #9). For someone with many scans: find one by name, put the grid in a useful order,
and group scans into folders. Copy keys `lib_search_*`, `lib_sort_*`, `lib_folder_*`, `lib_move*`
([copy.md](../copy.md#library-search-sort-and-folders)). Data model, migration and sort keys in code: the ADR filed
under #301 in `docs/architecture/adr/`.

### Model the user sees

- **One level of folders.** The Library root holds folders and loose scans; a folder holds scans only. A scan is in
  exactly one place: the root or one folder.
- **Nothing is ever deleted by a folder action.** Deleting a folder moves its scans back to the root.
- **Things made inside a folder land in it.** A scan started with the FAB, or a file opened with Open a file, while a
  folder is open is saved into that folder. Everywhere else they land at the root, as today.
- Existing scans all sit at the root after the update; no folder is made automatically.

### Root view (phone, 360 dp)

```
┌──────────────────────────────┐
│ Splatman      [⌕] [⇪] [⋮]    │  ⋮ = New folder, Settings, About
├──────────────────────────────┤
│ (⇅ Newest first ▾)           │  sort chip, scrolls with the grid
│ Folders                      │  titleSmall, onSurfaceVariant; only when both kinds exist
│ ┌───────────┐ ┌───────────┐  │
│ │ ▓▓▓ │ ▓▓▓ │ │           │  │  folder cell = same size as a scan card;
│ │─────┼─────│ │    🗀      │  │  2×2 mosaic of its first 4 thumbnails (current sort);
│ │ ▓▓▓ │     │ │           │  │  empty folder: Folder icon on surfaceContainerHighest
│ ├───────────┤ ├───────────┤  │
│ │🗀 Desk  [⋮]│ │🗀 Garden[⋮]│  │  Folder icon 20 dp + name (titleMedium, 2 lines) + ⋮ (48 dp)
│ │3 scans·96M│ │Empty      │  │  bodySmall: lib_folder_count · size, or lib_folder_count_empty
│ └───────────┘ └───────────┘  │
│ Scans                        │
│ ┌───────────┐ ┌───────────┐  │
│ │ thumbnail │ │ thumbnail │  │  scan cards, unchanged
│              [◈  Scan ]      │
└──────────────────────────────┘
```

- **Folder cell**: `surfaceContainerLow`, `CornerMedium`, like a scan card, so the grid keeps one rhythm. Mosaic tiles
  are separated by `SpacingXSmall` of the card colour; tiles for scans without a thumbnail are
  `surfaceContainerHighest`. The Folder icon in the title row (not colour) is what tells a folder from a scan. Tap
  opens the folder. Long-press, or the ⋮ button, opens a small menu: Rename folder, Delete folder. Folder cells are
  never selectable.
- **Order**: folders first, then loose scans, both in the chosen sort (below). The section labels "Folders" and
  "Scans" span the full width and appear only when the root holds both.
- **Empty states**: the existing empty state shows only when there are no scans and no folders at all. A root with
  folders but no loose scans shows just the folders and the FAB.

### Inside a folder

```
┌──────────────────────────────┐
│ [←] Desk stuff     [⌕] [⋮]   │  small TopAppBar; title 1 line, ellipsis; ⋮ = Rename folder, Delete folder
├──────────────────────────────┤
│ (⇅ Newest first ▾)           │
│ ┌───────────┐ ┌───────────┐  │  the folder's scans: same cards, same states
│ │ thumbnail │ │ ▓▓ 42%    │  │  (processing, failed, imported)
│              [◈  Scan ]      │  FAB: the new scan lands in this folder
└──────────────────────────────┘
```

- Back (arrow or system) returns to the root, scrolled to where it was.
- **Empty folder**: centred column, max 360 dp: Folder icon 48 dp (decorative), `lib_folder_empty_title`
  (headlineSmall), `lib_folder_empty_body` (bodyMedium, onSurfaceVariant). The FAB stays; the sort chip hides.
- Route suggestion for the architect: `library/folder/{folderId}`. A folder that disappears while open (deleted from
  elsewhere, or a create undone) returns to the root.

### Search

- **Where**: the ⌕ icon (`Icons.Rounded.Search`, description `lib_search`) in the top bar, at the root and inside a
  folder; Ctrl+F on a hardware keyboard opens it too. Tapping it turns the top bar into a search bar in place (no new
  screen): [←] (`lib_search_close`), a single-line text field focused with the keyboard up, and [×]
  (`lib_search_clear`) once there is text. The bar is the small pinned style while searching. The sort chip stays;
  the FAB hides while search is open.

```
┌──────────────────────────────┐
│ [←] desk|               [×]  │  placeholder lib_search_hint (root) / lib_search_hint_folder
├──────────────────────────────┤
│ (⇅ Newest first ▾)           │
│ ┌───────────┐ ┌───────────┐  │
│ │🗀 Desk stuff│ │Desk plant │  │  root search: matching folders first, then matching scans
│ │3 scans·96M│ │1 Nov·38 MB│  │  from every folder; a scan inside a folder gets a third line:
│ └───────────┘ │🗀 In Garden│  │  Folder icon 16 dp + lib_search_in_folder (bodySmall)
│               └───────────┘  │
└──────────────────────────────┘
```

- **Live filtering**: the grid filters as each character is typed; no submit button, and the keyboard's Search action
  only hides the keyboard. An empty or spaces-only query shows the whole place unfiltered. Results keep the chosen
  sort; each new query scrolls to the top.
- **Matching**: the trimmed query matches anywhere in the name, ignoring case and accents ("cafe" finds "Café").
  Emoji and any script match as typed. The field and every name use content text direction, so Hebrew or Arabic reads
  right to left and the ellipsis falls at the logical end.
- **Scope**: at the root, every scan in every folder plus folder names. Inside a folder, only that folder's scans.
- **Live data**: a processing scan in the results keeps its progress bar; one that finishes, fails or is deleted
  updates in place without clearing the query.
- **No match**: the grid area shows, centred, max 360 dp: `lib_search_empty_title` (titleMedium) with the query
  quoted, then `lib_search_empty_body` (bodyMedium, onSurfaceVariant). Inside a folder, a text button
  `lib_search_everywhere` below it leaves the folder and reruns the same query at the root, so a wrong folder is not a
  dead end. The quoted query is bidi-isolated (its own direction run, so an RTL query does not flip the quotes) and
  wraps to 3 lines at most, then ellipsis.
- **Closing**: [←], system Back or Esc closes search and clears the query; the next open starts empty. Tapping a
  result opens it as usual; coming back from the Viewer returns to the results with the query kept.
- **500 scans**: filtering and sorting run off the main thread; results replace the grid within a frame or two of a
  keystroke, with stable item keys so thumbnails do not reload or flash.

### Sort

- **Control**: an `AssistChip` at the top start of the grid content (above "Folders"): leading `Icons.AutoMirrored.Rounded.Sort`,
  label the current choice, trailing `Icons.Rounded.ArrowDropDown`; it scrolls away with the grid. Content description
  `lib_sort_description`. Hidden in the empty states and the no-match state.
- **Menu**: a `DropdownMenu` anchored to the chip, four items; the current one has a leading `Icons.Rounded.Check`
  (the others keep an empty 24 dp slot so labels line up): `lib_sort_newest`, `lib_sort_oldest`, `lib_sort_name`,
  `lib_sort_size`. Picking one closes the menu, reorders with item placement animation (`DurationMedium`,
  `EasingStandard`; instant with animations off) and scrolls to the top.
- **Keys**: newest and oldest by the date the scan was made (imports: the date imported); name in natural, locale
  order, ignoring case and accents ("Scan 2" before "Scan 10"); largest first by file size, with scans that have no
  size yet (captured, processing, failed) last. Ties fall back to newest first. Folders sort by the same key: their
  date is when they were made, their size the sum of their scans, their name their name.
- **Remembered**: one choice for the whole app (root, every folder, search results), kept across restarts. Default
  newest first.

### Folders: create, rename, move, delete

- **New folder**: from the root overflow menu, or the first row of the Move sheet. `AlertDialog`: title
  `lib_folder_new`, one text field labelled `lib_name`, empty, focused with the keyboard up; buttons `lib_cancel` and
  `lib_folder_create`. Create is disabled while the trimmed name is blank. Names are trimmed and capped at 60
  characters (counter `lib_folder_name_count` from 50 on). A name matching an existing folder, ignoring case and
  accents, keeps the dialog open with the field in error and supporting text `lib_folder_name_taken`. Created from
  the overflow menu, the folder appears in the grid; created from the Move sheet, the selected scans move straight
  into it (one snackbar, `lib_moved`).
- **Rename folder**: from the folder cell menu or the folder's top bar overflow. The same dialog, title
  `lib_folder_rename`, field prefilled and all selected, confirm button `lib_rename`. The same blank and duplicate
  rules; its own current name is not a duplicate.
- **Move**: selection mode gains a Move action (`Icons.AutoMirrored.Rounded.DriveFileMove`, `lib_move`) before Share
  and Delete. It opens a `ModalBottomSheet` (a dialog at 600 dp and up):

```
┌──────────────────────────────┐
│ Move 3 scans to              │  titleMedium, pinned; lib_move_title
│ [+🗀] New folder              │  CreateNewFolder icon; ListItem, 56 dp
│ [▦]  Library                 │  GridView icon; the root, lib_move_root
│ [🗀]  Desk stuff   Here now   │  current place: disabled, trailing lib_move_here_now
│ [🗀]  Garden       5 scans    │  trailing lib_folder_count
│  …                           │  list scrolls; folders in name order
└──────────────────────────────┘
```

  Tap a row: the sheet closes, selection mode ends, the scans move, and snackbar `lib_moved` with `lib_undo` (5 s)
  puts each one back where it was. A row is disabled ("Here now") only when every selected scan is already there, so
  a mixed selection from root search results can still move. Moving a processing or failed scan is allowed: it keeps
  building or keeps its failed state, and its card appears in the new place with the same progress. If the move
  fails, snackbar `lib_move_error` and nothing moves.
- **Move out**: inside a folder, select, Move, pick Library.
- **Delete folder**: from the folder cell menu or the folder's overflow. `AlertDialog`: title
  `lib_folder_delete_title`, body `lib_folder_delete_body` (or `lib_folder_delete_body_empty`), buttons `lib_cancel`
  and `lib_folder_delete` in `error` colour. The folder goes, its scans move to the root (a processing scan keeps
  building there), the user lands at the root, and snackbar `lib_folder_deleted` with `lib_undo` (5 s) brings back
  the folder with the same name and the same scans. Deleting scans stays a selection action; a folder delete never
  deletes a scan.
- **Errors**: a create, rename or delete that fails to save shows snackbar `lib_folder_error`; the dialog's text is
  kept so the user can retry.

### Selection inside a folder and in search

- Works as at the root: long-press a scan card enters selection; the top bar becomes `primaryContainer` with Close,
  `lib_selected`, Move, Share, Delete; tap toggles. At the root, folder cells dim to 38% content alpha and ignore
  taps while selection is on.
- Selection belongs to one place: leaving the folder or closing search ends it. Back steps out one layer at a time:
  selection, then search, then the folder.
- Entering selection while searching swaps the search bar for the selection bar; Close returns to the search bar with
  the query kept. A selected scan that stops matching leaves the selection.
- Deleting scans from inside a folder works as today (confirm, snackbar, Undo); the folder stays, empty if it was
  the last.

### States (this section)

| State | What shows |
|---|---|
| Root with folders | sort chip, "Folders", folder cells, "Scans", scan cards |
| Folder open | back arrow, folder name, sort chip, its scans, FAB |
| Folder empty | Folder icon, `lib_folder_empty_title`, `lib_folder_empty_body`, FAB |
| Loading | first load only, as today: 4 placeholder cards, at the root or in a folder |
| Search, no query | search bar focused, keyboard up, the whole place listed |
| Search results | filtered grid; at the root, scans in folders show "In …" |
| Search, no match | `lib_search_empty_title` / `_body`; inside a folder also `lib_search_everywhere` |
| Sort menu | dropdown, current choice checked |
| New folder / rename | dialog; blank disables the button; duplicate shows `lib_folder_name_taken` |
| Move sheet | New folder, Library, folders; the current place disabled |
| Moved | snackbar `lib_moved` + Undo |
| Delete folder confirm | dialog; body says the scans move to Library |
| Folder deleted | root, snackbar `lib_folder_deleted` + Undo |
| Error | snackbar `lib_move_error` or `lib_folder_error` |
| Long names | folder cells 2 lines then ellipsis; top bar title, Move rows and the "In …" line 1 line then ellipsis; the full name in the content description |
| Font scale 200% | the sort chip label wraps rather than clipping; Move rows grow; folder cell text grows under the mosaic |
| Wide (600 dp+) | content column max `ContentMaxWidth`, centred; the search field spans the top bar within that width; the sort chip aligns with the grid's start edge; the Move sheet is a dialog |

### Accessibility

- Search field: label `lib_search_hint` (or `lib_search_hint_folder`). When the result count changes, a polite live
  region announces `lib_search_results` (or `lib_search_none` when nothing matches), at most once per second of
  typing so it does not chatter.
- The sort chip reads `lib_sort_description` ("Sort: Newest first"); menu items have radio semantics, the checked one
  selected.
- A folder cell reads `lib_folder_description` ("Folder, Desk stuff, 3 scans, 96 megabytes"), with custom actions
  Rename folder and Delete folder; its ⋮ reads `lib_folder_more`. A scan card in root search results appends
  `lib_search_in_folder` to its description.
- Move rows read the name and count; the current place reads "Desk stuff, here now" and is disabled.
- Focus order: top bar, sort chip, grid (folders, then scans), FAB. Opening search moves focus to the field; closing
  it returns focus to the ⌕ button. Every new target (⌕, ×, ←, chip, folder ⋮, Move rows) is at least `TouchTarget`.
- Icons new to [tokens.md](../tokens.md): Search `Icons.Rounded.Search`, Sort `Icons.AutoMirrored.Rounded.Sort`, Folder
  `Icons.Rounded.Folder`, New folder `Icons.Rounded.CreateNewFolder`, Move
  `Icons.AutoMirrored.Rounded.DriveFileMove`, Library root `Icons.Rounded.GridView`, Check `Icons.Rounded.Check`,
  menu arrow `Icons.Rounded.ArrowDropDown`. No new colour, size or motion token.

### Out of scope

Folders inside folders; a scan in more than one folder; folder colours or chosen covers; searching by date, size or
anything but the name; highlighting the match; a different sort per folder; drag and drop to move; select all;
sharing or exporting a whole folder; per-folder storage in Settings.
