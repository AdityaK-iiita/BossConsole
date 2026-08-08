/**
 * The admin configuration page.
 *
 * Every form here is a POST with a CSRF field, and every action re-checks
 * admin-ness server-side. Rendering a control is never authorization: this view
 * hides what an admin cannot use, and routes/admin-actions.ts refuses it
 * regardless of what was rendered.
 */

import { esc, scrollable } from "../utils/html.ts"
import { csrfField, layout, tabs } from "./layout.ts"
import { formatDate } from "./org.ts"
import { CSRF_FIELD } from "../utils/csrf.ts"
import type { OrgDetail, OrgDomain, OrgInvite, OrgMember, OrgRole } from "../services/org.ts"

export interface AdminPageOptions {
  nonce: string
  basePath: string
  csrf: string
  org: OrgDetail
  members: OrgMember[]
  roles: OrgRole[]
  domains: OrgDomain[]
  invites: OrgInvite[]
  banner?: { kind: "ok" | "error"; message: string } | null
  /**
   * A just-created invite URL, shown once.
   *
   * Only the token's hash is stored, so this is the single moment the link
   * exists in readable form. It is why the create-invite action renders the
   * page directly instead of answering 303 like every other action.
   */
  newInviteUrl?: string | null
}

export function adminPage(options: AdminPageOptions): string {
  const { nonce, basePath, csrf, org, members, roles, domains, invites, banner } = options
  const action = `${basePath}/o/${encodeURIComponent(org.slug)}/admin`
  const pending = members.filter((m) => m.status === "pending")
  const active = members.filter((m) => m.status === "active")

  return layout({
    title: `${org.name} configuration - BOSS`,
    nonce,
    banner: banner ?? null,
    body: `
<header class="page">
  <h1>${esc(org.name)}</h1>
  <span class="slug">@${esc(org.slug)}</span>
</header>
<p class="sub">Organisation configuration. Only administrators can see this page.</p>
${tabs(basePath, org.slug, "admin", true)}

${options.newInviteUrl ? newInviteCard(options.newInviteUrl) : ""}
${settingsCard(action, csrf, org, roles)}
${pending.length > 0 ? pendingCard(action, csrf, pending) : ""}
${membersCard(action, csrf, org, active, roles)}
${rolesCard(action, csrf, org, roles)}
${invitesCard(action, csrf, invites, roles)}
${domainsCard(action, csrf, domains)}`,
  })
}

/**
 * The one-time display of a new invite link.
 *
 * Rendered in a readonly input rather than as an <a>: the value is a live
 * bearer credential, and a link invites a middle-click that would open it in
 * this browser.
 *
 * No `onfocus="this.select()"`, and no copy button. The CSP is
 * `script-src 'nonce-...'`, which blocks inline event handlers outright, so
 * either would be dead markup that looks like it works.
 */
function newInviteCard(url: string): string {
  return `
<section class="card highlight">
  <h2>Invite link created</h2>
  <p class="hint">This is the only time the link is shown. Only its hash is stored, so it cannot be recovered later.</p>
  <input type="text" class="mono" readonly value="${esc(url)}" aria-label="Invite link">
</section>`
}

