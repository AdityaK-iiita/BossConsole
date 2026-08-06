/**
 * GET /o/:slug -- the member overview, and the handoff exchange that gets you
 * there.
 *
 * THE `?t=` FLOW, in order, because the order is the security property:
 *
 *   1. rate limit the exchange (a brake on token guessing)
 *   2. consume the token, service-role, single use
 *   3. cross-check the token's org against the slug in the path
 *   4. Set-Cookie
 *   5. 302 to the same URL WITHOUT `t`
 *
 * Step 5 is a 302 and not a 303 on purpose. Both would work for a GET, but 302
 * is what a browser treats as "the resource is over here for now" and, more to
 * the point, it replaces the current navigation so the `?t=` URL leaves both the
 * address bar and the history entry. The token must not survive a back button.
 */

import { OpenAPIHono } from "@hono/zod-openapi"
import { exchangeHandoffToken, urlWithoutToken } from "../services/handoff.ts"
import { isOrgMember } from "../services/authority.ts"
import { loadOrgPageData } from "../services/org.ts"
import { htmlResponse, redirectResponse } from "../utils/responses.ts"
import { mintSession, newCsrfToken, sessionCookieHeader } from "../utils/session.ts"
import { isValidSlug, readRequestFacts } from "../utils/request.ts"
import { clientKey, rateLimit } from "../utils/rate-limit.ts"
import { errorPage, NOT_AVAILABLE_MESSAGE, SESSION_EXPIRED_MESSAGE } from "../views/error.ts"
import { orgPage } from "../views/org.ts"

/** 20 exchanges per minute per client. Generous for a human, useless for a loop. */
const HANDOFF_LIMIT = 20
const HANDOFF_WINDOW_SECONDS = 60

export const orgPageRoutes = new OpenAPIHono()

orgPageRoutes.get("/o/:slug", async (ctx) => {
  const slug = ctx.req.param("slug") ?? ""
  const facts = await readRequestFacts(ctx)

  if (!isValidSlug(slug)) {
    return notAvailable()
  }

  const token = new URL(ctx.req.url).searchParams.get("t")

  if (token) {
    // A state-changing GET, and the one request that does not follow the
    // session -> CSRF -> probe order the rest of this function keeps to. Consuming a
    // token and issuing Set-Cookie means any page could navigate a victim to
    // /o/<slug>?t=<attacker token> and replace their org session with the attacker's
    // identity - login CSRF. The attacker gains nothing, but the victim can be induced
    // to type into a settings form belonging to somebody else's organisation.
    //
    // Sec-Fetch-Site is not settable by page script. The desktop app opens this as a
    // top-level navigation from a boss:// link, which yields `none`, so refusing only
    // `cross-site` costs the real flow nothing.
    if (ctx.req.header("sec-fetch-site") === "cross-site") {
      return htmlResponse(
        (nonce) =>
          errorPage({
            nonce,
            title: "Session unavailable - BOSS",
            heading: "Open this from BOSS",
            message: SESSION_EXPIRED_MESSAGE,
          }),
        { status: 400 },
      )
    }

    const limit = rateLimit(
      `handoff:${clientKey(ctx.req.raw.headers)}`,
      HANDOFF_LIMIT,
      HANDOFF_WINDOW_SECONDS,
    )
    if (!limit.allowed) {
      return htmlResponse(
        (nonce) =>
          errorPage({
            nonce,
            title: "Too many attempts - BOSS",
            heading: "Too many attempts",
            message: "Please wait a moment and open the page again from BOSS.",
          }),
        { status: 429, headers: { "Retry-After": String(limit.retryAfterSeconds) } },
      )
    }

    const exchange = await exchangeHandoffToken(token, slug)
    if (!exchange.ok) {
      // Both failure reasons render the same page. A distinguishable
      // slug_mismatch would confirm that a guessed token is real and tell the
      // holder which org it belongs to.
      return htmlResponse(
        (nonce) =>
          errorPage({
            nonce,
            title: "Session unavailable - BOSS",
            heading: "This link has expired",
            message: SESSION_EXPIRED_MESSAGE,
          }),
        { status: 400 },
      )
    }

    const value = await mintSession({
      sub: exchange.identity.userId,
      org: exchange.identity.orgId,
      slug: exchange.identity.orgSlug,
      csrf: newCsrfToken(),
      pur: exchange.identity.purpose,
    })

    // The redirect target is rebuilt from the configured base path, so the
    // token cannot survive in the Location by way of an echoed request URL.
    const location = urlWithoutToken(
      facts.basePath,
      `/o/${encodeURIComponent(slug)}`,
      new URL(ctx.req.url).searchParams,
    )

    return redirectResponse(location, {
      status: 302,
      headers: { "Set-Cookie": sessionCookieHeader(value, facts.secure, facts.basePath) },
    })
  }

  const session = facts.session
  if (!session) {
    return htmlResponse(
      (nonce) =>
        errorPage({
          nonce,
          title: "Session expired - BOSS",
          heading: "Session expired",
          message: SESSION_EXPIRED_MESSAGE,
        }),
      { status: 401 },
    )
  }

  // The cookie names its org. A session for one org must not render another's
  // page even if the viewer is a member of both -- the links, the forms and the
  // CSRF nonce all belong to the org in the cookie.
  if (session.slug !== slug) {
    return notAvailable()
  }

  // Live probe. The cookie is authentication; this is authorization, and it is
  // re-asked on every request so a removed member loses access immediately.
  if (!await isOrgMember(session.sub, session.org)) {
    return notAvailable()
  }

  const data = await loadOrgPageData(session.sub, session.org)
  if (!data.ok) {
    return notAvailable()
  }

  return htmlResponse((nonce) =>
    orgPage({
      nonce,
      basePath: facts.basePath,
      org: data.org,
      members: data.members,
      roles: data.roles,
    })
  )
})

function notAvailable(): Response {
  return htmlResponse(
    (nonce) =>
      errorPage({
        nonce,
        title: "Not available - BOSS",
        heading: "Not available",
        message: NOT_AVAILABLE_MESSAGE,
      }),
    { status: 404 },
  )
}
