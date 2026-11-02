# Capture

Full-bleed camera, always dark, portrait-locked in v1 (rotation would reset the AR session and the user's grip).
Edge-to-edge; the overlay respects system bar and cutout insets. Copy keys: `cap_*`.

## Layout (phone, 360 dp)

```
┌──────────────────────────────┐
│ (✕)   [● Tracking]      (?)  │  icon buttons 48 dp on OverlayScrim circles; status pill
│ ┌──────────────────────────┐ │
│ │ ⚠ Slow down              │ │  warning banner (only when a warning is live)
│ └──────────────────────────┘ │
│                              │
│            ┌ ┐               │  subject reticle (corner brackets), anchored on the
│            └ ┘               │  tapped subject point, projected each frame
│                              │
│   Walk slowly around it      │  instruction, bodyLarge, on OverlayScrim pill
│                              │
│  ╭─ring─╮                    │  coverage ring 112 dp, bottom start
│  │ 46%  │      ( ◉ )   ✓Fin. │  capture button 72 dp centred; Finish bottom end
│  ╰──────╯   1:24 · 38 frames │  labelSmall caption under the button
└──────────────────────────────┘
```

- **Top bar**: Close (`Icons.Rounded.Close`) top start; a status pill centre ("Starting", "Tracking", "Lost
  track") with a 8 dp dot (`secondary` tracking, `tertiary` limited, `error` lost) and the word, always both; Help
  top end opens a sheet with the three `onb_tip_*` rows.
- **Reticle**: four `OverlayOnScrim` corner brackets, 64 dp box, 2 dp stroke, drawn at the subject's screen position.
  Before a subject is set it sits at the screen centre at 50% alpha.
- **Instruction**: one line (wraps to two), centred, on an `OverlayScrim` pill (`CornerDefault`, 16/8 dp padding),
  above the bottom controls. Exactly one instruction at a time; warnings replace it (see priority).
- **Coverage ring**: 24 azimuth segments × 3 height bands drawn as three concentric rings (outer = low, middle =
  level, inner = high). Covered segment: filled `secondary`. Uncovered: `outline` 1 dp stroke, no fill. The
  segment facing the phone's current direction gets a `primary` tick outside the ring. The centre shows the total in
  `numeral` (`cap_coverage`). The ring sits on an `OverlayScrim` disc. Tap on it toggles a larger 200 dp version
  centred on screen (for low vision).
- **Capture button** (72 dp, centre): idle = white ring with a `primary` filled disc; recording = `RecordRed` rounded
  square inside the ring (shape changes, not only colour); paused = white ring with a play triangle. Content
  description follows the state: "Start capture", "Pause capture", "Resume capture".
- **Finish** (bottom end): a filled tonal button with a check icon and the word "Finish". Disabled (38%) until
  coverage reaches the threshold set by the trainer ticket (placeholder: 40% with at least 2 bands touched); then
  `primary` filled, one `CONFIRM` haptic and `cap_ready` as the instruction.
- **Caption** under the capture button: elapsed time and frames kept, `labelSmall`, tabular figures.

Wide (600 dp+, tablets): same layout, ring 136 dp, controls in a column along the end edge instead of the bottom.

## States

| State | What shows |
|---|---|
| Permission not granted | dark screen, centred: camera-off icon, `cap_permission_title`, `cap_permission_body`, button `cap_permission_allow` (or `cap_permission_settings` once permanently denied), text button Back |
| AR unsupported / not installed | as onboarding's AR states, inside this screen, with Back |
| Starting | camera preview fading in; pill "Starting"; instruction `cap_starting`; controls hidden |
| Finding surfaces | pill "Tracking: limited"; instruction `cap_find_surface`; capture button disabled |
| Aim | instruction `cap_aim` plus `cap_aim_hint` as a second smaller line; tap on the preview sets the subject (reticle snaps there with a short scale-in); the capture button enabled ("Start" uses the centre hit if no tap) |
| Capturing | instruction from the guidance sequence: `cap_walk` → `cap_go_higher` → `cap_go_lower`; ring fills |
| Warning | banner under the top bar: `OverlayScrimStrong`, `CornerDefault`, Warning icon in `tertiary` (Lost track: ErrorOutline in `error`), text in `OverlayOnScrim`. Slides down (`DurationMedium`, decelerate), stays at least `GuidanceMinShow`, `REJECT` haptic, announced politely. Frames are not kept while a warning is live (the caption stops counting) |
| Paused | preview dims under `OverlayScrim`; pill "Paused"; ring stays visible; Finish still available if threshold met |
| Ready to finish | Finish enabled; instruction `cap_ready` |
| Discard confirm | Close or system Back with frames captured: dialog `cap_discard_title` / `_body`, buttons `cap_keep_scanning` and `cap_discard_confirm` (`error`). With nothing captured, Close leaves at once |
| Finishing | after Finish: controls fade out, the ring shrinks into the centre (`DurationLong`), then navigate to Processing |

**Warning priority** (highest first, one shown): Lost track > Too dark > Slow down > Too close > Too far. A lower
warning never replaces a higher one before `GuidanceMinShow`; the same warning waits `GuidanceCooldown` before it
can show again.

## Accessibility

All warnings are text plus haptic plus announcement; none rely on colour or sound. The ring has a content
description "46 percent covered. Missing: high, left side" (the builder computes the largest missing arc and band).
Controls are reachable with keyboard and switch access in order: Close, Help, Ring, Capture, Finish. At 200% font,
the caption and aim hint are dropped first; the instruction wraps to two lines, then truncates with the full text in
its description.
