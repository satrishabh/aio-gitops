# AIO portal redesign: brief

Status: agreed 2026-11-20, not started. Branch `feat/ui-redesign` (cut from `main` at 9c6a478).
Scope: `platform/frontend` only (React 18 + Vite, no UI library). Backend changes only where a section below says so.

## Decisions (from the owner)

1. **Three themes**, chosen under Account → Appearance:

   | Theme | Source mockup | Character |
   | --- | --- | --- |
   | **Default** (the default for everyone) | [`mockups/default-flow.html`](mockups/default-flow.html) | Light, soft colour, pipeline-first |
   | **Bright** | [`mockups/bright-signal.html`](mockups/bright-signal.html) | Light, bold type, black borders, hard shadows, colour blocks |
   | **Dark** | [`mockups/dark-control-room.html`](mockups/dark-control-room.html) | Dark, lime accent, monospace numbers, data-dense |

   Plus **Auto**: the device is light → Default, the device is dark → Dark.
2. **One layout for all themes: Flow's (Default's).** That means top navigation with 5 groups, a search box, and the Overview built around the delivery pipeline. Themes change tokens, fonts and card treatment, never the page structure.
3. **Fix the home page first.** The home page is the Overview (`/`, `pages/Dashboard.jsx`). Also restyle the sign-in page (`pages/Login.jsx`), which is the first page logged-out visitors see.
4. **Then the rest,** in this order: Metrics, the project and pipeline flow, Connectors, then a polish pass over every page.

The mockups are design-canvas files (`.dc.html`). Their markup and the `renderVals()` data show the exact colours, sizes and layout. They don't run on their own, so treat them as specs. All their numbers are sample data.

## How to work

- One PR per phase below, into `main`. Keep each PR reviewable; split it if it passes about 1,500 changed lines.
- Each PR must pass `cd platform/frontend && npm ci && npm run build`.
- Each PR description includes screenshots of the changed pages in all three themes, at 1280 px and at 390 px (phone) width.
- Accessibility rules carry over from the current code: text contrast at least 4.5:1, at least 3:1 for large numbers, in every theme; touch targets at least 44 px; visible focus rings; the skip link; focus moves to `<main>` on navigation; the mobile navigation's focus trap and Esc to close; and `data-label` attributes so tables stack on phones. Never tell states apart by hue alone; pair colour with a word or icon.
- Respect `prefers-reduced-motion` and `prefers-contrast: more`, as `styles.css` does today.
- Access tiers stay exactly as in `App.jsx`. `community` sees Overview, Projects and Metrics only, read-only. Clusters and Connectors are admin only.
- Deploying is not part of this work. It's done from the owner's laptop: a Tekton build-only run, then `helm upgrade` changing only `image.digest`, after a Postgres backup. See `docs/AIO-CVE-FIX-RELEASE-2026-11-19.md`.

## Phase 1: theme system and app shell

### Theme tokens

Rewrite the `:root` tokens at the top of `platform/frontend/src/styles.css` as three theme blocks keyed on `data-theme="default" | "bright" | "dark"`.

- **Keep the existing variable names** (`--bg`, `--panel`, `--line`, `--text`, `--muted`, `--accent`, `--green`, `--red`, `--amber`, `--*-bg`, `--radius`, `--shadow`, …) so the current pages still render while they're redone.
- **Add these variables:** `--font-display`, `--font-body`, `--font-mono`, `--border-w`, `--card-shadow`, `--radius-lg`, `--radius-sm`, `--ink-panel` (the dark "Needs you" card), `--on-accent`, and `--ok-text`, `--bad-text`, `--warn-text` (for text, where the fill colour is too light to read).

