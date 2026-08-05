/**
 * The member-facing organisation overview.
 *
 * Read-only. Every mutation lives on the admin page, which keeps this view free
 * of CSRF concerns entirely.
 */

import { esc } from "../utils/html.ts"
import { layout, tabs } from "./layout.ts"
import type { OrgDetail, OrgMember, OrgRole } from "../services/org.ts"

export interface OrgPageOptions {
  nonce: string
  basePath: string
  org: OrgDetail
  members: OrgMember[]
  roles: OrgRole[]
}

export function orgPage({ nonce, basePath, org, members, roles }: OrgPageOptions): string {
  const active = members.filter((m) => m.status === "active")

  return layout({
    title: `${org.name} - BOSS`,
    nonce,
    body: `
<header class="page">
  <h1>${esc(org.name)}</h1>
  <span class="slug">@${esc(org.slug)}</span>
  ${org.is_system ? '<span class="pill">system</span>' : ""}
</header>
<p class="sub">${esc(org.description ?? "No description.")}</p>
${tabs(basePath, org.slug, "overview", org.is_admin)}

<section class="card">
  <div class="stat"><b>${esc(org.member_count)}</b><span>members</span></div>
  <div class="stat"><b>${esc(roles.length)}</b><span>roles</span></div>
  <div class="stat"><b>${esc(org.visibility)}</b><span>visibility</span></div>
  <div class="stat"><b>${esc(org.join_policy.replace(/_/g, " "))}</b><span>joining</span></div>
  ${
      org.primary_domain
        ? `<div class="stat"><b>${esc(org.primary_domain)}</b><span>domain</span></div>`
        : ""
    }
</section>

<section class="card">
  <h2>Members</h2>
  <p class="hint">Owned by ${esc(org.owner_email ?? "unknown")}.</p>
  ${membersTable(active)}
</section>

<section class="card">
  <h2>Roles</h2>
  <p class="hint">Roles are backed by the BOSS role system. Permissions are granted per role.</p>
  ${rolesTable(roles)}
</section>`,
  })
}

function membersTable(members: OrgMember[]): string {
  if (members.length === 0) return '<p class="empty">No members yet.</p>'

  const rows = members.map((member) => `
    <tr>
      <td>${esc(member.email ?? "unknown")}</td>
      <td>${
    member.is_owner
      ? '<span class="pill admin">owner</span>'
      : member.is_admin
      ? '<span class="pill admin">admin</span>'
      : '<span class="pill">member</span>'
  }</td>
      <td>${(member.roles ?? []).map((r) => `<span class="pill mono">${esc(r)}</span>`).join("")}</td>
      <td>${esc(formatDate(member.joined_at))}</td>
    </tr>`).join("")

  return `<table>
  <thead><tr><th>Email</th><th>Standing</th><th>Roles</th><th>Joined</th></tr></thead>
  <tbody>${rows}</tbody>
</table>`
}

function rolesTable(roles: OrgRole[]): string {
  if (roles.length === 0) return '<p class="empty">No roles.</p>'

  const rows = roles.map((role) => `
    <tr>
      <td class="mono">${esc(role.role_name)}</td>
      <td><span class="pill${role.kind === "admin" ? " admin" : ""}">${esc(role.kind)}</span></td>
      <td>${esc(role.member_count)}</td>
      <td>${
    (role.permissions ?? []).length === 0
      ? '<span class="empty">none</span>'
      : (role.permissions ?? []).map((p) => `<span class="pill mono">${esc(p)}</span>`).join("")
  }</td>
    </tr>`).join("")

  return `<table>
  <thead><tr><th>Role</th><th>Kind</th><th>Members</th><th>Permissions</th></tr></thead>
  <tbody>${rows}</tbody>
</table>`
}

/**
 * Date for display, or an em-dash-free placeholder.
 *
 * Rendered as a plain ISO date rather than a locale string: the edge runtime's
 * locale is not the viewer's, so `toLocaleDateString` would silently pick one
 * and be wrong for most readers.
 */
export function formatDate(value: string | null): string {
  if (!value) return "-"
  const parsed = new Date(value)
  if (Number.isNaN(parsed.getTime())) return "-"
  return parsed.toISOString().slice(0, 10)
}
