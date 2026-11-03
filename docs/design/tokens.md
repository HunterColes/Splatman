# Design tokens

What `core/design` must hold after the tokens ticket. Names are the Kotlin names. The purple-and-gold palette
(`SplatColors.DeepPurple`, `SplatGold`, ...) and the poker dimensions (`CardWidth`, `TimerDisplayHeight`, ...) go;
`SplatTheme` and `SplatmanTheme` merge into one `SplatTheme`.

## Colour

Two Material 3 schemes. **Dark** is the brand and is forced on Capture, Processing and Viewer. **Light** is used only
where the screen follows the system (Library, Onboarding, Settings, About). No dynamic colour in v1.

| Role | Dark | Light | Use |
|---|---|---|---|
| `primary` | `#C7B5FF` | `#5B45B0` | Scan FAB, primary buttons, active ring segments, focus ring |
| `onPrimary` | `#2E1A6B` | `#FFFFFF` | text and icons on primary |
| `primaryContainer` | `#46329A` | `#E6DEFF` | selected chips, selection mode bar |
| `onPrimaryContainer` | `#E6DEFF` | `#1A0063` | |
| `secondary` | `#7FD8C4` | `#006B5C` | coverage "covered", success, "done" check |
| `onSecondary` | `#00382F` | `#FFFFFF` | |
| `tertiary` | `#FFC46B` | `#7F5700` | caution: move slower, hot phone, low coverage |
| `onTertiary` | `#432C00` | `#FFFFFF` | |
| `error` | `#FFB4AB` | `#BA1A1A` | failures, delete |
| `onError` | `#690005` | `#FFFFFF` | |
| `errorContainer` | `#93000A` | `#FFDAD6` | failed card badge, error banner |
| `onErrorContainer` | `#FFDAD6` | `#410002` | |
| `background` / `surface` | `#0E0D12` | `#FCF8FF` | screens |
| `surfaceContainerLow` | `#1B1A22` | `#F6F2FA` | library cards |
| `surfaceContainer` | `#211F29` | `#F0ECF4` | sheets, top bar when scrolled |
| `surfaceContainerHigh` | `#2B2934` | `#EAE6EF` | dialogs, menus |
| `surfaceContainerHighest` | `#36333F` | `#E5E1E9` | text fields, ring track |
| `onSurface` | `#E8E5F0` | `#1C1B21` | body text |
| `onSurfaceVariant` | `#C9C4D4` | `#48454F` | secondary text, metadata |
| `outline` | `#938E9E` | `#79757F` | borders, unfilled ring outline |
| `outlineVariant` | `#48454F` | `#CAC4D0` | dividers |
| `scrim` | `#000000` | `#000000` | |

Extra tokens outside the M3 scheme (in `SplatColors`):

| Name | Value | Use |
|---|---|---|
| `OverlayScrim` | `#000000` at 55% | behind any text or control drawn over camera or splat |
| `OverlayScrimStrong` | `#000000` at 75% | warning banners over camera |
| `OverlayOnScrim` | `#FFFFFF` | text and icons on the scrims |
| `ViewerBackground` | `#0A0A0D` | clear colour of the splat renderer |
| `RecordRed` | `#FF5449` | capture button while recording (always with the stop shape, never colour alone) |

**Contrast** (checked, WCAG AA): `onSurface` on `background` 16:1 dark, 16:1 light; `onSurfaceVariant` on
`surfaceContainer` 10:1 dark, 8:1 light; `onPrimary` on `primary` 7.8:1 dark, 7.2:1 light; `primary` on `background`
10:1 dark; `outline` on `background` 6:1 dark (UI needs 3:1); white on `OverlayScrim` over a pure white camera frame
is 5.0:1, so every overlay text sits on a scrim, never straight on the camera.

**Coverage is never colour-only**: a covered ring segment is filled `secondary`; an uncovered one is an `outline`
stroke with no fill; the segment under the camera's current direction gets a 2 dp `primary` outer tick. The
percentage is also printed in text.

## Type

System font (Roboto on most phones); no bundled or downloaded font. The full M3 scale, in `sp` so it follows the
user's font size:

| Style | Size / line | Weight | Use |
|---|---|---|---|
| `displaySmall` | 36 / 44 | Regular | onboarding headline |
| `headlineSmall` | 24 / 32 | Regular | empty-state title, processing stage |
| `titleLarge` | 22 / 28 | Regular | top app bar title |
| `titleMedium` | 16 / 24 | Medium | card title, sheet title |
| `titleSmall` | 14 / 20 | Medium | section headers in settings |
| `bodyLarge` | 16 / 24 | Regular | body, guidance instruction |
| `bodyMedium` | 14 / 20 | Regular | secondary body |
| `bodySmall` | 12 / 16 | Regular | metadata (date, size) |
| `labelLarge` | 14 / 20 | Medium | buttons |
| `labelMedium` | 12 / 16 | Medium | chips, badges |
| `labelSmall` | 11 / 16 | Medium | ring caption |
| `numeral` (extra) | 28 / 36 | Medium, tabular figures | coverage %, progress %, timer |

