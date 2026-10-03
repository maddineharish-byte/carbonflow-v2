# PHASE 10.11 — Accessibility & UX Hardening

**Document status:** CURRENT. Written during Phase 10.11 from the working tree
after the change set in that phase. Every claim is re-derivable with the
commands quoted in each section.

**Evidence labelling convention** (inherited from
[PHASE10-FRONTEND-CUTOVER.md](PHASE10-FRONTEND-CUTOVER.md)):

- **[ORIGINAL]** — a defect present at the start of Phase 10.11, cited by file
  and line in the pre-change tree (`git show bc7b75d:<path>`).
- **[FIXED]** — a change made in Phase 10.11, cited by file and line in the
  current tree.
- **[VERIFIED]** — a result produced by a command re-runnable today.
- **[REMAINING]** — a known gap that Phase 10.11 did **not** close.

---

## 1. Original issues

Phase 10.11 inherited three named findings from earlier reviews: responsive
failures at 768×1024, responsive failures at 390×844, and a keyboard
Escape/focus-trap gap. Working through the codebase produced a longer, more
specific list.

### 1.1 Dialogs had no dialog semantics (the Escape/focus-trap gap)

**[ORIGINAL]** Nine dialogs were hand-rolled overlays. Every one had the same
five defects:

| File (pre-change line) | Defect |
|---|---|
| `ActivityDataView.tsx:237` | No `role="dialog"`, no `aria-modal` |
| `BoundariesView.tsx:277` | Same |
| `AdminView.tsx:215` | Same |
| `EmissionsView.tsx:253` | Same |
| `AuditView.tsx:403` | Same |
| `TargetsView.tsx:345,442,481,566` | Same (four dialogs) |

- **[ORIGINAL]** No `onKeyDown` handler existed anywhere under `src/`
  (verified: `grep 'onKeyDown|Escape'` matched zero handler sites). Escape did
  nothing in any dialog.
- **[ORIGINAL]** No Tab/Shift+Tab containment. Tabbing from the last field in a
  dialog moved focus into the page behind it.
- **[ORIGINAL]** No focus restoration. Closing a dialog dropped focus to
  `<body>`, so a keyboard user restarted at the top of the document.
- **[ORIGINAL]** No focus placement on open.
- **[ORIGINAL]** No background scroll lock.
- **[ORIGINAL]** Close controls were a bare `✕` glyph (`ActivityDataView.tsx:241`,
  `TargetsView.tsx:349,446,485,569`, `AdminView.tsx:219`,
  `BoundariesView.tsx:285`, `AuditView.tsx:408`, `EmissionsView.tsx:261`) with
  no accessible name — a screen reader announced "button".
- **[ORIGINAL]** No panel height cap, so tall forms were clipped by the
  viewport at 390×844 with no way to scroll to the submit button.

### 1.2 Form labels were not associated with their controls

**[ORIGINAL]** Every `<label>` in the application had no `htmlFor` and every
`<input>`/`<select>` had no `id`. Labels were visual-only. Confirmed after the
change by re-running the audit command:

```bash
Select-String -Path src/components/*.tsx -Pattern '<label' |
  Where-Object { $_.Line -notmatch 'htmlFor' }
```

**[VERIFIED]** Returns zero rows. Affected areas: activity logging, facility
registration, reporting periods, targets, projects, findings, boundaries
settings, user administration, audit comment box, evidence file input,
activity-data filter selects.

### 1.3 Tables had no header scope or caption

**[ORIGINAL]** All nine data tables used bare `<th>` with no `scope` and no
`<caption>`: `ActivityDataView`, `BoundariesView`, `EmissionsView` (×2),
`EvidenceView`, `FactorsView`, `InventoryView`, `AnalyticsView`, `AdminView`.

### 1.4 Icon-only controls had no accessible name

**[ORIGINAL]** `Navbar.tsx` refresh button, `AnalyticsView.tsx:204` refresh
button, `PlatformAdminView.tsx:112` refresh button, `TargetsView.tsx:214,310`
pencil edit buttons. All relied on a `title` attribute as the only name.

