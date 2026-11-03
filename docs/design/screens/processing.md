# Processing

Always dark. Shown right after Finish, and from a Library card that is building or failed. The work itself runs in a
foreground service (#2 decides the trainer), so this screen is only a window onto it. Copy keys: `proc_*`, `notif_*`.

## Layout (phone, 360 dp)

```
┌──────────────────────────────┐
│ (←)  Building your splat     │  TopAppBar, transparent; back = leave, not cancel
│                              │
│   ┌──────────────────────┐   │  live preview: the splat as it trains, slowly turning,
│   │                      │   │  square, CornerLarge, on ViewerBackground
│   │    [ preview ]       │   │  (placeholder before the first preview frame:
│   │                      │   │   the capture's last camera frame, blurred)
│   └──────────────────────┘   │
│                              │
│   Building the splat         │  headlineSmall (current stage)
│   Step 2 of 4                │  bodyMedium, onSurfaceVariant
│   ████████████░░░░░  62%     │  LinearProgressIndicator 8 dp, CornerXSmall; numeral %
│   About 3 min left           │  bodyMedium
│                              │
│   ⓘ You can leave this ...   │  bodyMedium, onSurfaceVariant, Info icon
│                              │
│   [        Cancel        ]   │  outlined button, full width
└──────────────────────────────┘
```

Stages, in order: Preparing frames → Building the splat → Refining detail → Saving. The bar shows overall progress
across all stages (the trainer ticket gives the weights); the stage line changes with a crossfade.

The time estimate shows `proc_eta_unknown` until progress passes 10% and the estimate has been stable for 10 s, then
rounds to whole minutes ("About 1 min left" below 90 s, "Almost done" below 15 s). It never counts up.

Wide (600 dp+): preview left (max 480 dp), progress column right.

## States

| State | What shows |
|---|---|
| Queued | another scan is building: stage line "Waiting for the other scan to finish", indeterminate bar, Cancel |
| Running | as above |
| Hot phone | a `tertiary` Thermostat icon row with `proc_hot` replaces the "you can leave" row; ETA hides (shows `proc_eta_unknown`) until stable again |
| Cancel confirm | dialog `proc_cancel_title` / `_body`, buttons "Keep building" and `proc_cancel_confirm` |
| Cancelled | back to Library; the card shows as Failed with reason "Stopped" and Try again |
| Done | preview grows to fill the width; a `secondary` CheckCircle and `proc_done`; filled button `proc_open`, tonal button "Share"; `CONFIRM` haptic. If the user is elsewhere, the notification turns into `notif_done` and tapping it opens the Viewer |
| Failed | preview area shows the last camera frame dimmed with an ErrorOutline in `error`; `proc_failed_title` and one reason line from `proc_failed_*`; filled button `proc_retry` (only when frames are kept), text button `proc_delete` (`error`); an expandable "Details" row with the technical message, selectable text, for bug reports |
| Needs poses | a pose-free scan (capture.json, no transforms.json; #217): not a failure, so no `error` colour, no Retry, no Details. Preview: the last frame lightly blurred (SpacingSmall), full alpha, no icon. A PhotoLibrary icon in `onSurfaceVariant` beside `proc_needs_poses_title` (headlineSmall), then `proc_needs_poses_frames` (bodyMedium, onSurfaceVariant) with the kept frame count, read as one node by TalkBack; filled button `proc_back_library`, full width, which leaves at once (no notification ask). No notification. The SfM step (#200) later turns this into a normal run |
| Low storage before start | Failed with `proc_failed_storage`; Try again re-checks |

## Notification

Channel `notif_channel`, low importance (no sound), ongoing while running: title `notif_running`, a determinate
progress bar, action "Cancel". On done: `notif_done`, default importance, tap opens the Viewer. On failure:
`notif_failed`, tap opens this screen. Asking for `POST_NOTIFICATIONS` (API 33+) happens the first time the user
taps Back during processing, with the line `proc_leave` as the reason; a refusal changes nothing else.

## Accessibility

Progress is a live region announced at stage changes and every 25%, not every tick. Cancel and Back are separate
actions with distinct descriptions ("Leave, keep building" vs "Cancel building").