function settingsCard(action: string, csrf: string, org: OrgDetail, roles: OrgRole[]): string {
  const publishPolicy = org.publish_policy ?? "owner_only"
  const roleOptions = roles
    .map((role) =>
      `<option value="${esc(role.role_id)}"${
        org.publish_role_id === role.role_id ? " selected" : ""
      }>${esc(role.role_name)}</option>`
    )
    .join("")

  return `
<section class="card">
  <h2>Settings</h2>
  <p class="hint">The slug @${
    esc(org.slug)
  } is permanent. Role names derive from it, so changing it would orphan them.</p>
  <form method="post" action="${esc(action)}/settings">
    ${csrfField(CSRF_FIELD, csrf)}
    <div class="row">
      <div>
        <label for="name">Name</label>
        <input type="text" id="name" name="name" maxlength="120" value="${esc(org.name)}" required>
      </div>
      <div>
        <label for="visibility">Visibility</label>
        <select id="visibility" name="visibility">
          ${selectOption("private", "Private (hidden from search)", org.visibility)}
          ${selectOption("public", "Public (appears in discovery)", org.visibility)}
        </select>
      </div>
    </div>
    <div class="row">
      <div>
        <label for="website">Website</label>
        <input type="url" id="website" name="website" maxlength="500"
               placeholder="https://acme.com" value="${esc(org.website ?? "")}">
      </div>
    </div>
    <div class="row">
      <div>
        <label for="description">Description</label>
        <textarea id="description" name="description" rows="2" maxlength="500">${
    esc(org.description ?? "")
  }</textarea>
      </div>
    </div>
    <div class="row">
      <div>
        <label for="join_policy">Who can join</label>
        <select id="join_policy" name="join_policy">
          ${selectOption("invite_only", "Invite only", org.join_policy)}
          ${selectOption("request_to_join", "Anyone may request", org.join_policy)}
          ${selectOption("open", "Anyone may join directly", org.join_policy)}
        </select>
      </div>
      <div>
        <label for="publish_policy">Who can publish plugins</label>
        <select id="publish_policy" name="publish_policy">
          ${selectOption("owner_only", "Owner only", publishPolicy)}
          ${selectOption("admins", "Administrators", publishPolicy)}
          ${selectOption("members", "Any member", publishPolicy)}
        </select>
      </div>
      <div>
        <label for="publish_role_id">Or a specific role</label>
        <select id="publish_role_id" name="publish_role_id">
          <option value="">Use the policy above</option>
          ${roleOptions}
        </select>
      </div>
    </div>
    <div class="checkline">
      <input type="checkbox" id="auto_assign" name="auto_assign_member_role" value="1"${
    org.auto_assign_member_role ? " checked" : ""
  }>
      <label for="auto_assign">Give new members the ${
    esc(org.slug)
  }_user role automatically</label>
    </div>
    <button type="submit">Save settings</button>
  </form>
</section>`
}

function pendingCard(action: string, csrf: string, pending: OrgMember[]): string {
  const rows = pending.map((member) => `
    <tr>
      <td>${esc(member.email ?? "unknown")}</td>
      <td>${esc(member.request_message ?? "")}</td>
      <td>${esc(formatDate(member.requested_at))}</td>
      <td>
        <form class="inline" method="post" action="${esc(action)}/members/approve">
          ${csrfField(CSRF_FIELD, csrf)}
          <input type="hidden" name="user_id" value="${esc(member.user_id)}">
          <button type="submit">Approve</button>
        </form>
        <form class="inline" method="post" action="${esc(action)}/members/reject">
          ${csrfField(CSRF_FIELD, csrf)}
          <input type="hidden" name="user_id" value="${esc(member.user_id)}">
          <button type="submit" class="danger">Reject</button>
        </form>
      </td>
    </tr>`).join("")

  return `
<section class="card">
  <h2>Join requests (${pending.length})</h2>
  <p class="hint">People who asked to join and are waiting on a decision.</p>
  ${scrollable("Join requests", `
  <table>
    <thead><tr><th>Email</th><th>Message</th><th>Requested</th><th>Decision</th></tr></thead>
    <tbody>${rows}</tbody>
  </table>
  `, false)}
</section>`
}

function membersCard(
  action: string,
  csrf: string,
  org: OrgDetail,
  members: OrgMember[],
  roles: OrgRole[],
): string {
  if (members.length === 0) return ""

  const roleOptions = roles
    .map((role) => `<option value="${esc(role.role_id)}">${esc(role.role_name)}</option>`)
    .join("")

  const rows = members.map((member) => `
    <tr>
      <td>${esc(member.email ?? "unknown")}
        ${member.is_owner ? '<span class="pill admin">owner</span>' : ""}</td>
      <td>${
    (member.roles ?? []).map((r) => `<span class="pill mono">${esc(r)}</span>`).join("") ||
    '<span class="empty">none</span>'
  }</td>
      <td>
        <form class="inline" method="post" action="${esc(action)}/members/role">
          ${csrfField(CSRF_FIELD, csrf)}
          <input type="hidden" name="user_id" value="${esc(member.user_id)}">
          <select name="role_id" required>
            <option value="">Assign role...</option>
            ${roleOptions}
          </select>
          <button type="submit" class="secondary">Assign</button>
        </form>
      </td>
      <td>${
    // The owner cannot be removed here. The database refuses it too; this is
    // just not offering a button that always fails.
    member.is_owner ? "" : `
        <form class="inline" method="post" action="${esc(action)}/members/remove">
          ${csrfField(CSRF_FIELD, csrf)}
          <input type="hidden" name="user_id" value="${esc(member.user_id)}">
          <button type="submit" class="danger">Remove</button>
        </form>`
  }</td>
    </tr>`).join("")

  return `
<section class="card">
  <h2>Members (${esc(org.member_count)})</h2>
  <p class="hint">Removing a member also ends any web session they have open.</p>
  ${scrollable("Members", `
  <table>
    <thead><tr><th>Email</th><th>Roles</th><th>Assign</th><th></th></tr></thead>
    <tbody>${rows}</tbody>
  </table>
  `, false)}
</section>`
}

