---
paths:
  - "app/src/**/ui/**"
  - "app/src/**/theme/**"
  - "app/src/**/*Screen.kt"
---

# UI & Theme Rules

- Dark only. Background `#000000`. No light theme.
- Colors: black, white and grays ONLY. No other hues anywhere (set 2026-10-21).
  Use `ZsColors` from `ui/theme/Theme.kt`; never hardcode colored hex values.
  - primary `#FFFFFF`: links, active nav icons, brand, "on" states
  - primaryContainer `#3A3A3A`: active tabs, toggles, selected cards
  - secondary `#8A8A8A`: secondary buttons, chips, outlines, inactive controls
  - tertiary `#D0D0D0`: FABs, badges, progress indicators only
  - surface `#141414`, text `#E6E6E6`
  - Only exception: red `#D32F2F` for destructive Delete buttons and call End (user-approved 2026-10-21).
- Main nav: swipe-dial strip (`ui/DialNavBar.kt`): thin line + 5 dots (Gallery, Contact,
  Music, File, Camera). Swipe sideways = big tab name on screen, lift = switch. Tap a dot = jump.
  App opens on the last-used section; first install = Music.
- Section mode switches: pill tab at top + sideways swipe on the section screen (no nav-bar gestures).
- Use lazy lists/grids for large data. No animations unless approved.