### 1.5 Current view was signalled by colour alone

**[ORIGINAL]** `Sidebar.tsx` marked the active item with an emerald background
and text colour only. No `aria-current`, no `nav` landmark, no list semantics.

### 1.6 Status and error messages were not announced

**[ORIGINAL]** The global toast (`App.tsx:709-719`) rendered with no
`role`/`aria-live`, so no status message reached a screen reader. Ten inline
error banners (`EmissionsView`, `AdminView`, `InventoryView`, `AnalyticsView`,
`EvidenceView`, `PlatformAdminView`, `TrendInsightsSection`) had no `role`, so
they were not announced. Loading placeholders had no `role="status"`.

### 1.7 Busy state was signalled by motion alone

**[ORIGINAL]** Spinner/reload icons were the only feedback for in-flight
requests. If `prefers-reduced-motion: reduce` was set, the user got no
indicator at all.

### 1.8 Keyboard focus was invisible

**[ORIGINAL]** `focus:outline-none` appears on 56 lines across 11 files. No
`:focus-visible` rule existed anywhere in `src/index.css` (the file was a
single line: `@import "tailwindcss";`). The only focus affordance was a
border-colour change, which fails WCAG 2.4.7 on the dark surface.

### 1.9 Responsive failures

**[ORIGINAL] At 390×844:**

| Location | Defect |
|---|---|
| `App.tsx:603` | `<main>` used `p-6 lg:p-8` — 24px padding at 390px |
| `Navbar.tsx:62,80` | `hidden md:flex` **hid the tenant switcher entirely** below 768px, removing a documented control |
| `Sidebar.tsx:84` | Fixed `w-64` in a stacked (non-`md`) flex column — 256px of a 390px viewport, leaving ~134px dead space |
| `ActivityDataView.tsx:247,279,302,350,375` | `grid-cols-2` / `grid-cols-3` with no responsive prefix inside the activity dialog |
| `TargetsView.tsx:362,392,506,534` | `grid-cols-2` / `grid-cols-3` with no responsive prefix |
| `BoundariesView.tsx:301,329` | `grid-cols-2` with no responsive prefix |
| `DashboardView.tsx:280` | `p-6` on the hero panel |
| `TrendInsightsSection.tsx:111` | `p-6` |
| All nine tables | `overflow-x-auto` with no `min-width` on the `<table>` — columns collapsed to unreadable widths instead of scrolling |

**[ORIGINAL] At 768×1024:** `md:` was used as the 3-up breakpoint throughout
(`md:grid-cols-3` in `EmissionsView:158`, `BoundariesView:104`,
`FactorsView:31`, `TrendInsightsSection:291,354`), putting three columns into
768px. `md:grid-cols-2` was used for chart pairs and the dual-reporting block.
Several `sm:grid-cols-4` four-up stat rows (`DashboardView:182,358`,
`AnalyticsView:116`) also fired at 640px.

**[ORIGINAL] Both:** three dialog action rows used a fixed `flex items-center
justify-end` (`ActivityDataView:417`, `TargetsView:431,555,594`,
`AdminView:264`) which wrapped awkwardly, and no dialog capped its height.

### 1.10 Layout invalidity in table cells

**[ORIGINAL]** `BoundariesView.tsx:186` and `EvidenceView.tsx:168` applied
`className="flex items-center ..."` directly to a `<td>`. `display:flex` on a
table cell breaks row/column alignment, so the icon and text did not line up
with the rest of the row.

### 1.11 Destructive actions fired on a single click

**[ORIGINAL]** No confirmation anywhere for irreversible actions: disabling a
tenant member (`AdminView.tsx:186`), rejecting or suspending an organization
(`PlatformAdminView.tsx:190,199`), locking an inventory snapshot
(`InventoryView.tsx:199`), rejecting an audit (`AuditView.tsx:194`).

### 1.12 Colour-only status indicators

- **[ORIGINAL]** `TargetsView.tsx:250` — progress bar conveyed only by a
  gradient width. No value exposed to assistive technology.