`numeral` uses `fontFeatureSettings = "tnum"` so changing digits do not jitter.

## Spacing (`SplatDimens`)

4 dp grid. Keep the existing names; delete the rest.

| Name | dp | Use |
|---|---|---|
| `SpacingXSmall` | 4 | icon to label |
| `SpacingSmall` | 8 | inside chips, between metadata lines |
| `SpacingMedium` | 12 | grid gutter |
| `SpacingDefault` | 16 | screen edge margin on phones, card padding |
| `SpacingLarge` | 24 | between sections, screen margin at 600 dp and up |
| `SpacingXLarge` | 32 | empty-state blocks |
| `SpacingXXLarge` | 48 | onboarding top space |
| `TouchTarget` (new) | 48 | minimum size of anything tappable |
| `CaptureButton` (new) | 72 | the record / finish button |
| `CoverageRing` (new) | 112 | ring diameter on phones (136 at 600 dp and up) |
| `CoverageRingStroke` (new) | 8 | ring segment thickness |
| `ContentMaxWidth` (new) | 840 | max width of library, settings, sheets on wide screens |
| `LibraryCellMin` (new) | 160 | adaptive grid min cell width |

## Shape

| Name | Radius | Use |
|---|---|---|
| `CornerXSmall` | 4 dp | badges |
| `CornerSmall` | 8 dp | chips, snackbars |
| `CornerMedium` | 12 dp | library cards, thumbnails |
| `CornerDefault` | 16 dp | FAB, warning banner |
| `CornerLarge` | 28 dp | bottom sheets (top corners), dialogs |
| full (`CircleShape`) | | capture button, icon buttons on scrims |

## Elevation

Tonal, not shadows (M3): level 0 screens, level 1 cards (`surfaceContainerLow`), level 2 top bar when scrolled,
level 3 FAB and sheets, level 3 dialogs. Overlays on camera and viewer use the scrim, not elevation.

## Motion (`SplatMotion`, new)

| Token | Value | Use |
|---|---|---|
| `EasingStandard` | cubic (0.2, 0.0, 0.0, 1.0) | most moves |
| `EasingEmphasizedDecelerate` | cubic (0.05, 0.7, 0.1, 1.0) | things entering: sheets, screens |
| `EasingEmphasizedAccelerate` | cubic (0.3, 0.0, 0.8, 0.15) | things leaving |
| `DurationShort` | 150 ms | icon swaps, chip select, ring segment fill |
| `DurationMedium` | 300 ms | banners in and out, sheet open |
| `DurationLong` | 500 ms | screen transitions, capture to processing |
| `GuidanceMinShow` | 1500 ms | a live warning stays at least this long, so it does not flicker |
| `GuidanceCooldown` | 4000 ms | the same warning does not repeat sooner |

When the system animator scale is 0 ("remove animations"), durations become 0 and transitions become a 100 ms
crossfade. The ring fills without a sweep. The viewer does not auto-rotate.

## Icons

Material Symbols Rounded via `material-icons-extended` (already a dependency), 24 dp, filled when active, outlined
otherwise. One icon per idea across the app:

| Idea | Icon |
|---|---|
| Scan | `Icons.Rounded.ViewInAr` |
| Import / open file | `Icons.Rounded.FileOpen` |
| Share | `Icons.Rounded.Share` |
| Save to device | `Icons.Rounded.Download` |
| Settings | `Icons.Rounded.Settings` |
| Info | `Icons.Rounded.Info` |
| Delete | `Icons.Rounded.Delete` |
| Rename | `Icons.Rounded.Edit` |
| Reset view | `Icons.Rounded.CenterFocusStrong` |
| Auto-rotate | `Icons.Rounded.ThreeSixty` |
| Help / tips | `Icons.Rounded.HelpOutline` |
| Close | `Icons.Rounded.Close` |
| Back | `Icons.AutoMirrored.Rounded.ArrowBack` |
| Warning | `Icons.Rounded.Warning` |
| Error | `Icons.Rounded.ErrorOutline` |
| Done | `Icons.Rounded.CheckCircle` |
| Hot phone | `Icons.Rounded.Thermostat` |
| Pause / resume | `Icons.Rounded.Pause` / `Icons.Rounded.PlayArrow` |

App icon: Hunter's original Splatman icon (restored from ac960ac, gold splat on the original background), an
adaptive icon with the original monochrome silhouette for themed icons. It is the one thing that does not follow the
v1 violet scheme.

## Haptics

| Moment | Feedback constant |
|---|---|
| Capture starts / stops | `HapticFeedbackConstants.CONFIRM` (API 30+, else `LONG_PRESS`) |
| A live warning appears | `HapticFeedbackConstants.REJECT` (API 30+, else `LONG_PRESS`) |
| Coverage passes the Finish threshold | `CONFIRM` |
| Processing done (app in foreground) | `CONFIRM` |

All off when Settings > Haptics is off. No sounds anywhere (the old `SoundManager` is poker leftover).
