/**
 * Output escaping. Each context gets its own assertions, because "we escape
 * things" is not a property - escaping for the wrong context is the bug.
 */

import { assert, assertEquals } from "@std/assert"
import { attrUrl, cspNonce, esc, jsonForScript } from "../utils/html.ts"

Deno.test("esc neutralises every HTML metacharacter", () => {
  assertEquals(esc(`<script>`), "&lt;script&gt;")
  assertEquals(esc(`&`), "&amp;")
  assertEquals(esc(`"`), "&quot;")
  assertEquals(esc(`'`), "&#39;")
  assertEquals(esc(`<img src=x onerror="alert(1)">`), "&lt;img src=x onerror=&quot;alert(1)&quot;&gt;")
})

Deno.test("esc escapes & first, so entities are not double-decoded", () => {
  // If & were escaped last, "&lt;" would come out as "&lt;" and render as "<".
  assertEquals(esc("&lt;script&gt;"), "&amp;lt;script&amp;gt;")
})

Deno.test("esc renders null and undefined as empty, not as the word", () => {
  assertEquals(esc(null), "")
  assertEquals(esc(undefined), "")
  assertEquals(esc(0), "0")
  assertEquals(esc(false), "false")
})

Deno.test("jsonForScript cannot break out of a script block", () => {
  const out = jsonForScript({ name: "</script><script>alert(1)</script>" })
  // Escaping `<` alone is what matters: the HTML parser cannot see a tag,
  // opening or closing, without it. `>` is left as-is deliberately.
  assertEquals(out.includes("</script>"), false)
  assertEquals(out.includes("<"), false)
  assert(out.includes("\\u003c/script>"))
})

Deno.test("jsonForScript escapes the U+2028 and U+2029 line separators", () => {
  // Legal in JSON, but raw line terminators inside a JS string literal. This is
  // the case redirect/app.ts documents skipping because its inputs are
  // pre-encoded; ours are not.
  const out = jsonForScript({ name: `a\u2028b\u2029c` })
  assertEquals(out.includes("\u2028"), false)
  assertEquals(out.includes("\u2029"), false)
  assert(out.includes("\\u2028"))
  assert(out.includes("\\u2029"))
})

Deno.test("jsonForScript output is still valid JSON", () => {
  const value = { name: "</script>", sep: "a\u2028b" }
  const parsed = JSON.parse(jsonForScript(value))
  assertEquals(parsed.name, "</script>")
  assertEquals(parsed.sep, "a\u2028b")
})

Deno.test("attrUrl allows same-origin absolute paths", () => {
  assertEquals(attrUrl("/functions/v1/organisation/o/acme"), "/functions/v1/organisation/o/acme")
})

Deno.test("attrUrl refuses script-bearing and cross-origin schemes", () => {
  for (
    const bad of [
      "javascript:alert(1)",
      "JavaScript:alert(1)",
      " javascript:alert(1)",
      "data:text/html,<script>alert(1)</script>",
      "vbscript:msgbox",
      "//evil.example.com/path",
      "/\\evil.example.com/path",
      "\\\\evil.example.com/path",
      "\\/evil.example.com/path",
      "https://evil.example.com",
      "http://evil.example.com",
      "relative/path",
      "",
    ]
  ) {
    assertEquals(attrUrl(bad), "#", `should refuse: ${bad}`)
  }
})

Deno.test("attrUrl allows a deep-link scheme only when it is opted in", () => {
  assertEquals(attrUrl("boss://organisation/join?token=x"), "#")
  assertEquals(
    attrUrl("boss://organisation/join?token=x", ["boss"]),
    "boss://organisation/join?token=x",
  )
  // Opting in to `boss` does not open the door to anything else.
  assertEquals(attrUrl("javascript:alert(1)", ["boss"]), "#")
})

Deno.test("attrUrl escapes what it lets through", () => {
  assertEquals(attrUrl(`/o/acme"><script>`), "/o/acme&quot;&gt;&lt;script&gt;")
})

Deno.test("cspNonce is unique and base64url", () => {
  const seen = new Set<string>()
  for (let i = 0; i < 200; i++) {
    const nonce = cspNonce()
    assert(/^[A-Za-z0-9_-]+$/.test(nonce), `not base64url: ${nonce}`)
    seen.add(nonce)
  }
  assertEquals(seen.size, 200)
})