| Token | Default (Flow) | Bright (Signal) | Dark (Control room) |
| --- | --- | --- | --- |
| page `--bg` | `#F3F4F8` | `#FFFFFF` | `#0B0F14` |
| card `--panel` | `#FFFFFF` | `#FFFFFF` | `#121820` |
| raised/nav `--panel-2` | `#F3F4F8` | `#F0F0F0` | `#18212B` (nav bar `#0E131A`) |
| `--line` / `--line-2` | `#E3E5EE` / `#D5D8E3` | `#0A0A0A` / `#0A0A0A` | `#1F2A36` / `#2A3644` |
| `--border-w` | `1px` (cards borderless) | `2px` (sections 2px, header rule 3px) | `1px` |
| `--text` | `#161A2B` | `#0A0A0A` | `#E6EDF3` |
| `--text-2` | `#4A5168` | `#3A3A3A` | `#A9B4BF` |
| `--muted` | `#5C6378` | `#3A3A3A` | `#8B98A5` |
| `--accent` (buttons, active) | `#5B3FE6` | `#3D2BFF` | `#C6F432` |
| `--accent-hover` / link text | `#3B22B8` | `#2B1BE0` | `#DEFF7A` |
| `--accent-tint` | `#EEEAFF` | `#E8E5FF` | `rgba(198,244,50,0.14)` |
| `--on-accent` | `#FFFFFF` | `#FFFFFF` | `#0B0F14` |
| ok fill / `--ok-text` / tint | `#1FAF8E` / `#0E7C6F` / `#D9F5EC` | `#00B377` / `#0A0A0A` / `#C9F7D9` | `#C6F432` / `#C6F432` / `rgba(198,244,50,0.14)` |
| info (running, syncing) | `#5B3FE6` | `#3D2BFF` | `#4CC9F0` |
| bad fill / `--bad-text` / tint | `#E2553F` / `#A8321F` / `#FFE3DC` | `#FF5A1F` (black text on it) / `#0A0A0A` / `#FFD9C7` | `#FF6B6B` / `#FF8A8A` / `rgba(255,107,107,0.16)` |
| warn / `--warn-text` / tint | `#F2A93B` / `#8A5300` / `#FFF0C9` | `#FF5A1F` / `#0A0A0A` / `#FFD9C7` | `#FFB547` / `#FFB547` / `rgba(255,181,71,0.16)` |
| off (scaled down, idle) | `#C9CDD9` | `#F0F0F0` | `#5B6B7A` |
| `--ink-panel` | `#161A2B` (text `#FFFFFF`, sub `#C3C7D6`) | `#0A0A0A` | `#18212B` with `--line-2` border |
| `--radius-lg` / `--radius` / `--radius-sm` | `22px` / `18px` / `12px` | `0` / `0` / `0` | `14px` / `10px` / `9px` |
| `--card-shadow` | `0 1px 2px rgba(22,26,43,.05), 0 8px 28px rgba(22,26,43,.06)` | `5px 5px 0 #0A0A0A` (primary buttons `4px 4px 0`) | `none` |
| `--font-display` | Manrope 800 | Bricolage Grotesque 800 (KPI numbers 56 px) | Space Grotesk 600 |
| `--font-body` | Manrope | Inter | Inter |
| `--font-mono` (numbers, tags, labels) | DM Mono | IBM Plex Mono | JetBrains Mono (KPI values 34 px, uppercase labels with 0.08–0.12em tracking) |

Check every pair against the contrast rule. If one fails, darken the text token, never the fill.

**Fonts:** self-host them with npm `@fontsource` / `@fontsource-variable` packages (Manrope, DM Mono, Bricolage Grotesque, Inter, IBM Plex Mono, Space Grotesk, JetBrains Mono), imported in `main.jsx`. Don't use the Google Fonts CDN: it would send visitors' IPs to Google and needs network access at runtime. Check each package name on npm before adding it, and load only the weights the tokens use.

### Theme switching