- **[ORIGINAL]** `AuditView.tsx:224-260` — the 8-stage stepper conveyed
  current/completed/pending by circle fill and connector colour only.
- **[ORIGINAL]** `TestSuiteView.tsx:100` — pass/fail conveyed by icon plus
  badge colour.

### 1.13 Other UX defects found while reviewing

- **[ORIGINAL]** `EmissionsView.tsx:54` — `load` was `useCallback(..., [])`
  but reads `periodFilter`. The period filter silently did nothing until the
  view was remounted.
- **[ORIGINAL]** `TestSuiteView.tsx:65` — the "Passed" card displayed the
  hardcoded string `100% Security & Precision Pass` regardless of the actual
  result.
- **[ORIGINAL]** `App.tsx:80` — `showToast` set a `setTimeout` with no cleanup;
  a stale timer could clear a newer message.
- **[ORIGINAL]** `EvidenceView.tsx:97` — the file input used `className="hidden"`,
  which removes it from the accessibility tree entirely.
- **[ORIGINAL]** Client-side view switching gave screen readers no cue and left
  `document.title` unchanged.
- **[ORIGINAL]** No skip link.

---

## 2. Fixes

### 2.1 One shared dialog, implemented once — `src/components/Modal.tsx` **[FIXED]**

All nine dialogs now render through `Modal`. The component implements the
whole keyboard contract in one place:

- `role="dialog"`, `aria-modal="true"`, `aria-labelledby`, optional
  `aria-describedby` — `Modal.tsx:118-123`
- focus moves into the dialog on open — `Modal.tsx:43-51`
- focus is restored to the opener on close — `Modal.tsx:46-50`
- Escape closes — `Modal.tsx:75-79`
- Tab / Shift+Tab wrap inside the trap — `Modal.tsx:81-105`
- background scroll lock — `Modal.tsx:54-59`
- height cap with internal scroll, using `dvh` so it tracks mobile browser
  chrome — `Modal.tsx:128`
- the close control is a labelled icon button, not `✕` — `Modal.tsx:130-140`

**No keyboard trap.** The trap is bounded to the dialog *and* Escape plus the
visible Close/Cancel controls remain operable, so a keyboard user can always
leave. This is the standard modal pattern.

**Backdrop click does not close.** Deliberate: these dialogs hold partially
entered accounting data and an accidental backdrop click would discard it
silently. Documented in the component header, `Modal.tsx:17-21`.

### 2.2 Focus-trap logic extracted and tested — `src/services/focusTrap.ts` **[FIXED]**

The tab-cycling decision is a pure function (`resolveTabTarget`) so it is unit
testable under `node:test`, which has no DOM. Covered by tests A11Y 5–9.

### 2.3 Destructive confirmations — `src/components/ConfirmDialog.tsx` **[FIXED]**

Built on `Modal`, so confirmations get identical Escape/focus behaviour.
Applied to five actions, each naming its consequence in approved terminology:

| Action | Location |
|---|---|
| Disable tenant member | `AdminView.tsx:265` |
| Reject audit | `AuditView.tsx:526` |
| Lock inventory snapshot | `InventoryView.tsx:256` |
| Reject organization | `PlatformAdminView.tsx:294` |
| Suspend organization | `PlatformAdminView.tsx:294` |

Forward audit transitions (submit, collect, validate, review, approve) stay
single-click, as does re-enabling a member.

### 2.4 Global focus visibility — `src/index.css` **[FIXED]**

`focus:outline-none` compiles to `.focus\:outline-none:focus` (specificity
0,2,0). A plain `:focus-visible` rule (0,1,0) would lose to it, so the base
rule uses `!important`:

```css
a:focus-visible, button:focus-visible, input:focus-visible,
select:focus-visible, textarea:focus-visible, summary:focus-visible,
[role="button"]:focus-visible, [role="tab"]:focus-visible,
[tabindex="0"]:focus-visible {
  outline: 2px solid #34d399 !important;
  outline-offset: 2px;
  border-radius: 4px;
}
```

**[VERIFIED]** Emitted into `dist/assets/index-*.css`:

