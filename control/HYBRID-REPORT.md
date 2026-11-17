# Hybrid UI-tree / screenshot control (branch `droid-hybrid`)

Inspired by Vercel agent-browser `@ref` snapshots. All 16 tool names unchanged; server version 0.9.0.

## What changed (`control/control-mcp.mjs`)
1. **`ui_tree`** now returns a compact text list of the *actionable, visible* elements: `@e1 button "로그인" [x,y] flags`.
   Labels fall back to the texts inside the element, then `#resource-id`. Non-actionable visible texts go on one trailing `text:` line (no refs).
   `all:true` still returns the full raw node dump (actionable nodes additionally carry `ref`).
2. **`tap_element ref:"@eN"`** resolves against the LAST snapshot (refreshed by every `ui_tree` and every observe).
   Unknown ref -> error telling to re-snapshot. After any action the snapshot is "dirty": the ref is re-verified against a fresh dump
   (same role/label/hint) and the fresh coordinates are used; if it is gone -> `stale ref` error telling to re-snapshot.
   Text/id/index lookups keep working.
3. **`observe: auto`** (new default for action tools; `CONTROL_OBSERVE=none|ui|screenshot|auto` still overrides):
   ref list, plus a screenshot and a note when fewer than `SPARSE_BELOW` (3) actionable elements exist (games, canvas, Flutter, WebView, FLAG_SECURE).
   If the dump itself fails in `auto`, the screenshot alone is returned with a note. `ui_tree` itself only adds the note, never an image.
4. **Duplicate labels**: same role+label gets `(in "<nearest distinguishing ancestor text/id>")`, else `(#n)`.
5. **Viewport cap**: only elements intersecting the root bounds are listed (partly visible ones use the visible center); at most `MAX_LIST` = 60, with a "+N more" line.

Tool descriptions and server `INSTRUCTIONS` updated; README.md / README.ko.md updated.

## Measured `ui_tree` size
Fixture `control/test/settings-screen.xml`: a **synthetic** Settings-like screen (1080x2400, 12 rows, 2 below the viewport, 6 duplicate unlabeled switches, status/nav bars). Not a real device dump (no device available here).

| output | before | after |
|---|---|---|
| default `ui_tree` | 5408 bytes (50 JSON nodes) | 1182 bytes (22 elements + text line) |
| `ui_tree all:true` | 8347 bytes | 8624 bytes (adds `ref` on actionable nodes) |

Default output is ~78% smaller. Real apps with deeper trees should gain more; the effect is not measured on one.

## Behaviour changes to know
- Action tools now observe by default (`auto`) instead of `none`; use `observe:"none"` (or `CONTROL_OBSERVE=none`) for the old behaviour. `batch` steps still default to `none`; `batch`'s own observe runs at the end.
- `ui_tree` / observe output is plain text, not JSON (`{count,nodes}` only with `all:true`).
- Overlapping windows are not modelled (the dump has no z-order): an element hidden under the nav bar can still be listed.

## Tests
`cd control && node --test test/control-mcp.test.mjs` -> 26 pass, 0 fail (fake exec + `fake-adb.sh` stdio test, which now serves the fixture for `uiautomator dump`).
New tests cover snapshot format/clip/dupes/cap, size ratio, ref resolve/unknown/stale, observe auto/override/fallback.
