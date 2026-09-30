# CarbonFlow — UI/UX Audit

**Generated:** 2026-09-27
**Basis:** `ui-ux-pro-max` skill (`ui-ux-pro-max-cli` v2.15.0) + static analysis of `src/`
**Design system of record:** `design-system/carbonflow/MASTER.md`
**Scope:** Audit only. No P1/P2/P3 changes are authorized by this document.

> **Handling instruction:** This audit is documentation, not authorization for a frontend
> redesign. Phase 6 — Carbon Accounting Core remains the active development phase.
> Only the confirmed P0 Tailwind defect may be fixed. P1, P2 and P3 remain open.

---

## Method

- Source scan of all `src/**/*.tsx` (17 files) for class usage, ARIA, focus handling,
  heading structure, table semantics, motion and hardcoded colors.
- **Verification against build output**, not source alone: `npm run build`, then grep of
  `dist/assets/index-*.css` for emitted utilities.
- WCAG contrast ratios computed from hex values using the WCAG 2.1 relative-luminance formula.
- Recommendations cross-checked against the skill's own datasets
  (`ux-guidelines.csv`, `charts.csv`, `stacks/html-tailwind.csv`) via `scripts/search.py`.

### Project context

- React 19 + Vite + **Tailwind v4** (`@tailwindcss/vite`), Java 21 / Spring Boot REST API.
- Dark theme: `bg-slate-950` page, `bg-slate-900` cards, emerald/sky accents.
- Fonts loaded in `index.html`: Plus Jakarta Sans + JetBrains Mono.
- Single authenticated shell with state-based view navigation (no URL routing).

---

## P0 — Styling that is genuinely broken

### `bg-slate-850` does not exist in Tailwind v4

Verified: `dist/assets/index-*.css` contains `slate-850`: **0** occurrences
(`slate-800`: 21). Tailwind v4's theme jumps 800 → 900, so there is no `--color-slate-850`.

**All 21 usages emit no CSS at all** — the affected elements render transparent.

| # | Impact | Locations |
|---|--------|-----------|
| 1 | **5 table headers lose their background** — header row indistinguishable from body rows | `ActivityDataView.tsx:136`, `BoundariesView.tsx:131`, `EmissionsView.tsx:106`, `EvidenceView.tsx:126`, `FactorsView.tsx:92` |
| 2 | **8 dead row hovers** — no hover feedback on any list row | `ActivityDataView.tsx:150`, `AuditView.tsx:219`, `BoundariesView.tsx:143`, `EmissionsView.tsx:122`, `EvidenceView.tsx:138`, `FactorsView.tsx:108`, `TargetsView.tsx:86`, `TestSuiteView.tsx:88` |
| 3 | Solid inset panels render transparent | `AuditView.tsx:244`, `AuditView.tsx:308`, `EmissionsView.tsx:193`, `EmissionsView.tsx:201`, `TargetsView.tsx:41` |
| 4 | Progress-bar track renders transparent | `TargetsView.tsx:62` |
| 5 | `to-slate-850` gradient stop renders transparent | `DashboardView.tsx:254` |
| 6 | Nested table body transparent, header already `slate-800` | `EmissionsView.tsx:213` |

**Variant tally:** `bg-slate-850` ×12, `hover:bg-slate-850/50` ×8, `to-slate-850` ×1.

*Sanity scan:* no other undefined shade exists in the codebase — `slate-850` is the only
bad step (`\b(slate|emerald|sky|amber|rose|orange|indigo)-(150|250|350|450|550|650|750|850)\b`
returns only `slate-850` hits).

*Suggested fix:* map `850` → an existing valid shade (`slate-800` where visually
appropriate). Do **not** introduce a new design token unless there is strong reason.

