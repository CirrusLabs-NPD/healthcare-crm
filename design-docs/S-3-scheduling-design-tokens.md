# Scheduling UI — Design Direction & Tokens (S-3)

> **Direction:** *Soft-Blue Calm Admin* — the system this app already ships.
> Scheduling **extends** it; it does not introduce a new look. Every calendar,
> availability and recurrence screen is assembled from the tokens in
> `static/css/scheduling.css`, which sit on top of `static/css/custom.css`.
> **A literal colour, pixel, radius or type size in a scheduling template is a defect.**

## Reference followed
The existing product. Read before choosing anything:
- `templates/admin/layout.html` — Bootstrap **5.1.3** + Bootstrap Icons + Inter, sidebar admin shell.
- `static/css/custom.css` — the base tokens (Bootstrap CSS variables in `:root`), the soft-blue palette, the two radii, the three elevations, the **already-designed 2px accent focus ring**.
- `templates/admin/tasks.html`, `addTask.html` — table density, card language, form patterns, `datetime-local` (the field this feature replaces per S-1/S-2).

No new framework, font, icon set or component library is added. Calendar screens are **server-rendered Thymeleaf**, consistent with S-2.

## Stack (brownfield — the project's own system, extended)
| | |
|---|---|
| Framework | Bootstrap 5.1.3 (no swap, no second UI library) |
| Typeface | **Inter** → `Inter, Roboto, system-ui, -apple-system, "Segoe UI", …` (never the UA serif) |
| Icons | **Bootstrap Icons** (the set already loaded) — not lucide, to avoid mixing icon sets |
| Rendering | Thymeleaf templates + these two CSS files |

## Type scale — 32 / 24 / 20 / 16 / 14 / 12
`--sch-fs-h1 … --sch-fs-caption`. Headings differ in **size + weight + colour**; secondary info is muted (`--bs-body-color-muted`), never merely smaller. Grid gutter times and event meta are 12px caption.

## Spacing — 4px scale
`--sch-space-1..8` = 4/8/12/16/24/32. Gaps come only from these. Content sits inside the app's centred shell (max-width 1200px + page padding); nothing runs edge to edge.

## Radii & elevation (reused, not reinvented)
- Radii: `--sch-radius` (0.375rem, events/chips), `--sch-radius-lg` (0.5rem, panels) — the base app's two radii.
- Elevation: `--sch-elev-1/2/3` — resting card, hovered event, popover.

## Motion
One duration + easing: `--sch-motion` (200ms) / `--sch-ease` (ease-out). Hover/open/close only, nothing bounces, `prefers-reduced-motion` fully honoured (transitions + shimmer disabled).

## Palette — both themes (tokens carry dark values; dark is designed, not inverted)
| Role | Light | Dark |
|---|---|---|
| Primary | `#5677a4` | `#8aadd1` |
| Accent (primary actions + focus ring) | `#fdb813` | `#fdb813` |
| App background | `#f4f6f9` | `#0f151d` |
| Raised surface / panel | `#ffffff` | `#182230` |
| Border (hairline) | `rgba(16,24,40,.08–.16)` | `rgba(230,235,242,.08–.16)` |
| Body text | `#212529` | `#e6ebf2` |
| Muted text | slate-500 | `#94a2b8` |

> **The base app had no dark theme.** Scheduling introduces the first dark tokens, opt-in via `data-bs-theme="dark"` on `<html>`. Depth in dark comes from lighter surfaces, not heavier shadows. All state colours re-checked for ≥ 4.5:1 body-text contrast.

At most five hues on any screen: neutral surfaces, the primary, the gold accent (sparingly), plus the semantic set below.

## Appointment state tokens (the business states from S-2)
Each state is a **fill + a 3px left accent bar + readable text** — never colour alone.

| State | Bar (light) | Meaning |
|---|---|---|
| **Scheduled** | primary | booked, upcoming |
| **In progress** | info | happening now |
| **Completed** | success | done |
| **Cancelled** | secondary | struck through, de-emphasised |
| **Conflict** | danger | overlaps another booking — **flagged, never dropped** (S-2 policy) |
| **Deadline** (`type=DEADLINE`) | gold dot | a marker, not a timed block — preserves today's deadline-only tasks |

## Calendar grid tokens (day / week)
- Hour row height `--sch-grid-hour-h` (48px), time gutter `--sch-grid-time-w` (64px).
- Hairline dividers `--sch-grid-line`; stronger day separators `--sch-grid-line-strong`.
- Today's column washed with `--sch-today-tint`; "now" is a 2px `--sch-now-line`.
- **Availability display:** `--sch-avail-bg` green wash = provider bookable; `--sch-unavail-bg` diagonal hatch = off-hours; `--sch-exception-*` gold dashed = a dated exception (PTO). These sit **under** events and never compete with them.
- Default view: **Week on desktop, Day on phone** (verified at 390 — the week grid is too dense for a phone).

## Recurrence editing tokens
- Weekday toggles `.sch-recur__day` (40px circular, pressed = primary).
- A plain-language `.sch-recur__summary` ("Every week on Mon, Wed, Fri until 18 Dec — about 40 appointments").
- **Scope picker** `.sch-scope` with the three S-2 edit scopes: *This appointment* / *This and following* / *The whole series*.

## Every state is designed
- **Loading:** `.sch-skeleton` shimmer in the final calendar layout — not a spinner in a void.
- **Empty:** `.sch-empty` says what goes here and offers *New appointment*.
- **Error:** inline, specific, recoverable — the booking-conflict alert names the provider and offers the recourse (pick another time / book anyway and flag).
- **Success:** confirm with the app's existing toast pattern.

## Interface copy (canonical labels — build to these)
| Context | Copy |
|---|---|
| Primary action | **New appointment** |
| View switch | **Day** / **Week** |
| Availability states | **Bookable** / **Off** / **Exception** |
| Recurrence | **Repeats** · **On these days** · **Ends** |
| Scope | **This appointment** / **This and following** / **The whole series** |
| Empty day | "No appointments this day — this provider has no bookings on {date}." |
| Booking conflict | "That slot overlaps an existing appointment for {provider}. Pick another time, or book anyway and flag it as a conflict." |

## Files
- `static/css/scheduling.css` — the tokens + components (this is the contract).
- `static/scheduling-reference.html` — the living reference; every token rendered. Open it, toggle the theme, resize to 390.
- `design-docs/S-3-scheduling-design-review.md` — the craft-checklist review at 1440 & 390 in both themes.

## Rule for the engineers
Assemble screens from the classes above. If a screen needs something the system does not have, add a **token + a component class here first**, then use it — do not inline a value in a template.