function rolesCard(action: string, csrf: string, org: OrgDetail, roles: OrgRole[]): string {
  const customCount = org.custom_role_count ?? 0
  // 25 matches create_organisation_role COALESCE(v_max, 25); the column is NOT NULL
  // DEFAULT 25, so this only fires if the field is absent from the projection.
  const maxCustom = org.max_custom_roles ?? 25
  const atCap = maxCustom > 0 && customCount >= maxCustom

  const rows = roles.map((role) => `
    <tr>
      <td class="mono">${esc(role.role_name)}</td>
      <td><span class="pill${role.kind === "admin" ? " admin" : ""}">${esc(role.kind)}</span></td>
      <td>${esc(role.member_count)}</td>
      <td>${
    role.kind === "custom"
      ? `<form class="inline" method="post" action="${esc(action)}/roles/delete">
          ${csrfField(CSRF_FIELD, csrf)}
          <input type="hidden" name="role_id" value="${esc(role.role_id)}">
          <button type="submit" class="danger">Delete</button>
        </form>`
      : '<span class="empty">built in</span>'
  }</td>
    </tr>`).join("")

  const createForm = atCap
    ? `<p class="empty">This organisation is at its limit of ${esc(maxCustom)} custom roles.</p>`
    : `
    <form method="post" action="${esc(action)}/roles/create">
      ${csrfField(CSRF_FIELD, csrf)}
      <div class="row">
        <div>
          <label for="suffix">New role name</label>
          <input type="text" id="suffix" name="suffix" pattern="[a-z][a-z0-9_]{1,30}"
                 placeholder="reviewer" required>
        </div>
        <div>
          <label for="role_description">Description</label>
          <input type="text" id="role_description" name="description" maxlength="200">
        </div>
      </div>
      <p class="hint">The role will be created as <span class="mono">${
      esc(org.slug)
    }_&lt;name&gt;</span>, ranked below the administrator role.</p>
      <button type="submit">Create role</button>
    </form>`

  return `
<section class="card">
  <h2>Roles</h2>
  <p class="hint">${esc(customCount)} of ${esc(maxCustom)} custom roles used.</p>
  ${scrollable("Roles", `
  <table>
    <thead><tr><th>Role</th><th>Kind</th><th>Members</th><th></th></tr></thead>
    <tbody>${rows}</tbody>
  </table>
  `, false)}
  <div class="spaced">${createForm}</div>
</section>`
}

function invitesCard(
  action: string,
  csrf: string,
  invites: OrgInvite[],
  roles: OrgRole[],
): string {
  // An invite may not grant the admin-kind role: a leaked URL would be an
  // organisation takeover. The database refuses it; this does not offer it.
  const roleOptions = roles
    .filter((role) => role.kind !== "admin")
    .map((role) => `<option value="${esc(role.role_id)}">${esc(role.role_name)}</option>`)
    .join("")

  const rows = invites.length === 0
    ? '<tr><td colspan="5" class="empty">No invite links yet.</td></tr>'
    : invites.map((invite) => `
    <tr>
      <td>${esc(invite.label ?? "Invite")}
        <span class="mono">${esc(invite.token_prefix)}...</span></td>
      <td>${esc(invite.role_name ?? "default role")}</td>
      <td>${esc(invite.uses)}${invite.max_uses ? ` / ${esc(invite.max_uses)}` : ""}</td>
      <td>${
      invite.is_live
        ? '<span class="pill ok">live</span>'
        : '<span class="pill warn">expired</span>'
    } ${esc(formatDate(invite.expires_at))}</td>
      <td>${
      invite.is_live
        ? `<form class="inline" method="post" action="${esc(action)}/invites/revoke">
            ${csrfField(CSRF_FIELD, csrf)}
            <input type="hidden" name="invite_id" value="${esc(invite.invite_id)}">
            <button type="submit" class="danger">Revoke</button>
          </form>`
        : ""
    }</td>
    </tr>`).join("")

  return `
<section class="card">
  <h2>Invite links</h2>
  <p class="hint">A new link is shown once, at creation. Only its prefix is stored, so it cannot be recovered later.</p>
  ${scrollable("Invite links", `
  <table>
    <thead><tr><th>Label</th><th>Grants</th><th>Uses</th><th>Expires</th><th></th></tr></thead>
    <tbody>${rows}</tbody>
  </table>
  `, false)}
  <div class="spaced">
    <form method="post" action="${esc(action)}/invites/create">
      ${csrfField(CSRF_FIELD, csrf)}
      <div class="row">
        <div>
          <label for="label">Label</label>
          <input type="text" id="label" name="label" maxlength="80" placeholder="Engineering hires">
        </div>
        <div>
          <label for="invite_role">Grants role</label>
          <select id="invite_role" name="role_id">
            <option value="">Default member role</option>
            ${roleOptions}
          </select>
        </div>
        <div>
          <label for="max_uses">Max uses</label>
          <input type="number" id="max_uses" name="max_uses" min="1" max="1000" placeholder="unlimited">
        </div>
        <div>
          <label for="expires_in_hours">Expires in (hours)</label>
          <input type="number" id="expires_in_hours" name="expires_in_hours"
                 min="1" max="720" value="168" required>
        </div>
      </div>
      <button type="submit">Create invite link</button>
    </form>
  </div>
</section>`
}

