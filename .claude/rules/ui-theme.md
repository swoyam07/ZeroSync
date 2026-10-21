---
paths:
  - "app/src/**/ui/**"
  - "app/src/**/theme/**"
  - "app/src/**/*Screen.kt"
---

# UI & Theme Rules

- Dark only. Background `#000000`. No light theme.
- Colors (Material 3 roles):
  - primary `#C19EE0`: links, active nav icons, brand
  - primaryContainer `#6247AA`: active tabs, toggles, selected cards
  - secondary `#9163CB`: secondary buttons, chips, outlines, inactive controls
  - tertiary `#DEC9E9`: FABs, badges, progress indicators only
- Main nav: floating pill/capsule bottom bar: Gallery (default), Contact, Music, File, Camera.
- Section mode switches: pill tab at top + sideways swipe on the section screen (no nav-bar gestures).
- Use lazy lists/grids for large data. No animations unless approved.
