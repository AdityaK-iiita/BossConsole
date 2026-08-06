/**
 * Page chrome shared by every view.
 *
 * The stylesheet is inline and nonce-stamped rather than a separate file: the
 * CSP is `default-src 'none'` with no `style-src 'self'`, and an edge function
 * has no static asset route to serve one from anyway.
 *
 * Palette matches the sibling passkey pages (#1a1a1a / #2B2B2B / #F2F2F2 /
 * #4D4D4D) with the BOSS amber #F2A93B as the accent.
 */

import { esc } from "../utils/html.ts"

const STYLES = `
  * { margin: 0; padding: 0; box-sizing: border-box; }
  body {
    font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif;
    background-color: #1a1a1a; color: #F2F2F2; min-height: 100vh;
    line-height: 1.5; padding: 24px;
  }
  .wrap { width: 100%; max-width: 940px; margin: 0 auto; }
  header.page { display: flex; align-items: baseline; gap: 12px; flex-wrap: wrap; margin-bottom: 4px; }
  header.page h1 { font-size: 26px; font-weight: 600; letter-spacing: -0.4px; }
  .slug { color: #9A9A9A; font-size: 14px; font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }
  .sub { color: #9A9A9A; font-size: 14px; margin-bottom: 24px; }
  .tabs { display: flex; gap: 6px; margin-bottom: 20px; flex-wrap: wrap; }
  .tabs a {
    color: #C9C9C9; text-decoration: none; font-size: 14px; padding: 7px 14px;
    border: 1px solid #4D4D4D; border-radius: 6px;
  }
  .tabs a.active { color: #1a1a1a; background-color: #F2A93B; border-color: #F2A93B; font-weight: 600; }
  section.card {
    background-color: #2B2B2B; border: 1px solid #4D4D4D; border-radius: 8px;
    padding: 20px; margin-bottom: 16px;
  }
  section.card > h2 { font-size: 16px; font-weight: 600; margin-bottom: 4px; }
  section.card > p.hint { color: #9A9A9A; font-size: 13px; margin-bottom: 16px; }
  table { width: 100%; border-collapse: collapse; font-size: 14px; }
  th {
    text-align: left; color: #9A9A9A; font-weight: 500; font-size: 12px;
    text-transform: uppercase; letter-spacing: 0.5px;
    padding: 6px 10px 6px 0; border-bottom: 1px solid #4D4D4D;
  }
  td { padding: 10px 10px 10px 0; border-bottom: 1px solid #3A3A3A; vertical-align: middle; }
  tr:last-child td { border-bottom: none; }
  .pill {
    display: inline-block; font-size: 11px; padding: 2px 8px; border-radius: 10px;
    border: 1px solid #4D4D4D; color: #C9C9C9; margin-right: 4px;
  }
  .pill.admin { border-color: #F2A93B; color: #F2A93B; }
  .pill.ok { border-color: #4C9A5E; color: #7BC98D; }
  .pill.warn { border-color: #A8762B; color: #E0A44E; }
  .mono { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 12px; }
  label { display: block; font-size: 13px; color: #C9C9C9; margin-bottom: 5px; }
  input[type=text], input[type=number], select, textarea {
    width: 100%; background-color: #1F1F1F; color: #F2F2F2;
    border: 1px solid #4D4D4D; border-radius: 6px; padding: 8px 10px;
    font-size: 14px; font-family: inherit;
  }
  input:focus, select:focus, textarea:focus { outline: 2px solid #F2A93B; outline-offset: -1px; }
  .row { display: flex; gap: 12px; flex-wrap: wrap; margin-bottom: 14px; }
  .row > div { flex: 1 1 200px; }
  .checkline { display: flex; align-items: center; gap: 8px; margin-bottom: 14px; }
  .checkline label { margin: 0; }
  button {
    background-color: #F2A93B; color: #1a1a1a; border: none; border-radius: 6px;
    padding: 8px 16px; font-size: 14px; font-weight: 600; cursor: pointer; font-family: inherit;
  }
  button.secondary { background-color: transparent; color: #C9C9C9; border: 1px solid #4D4D4D; font-weight: 500; }
  button.danger { background-color: transparent; color: #E0796B; border: 1px solid #7A3B33; font-weight: 500; }
  button:hover { opacity: 0.9; }
  form.inline { display: inline; }
  .banner { border-radius: 6px; padding: 12px 14px; margin-bottom: 16px; font-size: 14px; }
  .banner.error { background-color: #3A2320; border: 1px solid #7A3B33; color: #F0B4AA; }
  .banner.ok { background-color: #1F2E23; border: 1px solid #3E6B4A; color: #A6D9B4; }
  .empty { color: #9A9A9A; font-size: 14px; padding: 8px 0; }
  footer.page { color: #6E6E6E; font-size: 12px; margin-top: 28px; text-align: center; }
  .stat { display: inline-block; margin-right: 22px; }
  .stat b { display: block; font-size: 20px; font-weight: 600; }
  .stat span { color: #9A9A9A; font-size: 12px; }

  /* Utility classes, because every inline style= attribute is DROPPED by our own
     CSP: style-src is nonce-only, CSP3 falls style-src-attr back to it, and a
     nonce cannot apply to an attribute. Inline styles rendered nothing and logged
     a violation on every page. */
  .spaced { margin-top: 16px; }
  .tight { margin-top: 12px; }
  .highlight { border-color: #F2A93B; }
  .linkish { color: #F2A93B; }
  @media (max-width: 620px) {
    body { padding: 14px; }
    th:nth-child(n+4), td:nth-child(n+4) { display: none; }
  }
`

export type BannerKind = "error" | "ok"

export interface LayoutOptions {
  title: string
  nonce: string
  body: string
  /** Rendered above the body when present. */
  banner?: { kind: BannerKind; message: string } | null
}

/**
 * Wrap page content in the document shell.
 *
 * `body` is expected to be already-escaped markup built by the caller. The
 * banner message is escaped HERE, because it is the one string that routinely
 * originates from an RPC error and would otherwise be interpolated raw.
 */
export function layout({ title, nonce, body, banner }: LayoutOptions): string {
  const bannerHtml = banner
    ? `<div class="banner ${banner.kind === "ok" ? "ok" : "error"}">${esc(banner.message)}</div>`
    : ""

  return `<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<meta name="referrer" content="no-referrer">
<title>${esc(title)}</title>
<style nonce="${esc(nonce)}">${STYLES}</style>
</head>
<body>
<div class="wrap">
${bannerHtml}
${body}
<footer class="page">BOSS Organisation</footer>
</div>
</body>
</html>`
}

/**
 * The tab strip.
 *
 * `current` selects the active tab. The admin tab is only emitted when the
 * caller says the viewer is an admin, and that flag always comes from a live
 * probe -- hiding it is cosmetic, the route re-checks regardless.
 */
export function tabs(
  basePath: string,
  slug: string,
  current: "overview" | "admin",
  isAdmin: boolean,
): string {
  const overviewHref = `${basePath}/o/${encodeURIComponent(slug)}`
  const items = [
    `<a href="${esc(overviewHref)}"${current === "overview" ? ' class="active"' : ""}>Overview</a>`,
  ]
  if (isAdmin) {
    items.push(
      `<a href="${esc(overviewHref)}/admin"${current === "admin" ? ' class="active"' : ""}>Configuration</a>`,
    )
  }
  return `<nav class="tabs">${items.join("")}</nav>`
}

/** A hidden CSRF field. Every mutating form must include one. */
export function csrfField(fieldName: string, token: string): string {
  return `<input type="hidden" name="${esc(fieldName)}" value="${esc(token)}">`
}
