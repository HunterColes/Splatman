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
│  ( ) Video (MP4)             │  exp_video
│      A 6-second spin of ...  │  exp_video_body
│      About 6 MB              │
│  ( ) Web page (.html)        │  exp_html
│      Opens in any browser... │  exp_html_body
│      About 12 MB             │
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

## Video (MP4)

A third radio row, after .splat, styled like the other two (no divider, no icon, no badge). It is the one row for people
without a splat viewer: one preset orbit, nothing to set.

- **What it makes**: one full turn of the scan, about 6 s at 30 fps (180 frames), first frame = last frame so it
  loops in a chat app. Square, 1080 x 1080, or the largest square the device's H.264 encoder supports. Centred on
  the scan's bounds, at the Viewer's current elevation and distance (from the Library: the Viewer's reset pose).
  Background `ViewerBackground`; a cropped scan renders cropped. No watermark, no title card, no music.
- **Shape**: square only, no choice. A square plays uncropped in every chat app, feed and in portrait or landscape,
  and an orbit around one subject fills a square best.
- **Size**: `exp_size` with the estimate from the encoder's bitrate x 6 s (about 6 MB at 1080 x 1080, 8 Mbit/s).
- **File**: the scan name plus `.mp4` (`Desk plant.mp4`), MIME `video/mp4` (not the octet-stream the splat formats
  use, so chat apps show it as a video). Share and Save to device work as for PLY.
- **Hidden** when the export is a Library multi-select (one scan per video), and when the phone has no H.264
  encoder (MediaCodecList). Not offered as Settings > Default export format; the sheet never opens on Video.
- **Writing (video)**: the button row is replaced by a determinate linear progress bar (frames encoded / 180),
  `exp_video_writing` above it in bodyMedium, `exp_video_keep_open` under it in bodySmall `onSurfaceVariant`, and a
  `exp_video_cancel` text button at the end (48 dp tall, whole width of its label + 12 dp padding). Cancel and
  system Back both stop the encode and delete the partial file; the sheet returns to Choose with Video selected.
  The bar keeps moving on a 2M splat scan: it advances per frame, never per stage.
- **Rotation mid-encode**: the sheet and its progress survive; the encode is offscreen and does not restart.
- **Left the app mid-encode**: the encode stops and the partial file is deleted; on return the sheet shows the
  error row with `exp_video_stopped`.
- **Encoder failed** (configure or mid-encode): the error row with `exp_video_error`; a write failure (storage, the
  save target) keeps `exp_error`. Never a crash.

## Web page (.html)

A fourth radio row, last, after Video (MP4), styled like the others (no divider, no icon, no badge). It is the row
for a friend without Splatman: one file they open in a browser and turn with a finger. The page's own look
(viewer, colours, title, "Made with Splatman" line) is the template's and not designed here.

- **What it makes**: one `.html` file: the template asset (`viewer-feature/src/main/assets/web/viewer.html`) with
  the scan inline as `.splat` bytes (32 bytes per splat), Base64-encoded in one block. Nothing else rides along: no
  second file, no ZIP, no URL. It opens from `file://` with the network off. A cropped or erased scan exports as
  it shows.
- **File**: the scan name made filesystem-safe plus `.html` (`Desk plant.html`), MIME `text/html` (not the
  octet-stream the splat formats use), so a phone that receives it opens it in the browser. Share and Save to device
  work as for PLY; Save's `ACTION_CREATE_DOCUMENT` carries `text/html`.
- **Exact size**: `T + N + 4 × ⌈32 × n / 3⌉` bytes, where `T` is the template's UTF-8 bytes with every placeholder
  empty, `N` the UTF-8 bytes of the values put into the placeholders (the escaped scan name, the start pose) and `n`
  the splats written (after the cap below). Base64 is one unbroken block with `=` padding, no line breaks. The row
  shows it through `exp_size` and the system's file size formatter, like the other rows; the number before
  formatting equals the written file's length to the byte.
- **Cap: 600,000 splats.** Browsers refuse or crash on one large file; the SuperSplat manual cites 32 MB, and #417
  measured about 25 MB of payload as the safe line. 600,000 splats is 19.2 MB of `.splat` bytes and 25.6 MB of
  Base64 (24.4 MiB), which leaves the template and a phone browser's own memory room under 32 MB. A scan at or under
  the cap exports whole.
- **Over the cap: keep the 600,000 most opaque splats, and the row says so.** The row stays enabled, its size is
  the trimmed file's, and a third line `exp_html_trimmed` ("Keeps the 600,000 most visible splats of 2.1 million")
  shows under the size in bodySmall `onSurfaceVariant`. Why trim, not disable: the page is meant to reach a friend,
  and a disabled row would turn away exactly the big scans people most want to show; the faintest splats add the
  least to the picture, so the page still looks like the scan; and the whole scan is one row up as PLY or .splat
  for anyone who needs every splat. Order: opacity (the `.splat` alpha byte) descending, ties by the lower splat
  index, then written in their original order, so the same scan always makes the same file. The scan itself is
  never changed.
- **Hidden** when the export is a Library multi-select (one scan per page), as the Video row, and when the template
  asset is missing from the build. Not offered as Settings > Default export format; the sheet never opens on
  Web page.
- **Writing**: as the PLY rows: the Writing state with `exp_writing`, no progress bar of its own and no Cancel
  beyond system Back, which cancels the write and deletes the partial file. Errors follow PLY too: a write failure
  shows the error row with `exp_error`, low space shows `exp_low_storage` with the size above. Never a crash.

## States

| State | What shows |
|---|---|
| Choose | as above |
| Writing | buttons replaced by a linear progress bar and `exp_writing`; the sheet cannot be dragged closed; system Back cancels the write |
| Writing (video) | as Writing, with a determinate bar, `exp_video_writing`, `exp_video_keep_open` and a Cancel text button (see Video above) |
| Web page over the cap | Web page row enabled; its size is the trimmed file's; `exp_html_trimmed` under the size (see Web page above) |
| Shared | sheet closes when the share sheet opens; nothing else (Android shows its own result) |
| Saved | sheet closes; snackbar `exp_saved` on the Viewer |
| Error | the buttons return; an `errorContainer` row above them with `exp_error` |
| Low storage | before writing: the button row is disabled and `exp_low_storage` shows in `error` text under the size |
| Long content | at 360 dp and 200 % font the bodies wrap; the sheet's content scrolls and the button row (or progress row) stays pinned at the bottom; `exp_html_trimmed` wraps under the Web page size, never truncated |

## Accessibility

The radio group is one focusable group with each option reading its name, description and size. Sizes use
the system's localized file size formatter. The video progress bar is a `ProgressBarRangeInfo` that TalkBack reads as
a percentage, and its label (`exp_video_writing`) is announced once when writing starts, not on every frame. Cancel
takes focus when the writing state opens, so a keyboard or switch user can stop it without hunting. The Web page row reads its name, description, size and, over the cap, `exp_html_trimmed`
in that order, so a TalkBack user hears that the page keeps fewer splats before choosing it. Splat counts in
`exp_html_trimmed` use the same localized number formatting as `view_info_splats` (digits for the cap, the compact
form for the total, "2.1 million").