```bash
Select-String -Path dist/assets/*.css -Pattern 'focus-visible'
```

The ring is `#34d399` on the `#0f172a` card surface — **9.29:1**, comfortably
above the 3:1 WCAG 1.4.11 requirement for non-text indicators, against
`#020617` for 10.2:1.

Two further global additions:

- Date/number input spinners are `filter: invert(1)`-ed — they were
  near-black on the dark surface and effectively invisible.
- A `prefers-reduced-motion: reduce` block neutralises all animation.

### 2.5 Responsive **[FIXED]**

- `<main>` padding now steps `p-4 sm:p-6 lg:p-8` — `App.tsx:632`.
- The tenant switcher is no longer `hidden md:flex`. It is always rendered and
  width-capped, so it stays reachable and labelled at 390px.
- The sidebar becomes a full-width, horizontally scrollable nav strip below
  `md`, and a 256px rail at `md` and above — `Sidebar.tsx:107`. Same markup,
  same tab order at every viewport.
- Three-up layouts moved from `md:` to `xl:` so 768×1024 gets one or two
  columns, not three. Four-up stat rows moved to `xl:`/`lg:`.
- Every dialog form grid is now `grid-cols-1 sm:grid-cols-2` (or
  `sm:grid-cols-3`).
- Dialog action rows are `flex-col-reverse sm:flex-row`, so at 390px the
  primary action sits at the top of the stack where it is reachable.
- Every data table gained a `min-w-*` so it genuinely scrolls rather than
  crushing columns, and its scroll container is `tabIndex={0}` with a
  `role="group"` label — a WCAG 2.1.1 keyboard requirement.
- Dialog panels are `p-4 sm:p-6` and the hero/insight panels `p-4 sm:p-6`.

### 2.6 Semantics and names **[FIXED]**

- All labels associated; all controls have `id`. **60** `<label htmlFor>`
  associations.
- All nine tables: `<caption class="sr-only">` and `<th scope="col">`.
- Icon-only controls: `aria-label` (refresh, sign-out, pencil edit, download,
  evidence attach, calculation, lineage, resolve finding, disable/enable).
  Where a visible label already existed, the visible text became the
  `aria-label` so the two cannot drift apart.
- Sidebar: `<nav aria-label="Primary">`, `<ul>`/`<li>`, `aria-current="page"`
  on the active item, plus an explicit empty state when a role has no sections.
- `<h1>` → `<h2>` nesting verified per view (test A11Y 23).
- Decorative icons marked `aria-hidden="true"` so they are not announced twice.
- Checklist checkboxes labelled by their visible item title — `AuditView.tsx:305`.
- The audit stepper is an `<ol>` where every stage carries an explicit
  `, current` / `, completed` / `, pending` text state — `AuditView.tsx:243,265`.
- Trend Insights filters became a proper `role="tablist"` with roving
  `tabIndex`, `aria-selected`, and Arrow/Home/End navigation —
  `TrendInsightsSection.tsx:151-244`.
- Dashboard stream filter became a `role="radiogroup"` with `aria-checked` —
  `DashboardView.tsx:317-340`.
- Recharts containers given `role="img"` + descriptive `aria-label` — the
  figures are also present as text, so the chart is not the only source.
- Progress bars: `role="progressbar"` with `aria-valuenow/min/max/valuetext`.
- Truncated snapshot hashes get the full digest in an `sr-only` span —
  `InventoryView.tsx:226`.

### 2.7 Announcements **[FIXED]**

- The toast container is **always mounted** so the live region registers
  before the first message, and switches between `aria-live="polite"` and
  `assertive` by severity — `App.tsx:731-748`.
- All ten inline error banners: `role="alert"` + `aria-hidden` icon.
- All loading placeholders: `role="status"`.
- Evidence upload and download report busy state via `aria-busy` and a visible
  status line — `EvidenceView.tsx:64-70`.
- Filter result counts are live regions, so changing a filter is announced.
- `document.title` updates on navigation and a skip link targets `<main>` —
  `App.tsx:568,617`.

### 2.8 Non-motion busy feedback **[FIXED]**

