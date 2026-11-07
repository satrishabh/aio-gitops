# Mobile app UI brief

Use this brief with another designer, developer, or coding agent. The supplied WhatsApp and Naukri screenshots are visual references for navigation, spacing, and hierarchy, not assets to copy.

## Copyable brief

Design the mobile experience of **[product name]** as a focused, touch-friendly app. Use the reference screenshots for a compact header, rounded search and action controls, a clear content area, and persistent bottom navigation. Preserve the product's branding, features, permissions, and desktop experience.

- **Screen structure:** compact header → scrollable page content → bottom navigation. Keep navigation out of the content's way. Avoid stacking a logo, full menu, breadcrumbs, tabs, and action buttons at the top.
- **Header:** show one short page title or a rounded search field when search is useful. Include only the necessary menu, profile, notification, or page action controls. Move secondary commands into an overflow menu. Never show decorative controls that do nothing.
- **Bottom navigation:** use three to five primary destinations with consistent icons and short, visible labels. Make the selected destination clear through shape and emphasis as well as color. Keep order and placement stable across screens. Secondary destinations can live in a menu or sheet.
- **Content:** favor readable rows and simple groups over a grid of small cards. A row can contain a leading avatar or status icon, a prominent title, a quieter description, and trailing time or status. Use subtle inset dividers. Show enough information to make the next action clear.
- **Visual style:** use a system font stack, regular and semibold weights, comfortable spacing, soft corners, and one product accent color. Use restrained shadows and avoid decorative gradients, glowing outlines, excessive badges, and oversized marketing headings inside working screens.
- **Appearance:** provide complete light and dark palettes using semantic color tokens. In dark mode, use near-black backgrounds, slightly lighter raised surfaces, softened white text, and readable secondary text. Light mode uses neutral surfaces and dark text. Keep status colors consistent and readable in both.
- **Transparency:** use subtle translucency and blur only on floating controls or navigation. Keep content legible over scrolling backgrounds and provide an opaque fallback when reduced transparency is requested.
- **Theme control:** keep a small, slightly translucent button on the right edge. Reveal Auto, Light, and Dark choices on desktop hover or activation; support tap and keyboard access. Dismiss with Escape, an outside tap, or pointer exit when focus does not remain inside. Auto follows the device appearance; preserve explicit choices across reloads.
- **Touch and accessibility:** provide at least 44 × 44 CSS-pixel touch targets, visible keyboard focus, screen-reader labels, sufficient contrast, and reduced-motion support. Do not rely on hover or color alone.
- **Responsive behavior:** support 320–430 px mobile widths without page overflow, clipped actions, or hidden functions. Respect top and bottom safe areas. Reserve space below scrolling content for the bottom navigation so the final row and primary actions remain reachable.
- **Validation:** test light and dark, long names, empty/loading/error states, increased text size, touch, keyboard access, and navigation state. Deliver screenshots and state which interactions were actually tested.

## AIO adaptation

For AIO, the screen's job is to help people find projects and understand delivery status. A suitable primary navigation is **Overview · Projects · Pipelines · More**; community users see only destinations allowed for their tier. More can expose metrics, approvals, clusters, templates, audit history, and account controls according to existing permissions. Do not remove features just to fit the navigation.

Project rows should foreground the project name, latest build status, environment health, and last update. Reserve the accent for primary actions and the active navigation state; delivery statuses retain their own semantic colors.

This bottom-navigation direction describes a subsequent design iteration. Release `0.12.3` uses a compact header and a collapsible mobile side drawer, rather than a bottom bar.
