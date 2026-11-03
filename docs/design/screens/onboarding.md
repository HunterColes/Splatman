# Onboarding

First run only (a preference flag), and again from Settings > Show scanning tips again. Follows the system theme.
Copy keys: `onb_*`, `ar_*` in [../copy.md](../copy.md).

## Layout (phone, 360 dp)

A horizontal pager of three pages, one column, 24 dp side margins, content vertically centred in the space above a
fixed bottom bar.

```
┌──────────────────────────────┐
│                       Skip   │  text button, top end, 48 dp
│                              │
│        [ illustration ]      │  200 dp square, decorative (no content description)
│                              │
│  Scan anything in 3D         │  displaySmall
│  Walk around something...    │  bodyLarge, onSurfaceVariant
│  🔒 Everything stays on ...  │  bodyMedium, lock icon, page 1 only
│                              │
│          ● ○ ○               │  page dots, 8 dp, with "Page 1 of 3" for TalkBack
│  [          Next          ]  │  filled button, full width, 48 dp
└──────────────────────────────┘
```

1. **Welcome**: illustration of a phone orbiting an object along a dotted circle.
2. **Tips**: three rows, each a 40 dp icon in a `primaryContainer` circle plus one line (`onb_tip_slow`,
   `onb_tip_around`, `onb_tip_light`). No illustration; the rows are the content.
3. **Camera**: camera icon illustration, `onb_camera_title`, `onb_camera_body`; the button reads `onb_camera_allow`
   and launches the system permission dialog. Whatever the answer, it then goes to the Library (the user is not
   blocked; Capture handles a denial).

Wide (600 dp and up): the column is max 480 dp wide, centred; the illustration sits left of the text on landscape.

## States

| State | What shows |
|---|---|
| Normal | the three pages above; swipe or Next moves on; Skip goes straight to the Library |
| AR unsupported | replaces page 3: `ar_unsupported_title` / `ar_unsupported_body`, button "Open a file" (import) and text button "Continue" (Library). The Scan FAB in the Library is then shown disabled with the same explanation on tap |
| AR needs install | replaces page 3: `ar_install_title` / `ar_install_body`, buttons `ar_install_action` (opens the store listing by intent; no internet permission needed in-app) and "Later" |
| AR couldn't check | ARCore's check erred, timed out or threw (never read as unsupported): replaces page 3: `ar_check_failed_title` / `ar_check_failed_body`, button "Try again" (checks again) and text button "Continue" (Library) |
| Details (every AR state) | under the body: `ar_details` with the raw reason. Only ARCore's UNSUPPORTED_DEVICE_NOT_CAPABLE (or the foss build) is "unsupported"; SUPPORTED_NOT_INSTALLED and SUPPORTED_APK_TOO_OLD are "needs install" |
| Long text / 200% font | the page scrolls vertically; the bottom bar stays fixed |

## Accessibility

Pages are announced as "Page n of 3"; Skip and Next are reachable by keyboard in reading order; the pager does not
auto-advance. Illustrations are decorative.