**Status:** fixed — see [P0 Resolution Log](#p0-resolution-log).

---

## P1 — Accessibility (skill severity: High/Critical)

1. **Every data-entry form is unlabelled.**
   19 `<label>` elements in `ActivityDataView` (8), `BoundariesView` (8), `AuditView` (3)
   have **no `htmlFor`**, and their inputs have **no `id`** → nothing programmatically
   associated. 12 inputs rely on placeholder only.
   `LoginView.tsx:45-71` is the correct pattern (`htmlFor` + `id` + `autoComplete`) — it is
   the only screen done right.
   *Skill rule: "Form Labels" — Severity High.*

2. **All 4 modals are inaccessible** — `ActivityDataView.tsx:213`, `AuditView.tsx:341`,
   `BoundariesView.tsx:170`, `EmissionsView.tsx:168`:
   no `role="dialog"`, no `aria-modal`, no `aria-labelledby`,
   **zero `onKeyDown` in the entire codebase** (no Escape), no focus trap, no focus restore.
   Close buttons are a text glyph `U+2715` — not an SVG icon, no `aria-label`.

3. **Icon-only buttons without accessible names** — Navbar refresh (`Navbar.tsx:131`) uses
   `title=` only, which is not a reliable accessible name; plus the 3 close buttons
   (`ActivityDataView.tsx:217`, `AuditView.tsx:345`, `EmissionsView.tsx:175`).
   *Skill rule: "ARIA Labels" — Severity High.*

4. **Focus indicators removed without replacement** — 23× `focus:outline-none` downgraded to
   a 1px `focus:border-emerald-500` color change; and 8 files have **zero** focus styling
   (DashboardView, EmissionsView, EvidenceView, FactorsView, TargetsView, TestSuiteView,
   TrendInsightsSection, Sidebar).
   *Skill rule: "Focus States" — "Don't remove focus outline without replacement", Severity High.*

5. **46 `<th>`, zero `scope`, zero `<caption>`** → screen readers cannot associate cells with
   headers. Tables themselves are fine (6/6 wrapped in `overflow-x-auto`).

6. **Toast not announced** — `App.tsx:601` has no `aria-live`/`role="status"`, so
   success/error toasts are invisible to assistive technology.

7. **No reduced-motion support** — 0 hits for `prefers-reduced-motion`/`motion-reduce`
   despite 9 animated elements, including `animate-bounce` on export icons
   (`DashboardView.tsx:139`, `EmissionsView.tsx:59`), which the skill calls out explicitly.
   *Skill rules: "Reduced Motion" (High), "Continuous Animation" (Medium).*

8. **Charts have no accessible path** — Recharts with no `accessibilityLayer`, no data-table
   fallback. *Skill chart guidance: "Visible KPI/target table plus status summary; keyboard
   focus reveals the same detail as hover."*

**All P1 items: NOT FIXED — deferred by instruction.**

---

## P2 — Contrast (measured)

`text-slate-500` — **40 uses**, all fail AA for normal text:

| Pair | Ratio | Verdict |
|---|---|---|
| slate-500 on slate-900 | **3.75:1** | fail |
| slate-500 on slate-950 | **4.24:1** | fail |
| slate-500 on slate-800 | **3.07:1** | fail |
| slate-400 on slate-800 | 5.71:1 | PASS AA |
| slate-400 on slate-900 | 6.96:1 | PASS AA |
| slate-400 on slate-950 | 7.87:1 | PASS AA |
| slate-300 on slate-800 (form labels) | 9.85:1 | PASS AA |
| emerald-400 on slate-900 | 9.29:1 | PASS AA |
| emerald-300 on emerald-950 (badge) | 9.78:1 | PASS AA |
| rose-300 on rose-950 (badge) | 8.27:1 | PASS AA |

Everything else passes — the palette is healthy; only the `-500` step is the problem.
(`disabled:text-slate-500` is exempt as inactive UI.)

**All P2 items: NOT FIXED — deferred by instruction.**

---

## P3 — Design system vs. reality (needs authorization)

- **Typography conflict:** `MASTER.md` specifies Fira Sans/Fira Code; `index.html:14` loads
  **Plus Jakarta Sans + JetBrains Mono**. Approved direction is "keep current", so `MASTER.md`
  should be amended — but the skill forbids editing `MASTER.md` without explicit authorization.
- **`MASTER.md` component specs are light-mode and would break the dark UI if followed
  literally:** `.btn-secondary` text `#0F172A` (near-black on dark), `.input` border `#E2E8F0`
  (light gray), `.modal` background `white`, `.card` background `#020617` (identical to page
  bg → invisible cards).
- **Pattern mismatch:** the generator returned *"Enterprise Gateway"* — a marketing landing
  pattern with a "Contact Sales" CTA. CarbonFlow is an authenticated app shell; only the
  Color/Style/Typography sections apply.
- **Accent mismatch:** `MASTER.md` `#22C55E` vs. the app's `emerald-500`
  (`oklch(69.6% 0.17 162.48)`). One mapping decision is needed to define `--color-accent`.
- **`cursor:pointer` on only 3 elements** across 47 buttons — the built CSS confirms no
  UA/preflight default, so buttons show `cursor: default`.
- **Responsive at 375px unverified** — the header has no wrap/overflow handling
  (`Navbar.tsx:56` `justify-between`, role switcher always visible).
  *Skill checklist: "No horizontal scroll on mobile."*

**All P3 items: NOT FIXED — deferred by instruction.**

---

## Positives

- No emoji icons anywhere (0 pictographs across all 17 files).
- No clickable `<div>`/`<span>` — all interactive elements are real `<button>` elements.
- Clean heading hierarchy: one `<h1>` per view, then `<h2>`/`<h3>`.
- Login screen implements auth semantics correctly (`autoComplete`, `role="alert"`,
  `htmlFor` + `id`).
- 6/6 tables wrapped in `overflow-x-auto`.
- Palette contrast is broadly healthy (see P2 table).
- `slate-850` is the only undefined shade in the codebase.

---

## Suggested priority order

1. **P0** — one-token fix, large visual win, zero risk. ✅ *done*
2. **P1.1 / P1.2** — form labels + modal accessibility (highest user impact).
3. **P2** — `text-slate-500` contrast.
4. **P3** — design-system reconciliation and polish.

---

## P0 Resolution Log

*Appended after the audit; does not modify the findings above.*

**Change:** replaced the undefined `slate-850` step with existing valid Tailwind v4 shades.
No new design tokens were introduced.

| Context | Replacement | Rationale |
|---|---|---|
| 5 × `<thead>` | `bg-slate-800` | Cards are `bg-slate-900`; 800 keeps the header lighter than the body. Matches the sibling `EmissionsView.tsx:214`, which already used `bg-slate-800`. |
| 8 × row hover | `hover:bg-slate-800/50` | Ancestors are `bg-slate-900`; a lighter hover is visible. Matches the existing `hover:bg-slate-800` idiom in `Navbar.tsx:137`, `Navbar.tsx:146`, `Sidebar.tsx:72`. |
| 7 × solid inset panel / track | `bg-slate-800` | `AuditView.tsx:244`, `AuditView.tsx:308`, `EmissionsView.tsx:193`, `EmissionsView.tsx:201`, `TargetsView.tsx:41`, `TargetsView:62`. All sit on `bg-slate-900` cards; 800 keeps the panel visibly raised. |
| 1 × nested table body | `bg-slate-900` | `EmissionsView.tsx:213`. Its `<thead>` (line 214) is already `bg-slate-800` and its `divide-y` is `divide-slate-800`; mapping to 800 would flatten both the header and the row dividers. 900 preserves the app-wide "900 body + 800 header" pattern. |
| 1 × gradient stop | `to-slate-800` | `DashboardView.tsx:254`. `from-slate-900 via-slate-900 to-…`: 800 is the nearest valid *lighter* step, preserving the intended subtle fade direction (850 sits between 800 and 900). |

**Verification:** `slate-850` returns 0 matches across `src/`; `npx tsc --noEmit` and
`npm test` pass.

**Explicitly NOT changed:** all P1 accessibility items, all P2 contrast items, all P3
design-system items, `MASTER.md`, typography, accent tokens, global `cursor:pointer`.