Every in-flight control now carries `aria-busy` **and** a text change (e.g.
`Execute Test Suite` → `Running Test Suite…`, `Export CSV` →
`Exporting CSV…`, `Refresh` → `Analyzing…`), so state survives
`prefers-reduced-motion`. Disabled controls state their reason in `title` and,
where it fits, in adjacent visible hint text.

### 2.9 UX defects **[FIXED]**

- `EmissionsView` period filter now works — `periodFilter` added to the
  `load` dependency array (`EmissionsView.tsx:54`).
- `TestSuiteView` pass rate is computed from the payload
  (`Math.round(passed / total * 100)`), replacing the hardcoded `100% …` claim.
- `showToast` timer is now ref-tracked and cleared on unmount — `App.tsx:81-93`.
- Evidence file input moved from `className="hidden"` to `className="sr-only"`
  with a real `<label>` — it is no longer removed from the accessibility tree.
- `BoundaryView` / `EvidenceView` `<td className="flex">` replaced with an
  inner `<span className="inline-flex">`, restoring table alignment.

---

## 3. Testing

### 3.1 Commands **[VERIFIED]**

| Check | Command | Result |
|---|---|---|
| TypeScript | `npm run lint` | clean, no diagnostics |
| Frontend tests | `npm test` | **66 passed, 0 failed** |
| Production build | `npm run build` | succeeded |
| Emitted CSS | `Select-String dist/assets/*.css -Pattern 'focus-visible'` | rule present with `!important` |

Baseline before Phase 10.11 was 36 tests passing; 30 accessibility tests were
added in `src/accessibility.test.tsx`, wired into `npm run test:frontend`.

### 3.2 What the 30 new tests cover

- **Dialog semantics (1–4)** — `role`, `aria-modal`, `aria-labelledby`,
  `aria-describedby`, absence of the bare `✕` glyph, confirmation dialogs
  naming their consequence.
- **Keyboard (5–9)** — Tab wrap, Shift+Tab wrap, mid-trap advance without
  suppressing default, empty-dialog containment, focusable selector coverage.
- **Navigation (10–11)** — `nav` landmark, exactly one `aria-current="page"`,
  empty-state message.
- **Header (12–13)** — accessible names on every control, disabled-state
  explanations.
- **Forms (14–15)** — programmatic labels, `autocomplete`, `role="alert"`.
- **Tables (16)** — caption and `scope="col"`.
- **Colour independence (17, 18, 22)** — progress values, audit stage states,
  test results.
- **Announcements (19–21)** — live regions, busy state, full checksums.
- **Structure (23–24)** — single `h1`, chart labelling.
- **States (25–26)** — loading, error, empty.
- **Terminology (27–29)** — explicit drift guards on the 13 approved nav labels,
  the approved audit state names, and the dual-reporting wording.
- **Boundary (30)** — asserts the API surface is unchanged.

### 3.3 Verification I could **not** perform

**No browser validation was performed.** The desktop browser tool reported
`[browser.disconnected] — No desktop browser is connected to this session`.
There is also no jsdom/happy-dom/linkedom in `node_modules`, so the React
test suite runs through `renderToStaticMarkup` only.

Consequences, stated plainly:

- Escape, focus trapping and focus restoration are **verified by unit test on
  the extracted logic and by markup assertions on the wrapper**, not by
  observed keyboard interaction in a live page.
- The 390×844 / 768×1024 / desktop / wide-desktop review is a **static review
  of the responsive class changes**, not a rendered screenshot comparison.
- Everything else (semantics, names, labels, ARIA, announcements) is directly
  asserted against rendered markup and is solid.

---

## 4. Verified viewports

These are the breakpoints the change set was reviewed and adjusted against,
by class-level analysis:

