/**
 * Output-escaping primitives.
 *
 * This module holds ONLY pure functions, with no imports, so the test suite can
 * exercise each escaping context in isolation. Every value that reaches a page
 * is organisation-controlled -- names, descriptions, member emails, role names,
 * domains, invite labels -- and an org admin is not a trusted author.
 */

/**
 * HTML text and attribute escaping. Applied to EVERY interpolation, with no
 * exceptions for "this one is a uuid" -- the exceptions are what rot.
 *
 * Both quote styles are escaped so the same function is safe in text nodes and
 * in single- or double-quoted attribute values.
 */
export function esc(value: unknown): string {
  return String(value ?? "")
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#39;")
}

/**
 * JSON safe to embed inside a `<script>` block.
 *
 * Two hazards, both of which JSON.stringify leaves open:
 *
 *   - `</script>` inside a string ends the block early, whatever the JS grammar
 *     thinks. Escaping `<` covers it.
 *   - U+2028 and U+2029 are legal in JSON but are raw line terminators in a JS
 *     string literal, so they produce a syntax error or, worse, split a
 *     statement. `redirect/app.ts::jsStringLiteral` documents skipping these
 *     because its inputs are already percent-encoded; ours are not.
 */
export function jsonForScript(value: unknown): string {
  // The patterns are written as \u escapes, not literal characters: U+2028 and
  // U+2029 are ECMAScript LineTerminators, and a LineTerminator inside a regex
  // literal is a syntax error, so a literal one here would not even parse.
  return JSON.stringify(value ?? null)
    .replace(/</g, "\\u003c")
    .replace(/\u2028/g, "\\u2028")
    .replace(/\u2029/g, "\\u2029")
}

/**
 * A URL safe to place in href/action, or "#" if it is not.
 *
 * Allows only same-origin absolute paths and the app's own deep-link scheme.
 * Everything else -- `javascript:`, `data:`, protocol-relative `//evil.com`, a
 * bare `http://` to another host -- collapses to "#". An org admin controls
 * strings that end up near links, and "we only build these ourselves" is a
 * property that survives exactly one refactor.
 */
export function attrUrl(url: string, allowedSchemes: readonly string[] = []): string {
  const trimmed = String(url ?? "").trim()
  if (trimmed.length === 0) return "#"

  // Protocol-relative: the browser reads //host/path as another origin. Backslash counts -
  // browsers normalise a leading /\ (and \\) to // for special schemes, so `/\evil.com` is
  // cross-origin while still passing a naive startsWith("/") test.
  if (/^[/\\]{2}/.test(trimmed)) return "#"

  // Same-origin absolute path -- the normal case for every link we emit.
  if (trimmed.startsWith("/")) return esc(trimmed)

  const scheme = /^([a-zA-Z][a-zA-Z0-9+.-]*):/.exec(trimmed)?.[1]?.toLowerCase()
  if (!scheme) return "#" // relative path: refuse rather than guess a base
  if (allowedSchemes.map((s) => s.toLowerCase()).includes(scheme)) return esc(trimmed)
  return "#"
}

/** A fresh CSP nonce. 128 bits, base64url, one per response. */
export function cspNonce(): string {
  const bytes = new Uint8Array(16)
  crypto.getRandomValues(bytes)
  return btoa(String.fromCharCode(...bytes))
    .replace(/\+/g, "-")
    .replace(/\//g, "_")
    .replace(/=+$/, "")
}
