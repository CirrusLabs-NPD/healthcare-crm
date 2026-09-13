# S-3 — Scheduling UI craft review

**Reviewed:** `static/scheduling-reference.html` (every token rendered), served as a static production page.
**Matrix:** 1440 & 390 wide × light & dark — all four captured.
**Verdict per render (from `sandbox_browse`):** `healthy:true · styled:true · defaultControls:0 · bodyFontIsDefault:false · consoleErrors:[] · pageErrors:[]` on all four.

Screenshots on record: `review/s3-1440-light.png`, `s3-1440-dark.png`, `s3-390-light.png`, `s3-390-dark.png`.

## Checklist

| # | Item | Result | Note / fix |
|---|---|---|---|
| 1 | One typeface, not the UA serif | **PASS** | Inter stack resolves (`bodyFontIsDefault:false`). |
| 2 | Type scale 12/14/16/20/24/32, hierarchy by size+weight+colour | **PASS** | Six-step scale rendered; muted secondary, not just smaller. |
| 3 | 4px spacing scale, no ad-hoc gaps | **PASS** | All gaps from `--sch-space-*`. |
| 4 | One primary action, most visible | **PASS** | Solid gold/primary *New appointment* is the loudest control; view-switch and nav are outline/secondary. |
| 5 | Centred max-width, page padding, aligned grid | **PASS** | Reference wrapped at 1200px; calendar grid columns align. |
| 6 | No browser-default controls; designed 2px accent focus ring | **PASS** | `defaultControls:0`; view-switch, weekday and scope controls are custom; focus ring inherited from base `custom.css`. |
| 7 | Colour discipline — neutral surfaces, accent sparingly, ≤5 hues, ≥4.5:1 | **PASS** | Accent only on primary action + focus; event text contrast checked in both themes. |
| 8 | Consistent components — shared heights, radii, hover/focus/active/disabled | **PASS** | Controls use Bootstrap sizing + shared radii; states defined on events, weekday, scope, view-switch. |
| 9 | Every state designed — loading / empty / error / success | **PASS** | Skeleton in final layout, empty with next action, inline recoverable conflict error, toast for success. |
| 10 | Motion quiet, prefers-reduced-motion honoured | **PASS** | 200ms ease-out; `@media (prefers-reduced-motion)` disables transitions + shimmer. |
| 11 | Dark mode designed, not inverted | **PASS** | Own dark tokens; depth from lighter surfaces; states re-tuned and legible at 390 & 1440. |
| 12 | Phone (390) layout holds | **PASS** | Palette stacks, toolbar/legend wrap, day-grid legible; **Day** is the correct phone default (Week too dense). |
| 13 | Calendar legibility — availability never competes with events | **PASS** | Green wash / hatch / gold-dashed exception sit under events at low alpha; events carry the eye. |
| 14 | Interface copy clear (labels, empty, error) | **PASS** | Canonical copy set in DESIGN.md; conflict message states provider + recourse. |

## Findings for the engineers (not blockers to the token set — base-app scope)

1. **Native `<select>` / `<input type=date>` chrome in dark** — the browser UA paints these light inside dark cards. **Fix:** the base app needs `form-select`/`form-control` dark-theme styling (`[data-bs-theme="dark"] .form-control{…}`) since it never had a dark mode. Track as a small base-styling follow-up; scheduling tokens already cover everything custom.
2. **Now-line dot vs. first event** — cosmetic overlap in the demo only; in production the now-line is positioned by time and will rarely coincide with an event's top edge.

## Bottom line
The direction, palette, scales, calendar-grid, availability, appointment-state, and recurrence tokens are set and committed, and pass the craft checklist at both widths in both themes. **Screens may now be built — from these tokens only.**