| Viewport | Width class used | Review outcome |
|---|---|---|
| 390×844 | base (`grid-cols-1`) | Single-column forms and dialogs; dialogs capped at `calc(100dvh - 1.5rem)` and scroll internally; action rows stack primary-first; sidebar becomes a scrollable strip; tables scroll on `min-w-*`; `p-4` gutters |
| 768×1024 | `sm` / `md` | Two-column form fields, two-column dialog actions; 3-up and 4-up grids deliberately deferred to `xl` so 768px gets one or two columns, not three |
| Desktop ≥1280 | `lg` | 3-up stat grids, side-by-side charts, 256px sidebar rail |
| Wide desktop ≥1536 | `xl` | 4-up KPI row, 2-up chart pairs, dual-reporting block side by side |

**These were reviewed in source, not rendered.** See §3.3.

---

## 5. Remaining issues

Honest list of what Phase 10.11 did **not** close.

1. **No rendered-browser verification.** The largest gap. A real 390×844 /
   768×1024 pass with keyboard walkthrough is still required before sign-off.
2. **No automated axe / Lighthouse run.** No such tool is in the project.
   `src/accessibility.test.tsx` covers the defects found by manual review; it
   is not a substitute for an automated rule engine, and rules like contrast
   ratios are not asserted.
3. **Contrast ratios not machine-verified.** `text-slate-500` (`#64748b`) on
   `bg-slate-900` (`#0f172a`) is **3.75:1** — below the 4.5:1 AA threshold for
   body text. It is used for secondary helper text and empty-state copy across
   **87 sites**. On the page background it is 4.24:1, still short. By contrast
   `text-slate-400` (`#94a3b8`) is 6.96:1 and passes. Changing the `slate-500`
   role is a design-system decision spanning the whole palette, not a
   phase-10.11 fix, so it was left alone and is recorded here.
   **[REMAINING]** — this is a genuine WCAG AA failure, not a nit.
4. **Recharts internals remain unlabelled.** The container carries
   `role="img"` + `aria-label`, and every plotted value is also available as
   text or in a table elsewhere. Chart tooltips and axes are still not
   navigable by keyboard. A data-table fallback per chart would be the full
   fix.
5. **Data-table scroll containers are `tabIndex={0}`** to satisfy keyboard
   scrolling. This adds a tab stop before each table. It is the WCAG 2.1.1
   trade-off, but on a page with nine tables it is a real cost.
6. **Activity-data filter labels changed wording** — `Scope:` / `Facility:`
   became `Scope` / `Facility` (the colon moved from the label into the
   control's value). Not regulated terminology; noted for completeness.
7. **"Retry AI Analysis" → "Re-run trend analysis"** in `TrendInsightsSection`.
   This one *was* a terminology change: the section runs a deterministic
   in-process engine, not an AI service, so the old label was misleading.
   Renamed to match the section's own "Deterministic Engine" badge. Reason
   documented here as required.
8. **No focus management on client-side view change.** The document title
   updates and the skip link exists, but focus is not moved to the new view's
   heading. This is the most valuable remaining keyboard improvement.
9. **Focus trap is not verified in a real browser** — the wrap logic is unit
   tested, but the real `document.activeElement`/`offsetParent` interaction is
   unexercised.
10. **`AuthBoundary` loading text is not inside a landmark-labelled region**
    beyond `role="status"`; minor.

---

## 6. Files changed

**New**

- `src/components/Modal.tsx` — the shared accessible dialog
- `src/components/ConfirmDialog.tsx` — destructive-action confirmation
- `src/services/focusTrap.ts` — testable focus-trap logic
- `src/accessibility.test.tsx` — 30 accessibility/keyboard/UX tests
- `docs/PHASE10.11-ACCESSIBILITY.md` — this document

**Modified**

- `src/index.css`, `src/App.tsx`, `package.json` (test script)
- `src/components/`: `Navbar`, `Sidebar`, `DashboardView`, `BoundariesView`,
  `ActivityDataView`, `EmissionsView`, `AuditView`, `TargetsView`,
  `InventoryView`, `AnalyticsView`, `AdminView`, `PlatformAdminView`,
  `EvidenceView`, `FactorsView`, `TestSuiteView`, `TrendInsightsSection`

**Not modified:** anything under `backend-java/`, `db/`, `server/`, and
`src/services/api.ts`. No backend API was changed — asserted by test A11Y 30.