function domainsCard(action: string, csrf: string, domains: OrgDomain[]): string {
  const rows = domains.length === 0
    ? '<tr><td colspan="4" class="empty">No domains claimed.</td></tr>'
    : domains.map((domain) => `
    <tr>
      <td>${esc(domain.domain)}${
      domain.is_primary ? ' <span class="pill admin">primary</span>' : ""
    }</td>
      <td>${
      domain.verified
        ? '<span class="pill ok">verified</span>'
        : '<span class="pill warn">unverified</span>'
    }</td>
      <td>${
      domain.verified ? "" : `<span class="mono">${esc(domain.dns_record_name)} TXT ${
        esc(domain.dns_record_value)
      }</span>`
    }</td>
      <td>
        ${
      domain.verified ? "" : `
        <form class="inline" method="post" action="${esc(action)}/domains/verify">
          ${csrfField(CSRF_FIELD, csrf)}
          <input type="hidden" name="domain_id" value="${esc(domain.domain_id)}">
          <button type="submit" class="secondary">Verify now</button>
        </form>`
    }
        ${
      domain.verified && !domain.is_primary
        ? `<form class="inline" method="post" action="${esc(action)}/domains/primary">
            ${csrfField(CSRF_FIELD, csrf)}
            <input type="hidden" name="domain_id" value="${esc(domain.domain_id)}">
            <button type="submit" class="secondary">Make primary</button>
          </form>`
        : ""
    }
        <form class="inline" method="post" action="${esc(action)}/domains/remove">
          ${csrfField(CSRF_FIELD, csrf)}
          <input type="hidden" name="domain_id" value="${esc(domain.domain_id)}">
          <button type="submit" class="danger">Remove</button>
        </form>
      </td>
    </tr>`).join("")

  return `
<section class="card">
  <h2>Domains</h2>
  <p class="hint">A verified domain lets people with a matching email address find and join this organisation. Add the TXT record, then press Verify.</p>
  ${scrollable("Domains", `
  <table>
    <thead><tr><th>Domain</th><th>Status</th><th>DNS record</th><th></th></tr></thead>
    <tbody>${rows}</tbody>
  </table>
  `, false)}
  <div class="spaced">
    <form method="post" action="${esc(action)}/domains/add">
      ${csrfField(CSRF_FIELD, csrf)}
      <div class="row">
        <div>
          <label for="domain">Add a domain</label>
          <input type="text" id="domain" name="domain" placeholder="example.com" required>
        </div>
      </div>
      <button type="submit">Add domain</button>
    </form>
  </div>
</section>`
}

function selectOption(value: string, label: string, current: string): string {
  return `<option value="${esc(value)}"${
    current === value ? " selected" : ""
  }>${esc(label)}</option>`
}

/**
 * The invite link on its own, for when the admin page data could not be loaded.
 *
 * The invite already exists and is live at this point, so redirecting would lose its plaintext
 * forever while reporting success - the admin would have to find it by prefix and revoke it. The
 * url does not depend on any of that page data, so it can always be shown.
 */
export function inviteOnlyPage(
  nonce: string,
  basePath: string,
  slug: string,
  inviteUrl: string,
): string {
  return layout({
    title: "Invite link created - BOSS",
    nonce,
    body: `
<header class="page"><h1>Invite link created</h1></header>
${newInviteCard(inviteUrl)}
<section class="card">
  <p class="hint">The rest of the configuration page could not be loaded just now. The invite
  above is live and is shown only once, so copy it before reloading.</p>
  <a href="${esc(basePath)}/o/${esc(encodeURIComponent(slug))}/admin">Back to configuration</a>
</section>`,
  })
}