- **`appearance.js`:** valid values become `auto | default | bright | dark`. With nothing stored, the preference is `default`. Map stored old values: `light` → `default`, `dark` → `dark`, `system` → `auto`. `data-theme` is always the resolved theme (`default`, `bright` or `dark`).
- **`index.html`:** mirror the same logic in the pre-paint script (there's a comment saying the two must stay in sync), and set `theme-color` per theme: `#F3F4F8`, `#FFFFFF`, `#0B0F14`.
- **`AccountControls.jsx`:** the Appearance select offers Default, Bright, Dark, and "Auto (match device)". Add a small swatch preview next to each option if it's cheap.
- **`ThemeToggle.jsx`:** the quick toggle on the sign-in page cycles Default → Bright → Dark.

### App shell (`App.jsx`)

- **Navigation:** replace the 15-item sidebar with a top bar like `mockups/default-flow.html`: the logo, then 5 tabs. Each group opens a small menu, and the tab is active when any of its routes is.

  | Tab | Routes |
  | --- | --- |
  | Overview | `/` |
  | Projects | Projects `/projects`, New project `/new`, Templates `/templates`, Assistant `/assistant` |
  | Pipelines | CI `/pipelines/ci` (+ `/runs/*`), CD `/pipelines/cd`, Security `/pipelines/security` (+ `/scans/*`) |
  | Delivery | Approvals `/approvals` (pending count badge, keep the 15 s poll), Metrics `/metrics`, Audit log `/audit`, Clusters `/clusters` (admin) |
  | Connectors | `/connectors` (admin) |

  Then the search box, then the "New project" button, then the account menu.
- **Search (Ctrl K / ⌘K):** opens a command palette that searches pages, projects (`/api/projects`) and recent CI runs. Navigation only, no actions in v1. Arrow keys move, Enter opens, Esc closes, focus returns to where it was.
- **Phones (≤ 760 px):** the tabs collapse into the existing menu button and sheet. Keep its focus trap, Esc handling and body scroll lock. The breadcrumb stays under the bar on detail pages.
- **Remaining content:** keep the "Live" indicator, `Stale` banners and the toast provider.

## Phase 2: Overview (home) and sign-in

### Overview (`pages/Dashboard.jsx`)

Rebuild it to match `mockups/default-flow.html`.

1. **Header:** the greeting (keep the current morning/afternoon/evening logic and the first name), then the subtitle "Your delivery pipeline over the last N days" and the "● Live" mark. On the right, a 7 / 14 / 30 day segmented control that drives `/api/metrics?days=N`. Store the choice in `localStorage` (`aio-overview-days`), wrapped in try/catch.
2. **Pipeline strip:** five stage cards joined by arrows, wrapping on phones.

   | Stage | Number | Sub-line |
   | --- | --- | --- |
   | Commits | Commit count | "from N GitHub repos" |
   | CI runs | `overall.ciRuns` | "X% passed · avg Y min" (`ciSuccessRate`, `ciDurationMinutesMean`) |
   | Dev | `overall.devDeploys` | health summary from project environments |
   | Staging | Staging deploys | health summary |
   | Prod | `overall.prodDeploys` | "N waiting for approval" (pending promotions) |

   Under each stage, a strip of one tick per day from `metrics.daily`: green passed, red failed, grey idle. Below the strip: lead time, time to restore, and the "DORA metrics" link.

   Two fields may not exist in the metrics API: the commit count, and deploys or health per environment for staging. Check `platform/backend/.../MetricsService` (or wherever `/api/metrics` is built). If either is missing, add it there in this PR, with a unit test. If that's not feasible, drop the Commits stage and show staging health only, not a count. Never show invented numbers.
3. **Projects:** one row card per project from `/api/projects`, as in the mockup.
   - Left: avatar initials, the name, and the last CI status in words.
   - Right: a dev → staging → prod track. Each stop shows a coloured dot and the env name, with the image tag below it, or "scaled down" / "not deployed" (keep `envTone` and `isStopped` from `ui.jsx`).
   - The whole row links to `/projects/:id` (no link for the `community` tier).
   - Show the first 6 rows, then "See all N".
4. **"Needs you"** (dark `--ink-panel` card, right column, hidden for the `community` tier):
   - Pending promotions (`/api/promotions?status=PENDING`), each linking to `/approvals`.
   - Projects whose `lastRun.status` is `FAILED`, linking to `/runs/:id`.
   - Environments where `envTone` is red, linking to the project.

   Each item has a title, a sub-line and an action button ("Review" / "Open"). With nothing waiting, show "Nothing needs you right now".
5. **Activity:** the latest 6 entries from `/api/audit`, using the existing `FEED` icon and tone mapping, with an "Audit log" link.
6. **States:** keep `usePoll` at 10 s, the skeletons while loading, `Stale` on errors, and the empty state ("Connect your first GitHub repository") when there are no projects.

Each theme restyles this same structure. Bright: section borders 2 px black, environment stops as colour blocks, KPI-style numbers 56 px. Dark: hairline borders, glowing status dots, mono numbers. Take the details from the other two mockups.

### Sign-in (`pages/Login.jsx`)

Keep the copy and the sign-in flow. Restyle the page with the theme tokens, and add a small static version of the pipeline strip as the hero graphic (no data, illustrative labels only). The theme toggle stays.

## Phase 3: Metrics

`pages/Metrics.jsx`:
- The four DORA key tiles, each with a sparkline from `daily`.
- The DORA band as a large labelled badge.
- The daily chart (reuse `BarChart`, restyled per theme).
- The by-project table with inline bars, plus sorting by any column.
- The period control matches the Overview's.

## Phase 4: project and pipeline flow

Pages: `NewProject.jsx`, `ProjectDetail.jsx`, `PipelinesCi.jsx`, `RunDetail.jsx`, `PipelinesCd.jsx`, `CdDetail.jsx`, `Approvals.jsx`.
- **New project:** a numbered stepper (Repository → Build → Environments → Review), with what each step decides spelled out and the defaults visible.
- **Project detail:** the dev → staging → prod track at the top, with Promote and Roll back on each stop. Tabs below.
- **CI run:** a horizontal task timeline (clone → lint → test → build → scan → bump) showing each task's status and duration. Clicking a task opens its log. On failure, the first error line goes at the top with "Open log". Keep SSE live updates.
- **Approvals:** cards showing what changes (old tag → new tag, commits, scan summary), with Approve and Reject.

## Phase 5: Connectors

`pages/Connectors.jsx`:
- The 56 connectors grouped by the 7 phases, each phase with a progress header ("4 of 6 enabled · switched on").
- Search and filter chips (kind, status).
- Cards showing health, version and the Enable / Test / Roll back actions.
- Each phase's switch-on control in its header.

## Phase 6: polish pass

Go over every page not covered above: Projects, Templates, Assistant, Security, ScanDetail, Clusters, Audit, and the 404 page.
- Consistent page headers.
- Empty states with a small illustration (inline SVG, using theme colours).
- Skeletons matched to each page's layout.
- Tables that stack on phones.
- One icon set (extend `components/Icon.jsx`).
- Badges from one `Badge` component per theme.

## Not in scope

- New backend features beyond the metrics fields named in phase 2.
- Changing the API, auth, or the access tiers.
- The public marketing sites.

## Starting a cloud session

In Claude Code on the web, open repository `satrishabh/aio-gitops`, branch `feat/ui-redesign`, and ask:

> Read `docs/ui/REDESIGN-BRIEF.md` and do phase 1 (then phase 2) on branch `feat/ui-redesign`. Open a PR per phase into `main` with screenshots in all three themes.
