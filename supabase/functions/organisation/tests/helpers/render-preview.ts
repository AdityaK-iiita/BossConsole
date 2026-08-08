/**
 * Render every page, in every state that changes its markup, so a human (or a
 * screenshot) can look at them.
 *
 * Not a test - a development harness. The views are pure functions of their
 * data, so they can be rendered without a database, a session or a running
 * function, which is the only reason a visual check is cheap enough to do on
 * every styling change.
 *
 * The stylesheet is the thing this exists for. `deno check` and the unit tests
 * both pass on CSS that renders as an unreadable mess: neither one can see that
 * a colour fails contrast, that a table overflows its card, or that a heading
 * disappeared into its own eyebrow treatment. Those only show up on a screen.
 *
 *   deno run --allow-write --allow-read tests/helpers/render-preview.ts <outDir>
 */

import { adminPage, inviteOnlyPage } from "../../views/admin.ts"
import { orgPage } from "../../views/org.ts"
import { invalidInvitePage, joinPage } from "../../views/join.ts"
import { errorPage, NOT_AVAILABLE_MESSAGE } from "../../views/error.ts"
import type { OrgDetail, OrgDomain, OrgInvite, OrgMember, OrgRole } from "../../services/org.ts"

const NONCE = "previewnonce"
/** A plausible one-time invite URL. Only its shape matters to the rendering. */
const INVITE =
  "https://api.risaboss.com/functions/v1/organisation/join/hK3nQ8mZ0pW7xR4tL9vB2cN6dF1sA5gJ"
const BASE = "/functions/v1/organisation"

const org: OrgDetail = {
  id: "11111111-1111-1111-1111-111111111111",
  slug: "risa_labs",
  name: "Risa Labs",
  description: "The people building BOSS. Ask an administrator if you need access to something.",
  visibility: "public",
  join_policy: "request_to_join",
  is_system: false,
  owner_email: "shivang@risalabs.ai",
  member_count: 89,
  pending_count: 2,
  primary_domain: "risalabs.ai",
  website: "https://risalabs.ai",
  is_owner: true,
  is_admin: true,
  can_publish: true,
  publish_policy: "admins",
}

const members: OrgMember[] = [
  {
    user_id: "u1",
    email: "shivang@risalabs.ai",
    status: "active",
    joined_at: "2026-01-14T10:00:00Z",
    requested_at: null,
    request_message: null,
    join_source: "seed",
    is_owner: true,
    is_admin: true,
    roles: ["risa_labs_admin"],
  },
  {
    user_id: "u2",
    email: "aamer@risalabs.ai",
    status: "active",
    joined_at: "2026-03-02T10:00:00Z",
    requested_at: null,
    request_message: null,
    join_source: "domain",
    is_owner: false,
    is_admin: true,
    roles: ["risa_labs_admin", "risa_labs_user"],
  },
  {
    user_id: "u3",
    email: "someone-with-a-rather-long-address@risalabs.ai",
    status: "active",
    joined_at: "2026-06-21T10:00:00Z",
    requested_at: null,
    request_message: null,
    join_source: "invite",
    is_owner: false,
    is_admin: false,
    roles: ["risa_labs_user"],
  },
  {
    user_id: "u4",
    email: "newcomer@example.com",
    status: "pending",
    joined_at: null,
    requested_at: "2026-08-05T10:00:00Z",
    request_message: "Joining the platform team next week.",
    join_source: "request",
    is_owner: false,
    is_admin: false,
    roles: [],
  },
]

const roles: OrgRole[] = [
  {
    role_id: "r1",
    role_name: "risa_labs_admin",
    description: "Full control of the organisation.",
    kind: "admin",
    member_count: 2,
    permissions: ["organisation.read", "organisation.admin", "plugins.create", "plugins.publish"],
  },
  {
    role_id: "r2",
    role_name: "risa_labs_user",
    description: null,
    kind: "user",
    member_count: 87,
    permissions: ["organisation.read"],
  },
]

const domains: OrgDomain[] = [
  {
    domain_id: "d1",
    domain: "risalabs.ai",
    is_primary: true,
    verified: true,
    verified_at: "2026-02-01T10:00:00Z",
    dns_record_type: "TXT",
    dns_record_name: "_boss-verify.risalabs.ai",
    dns_record_value: "boss-verify=6f2a9c31d7b84e05",
  },
  {
    domain_id: "d2",
    domain: "risaboss.com",
    is_primary: false,
    verified: false,
    verified_at: null,
    dns_record_type: "TXT",
    dns_record_name: "_boss-verify.risaboss.com",
    dns_record_value: "boss-verify=1c7e40ab99f3ad2",
  },
]

const invites: OrgInvite[] = [
  {
    invite_id: "i1",
    token_prefix: "hK3nQ8",
    label: "Platform team",
    role_id: "r2",
    role_name: "risa_labs_user",
    max_uses: 25,
    uses: 4,
    expires_at: "2026-09-01T10:00:00Z",
    revoked_at: null,
    created_by_email: "shivang@risalabs.ai",
    is_live: true,
  },
  {
    invite_id: "i2",
    token_prefix: "zP0mR2",
    label: null,
    role_id: null,
    role_name: null,
    max_uses: null,
    uses: 12,
    expires_at: "2026-07-01T10:00:00Z",
    revoked_at: "2026-06-20T10:00:00Z",
    created_by_email: "aamer@risalabs.ai",
    is_live: false,
  },
]

const pages: Record<string, string> = {
  "overview.html": orgPage({ nonce: NONCE, basePath: BASE, org, members, roles }),
  "admin.html": adminPage({
    nonce: NONCE,
    basePath: BASE,
    csrf: "preview-csrf",
    org,
    members,
    roles,
    domains,
    invites,
    banner: { kind: "ok", message: "Settings saved." },
  }),
  "admin-error.html": adminPage({
    nonce: NONCE,
    basePath: BASE,
    csrf: "preview-csrf",
    org,
    members,
    roles,
    domains,
    invites,
    banner: { kind: "error", message: "That value was not accepted. Check the field and try again." },
  }),
  // The one-time invite card. It carries `.highlight`, which had never rendered
  // (a bare .highlight loses to section.card on specificity) and which no preview
  // covered - so the visual check could not have caught it. That is the gap this
  // page and the two below close.
  "admin-new-invite.html": adminPage({
    nonce: NONCE,
    basePath: BASE,
    csrf: "preview-csrf",
    org,
    members,
    roles,
    domains,
    invites,
    newInviteUrl: INVITE,
  }),
  "invite-only.html": inviteOnlyPage(NONCE, BASE, "risa_labs", INVITE),
  "join.html": joinPage({
    nonce: NONCE,
    orgName: "Risa Labs",
    orgSlug: "risa_labs",
    description: "The people building BOSS.",
    deepLink: "boss://organisation/join?token=preview",
  }),
  "invite-invalid.html": invalidInvitePage(NONCE),
  "error.html": errorPage({
    nonce: NONCE,
    title: "Not available - BOSS",
    heading: "Not available",
    message: NOT_AVAILABLE_MESSAGE,
  }),
  "error-with-action.html": errorPage({
    nonce: NONCE,
    title: "Not available - BOSS",
    heading: "Not available",
    message: NOT_AVAILABLE_MESSAGE,
    action: { href: `${BASE}/o/risa_labs`, label: "Back to the organisation" },
  }),
}

const outDir = Deno.args[0] ?? "./preview"
await Deno.mkdir(outDir, { recursive: true })
for (const [name, html] of Object.entries(pages)) {
  await Deno.writeTextFile(`${outDir}/${name}`, html)
  console.log(`${outDir}/${name}`)
}
