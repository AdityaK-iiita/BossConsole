/**
 * The single channel from this function to the database.
 *
 * Every org RPC follows one shape: it returns
 * `{ success: true, data: ... }` or `{ success: false, error: "..." }`, and it
 * takes an optional `p_actor_id` that only service_role may set (see
 * resolve_org_actor). This function is a service_role caller, so `p_actor_id`
 * is how the browser's identity -- taken from the signed cookie, never from a
 * request parameter -- reaches an RPC that would otherwise read auth.uid().
 *
 * THE RULE THIS MODULE EXISTS TO ENFORCE: no raw table access. A service_role
 * client bypasses RLS, so a `.from("organisations").select()` here would be an
 * authorization decision written in TypeScript, sitting outside every test the
 * database layer has. Reads and writes both go through gated RPCs that
 * re-derive authority from the actor.
 */

import { createClient, type SupabaseClient } from "@supabase/supabase-js"
import { readConfig } from "./config.ts"

export type RpcResult<T> =
  | { ok: true; data: T }
  | { ok: false; error: string }

let cached: SupabaseClient | null = null

/** The service-role client, one per isolate. */
export function serviceClient(): SupabaseClient {
  if (!cached) {
    const config = readConfig()
    cached = createClient(config.supabaseUrl, config.serviceRoleKey, {
      auth: { persistSession: false, autoRefreshToken: false },
    })
  }
  return cached
}

/** Test seam: replace the client. */
export function setServiceClientForTests(client: SupabaseClient | null): void {
  cached = client
}

/**
 * Call an org RPC and normalise its envelope.
 *
 * Transport failures and RPC-reported failures collapse into the same
 * `{ ok: false }` shape, because callers treat them identically: render the
 * message, change nothing. The distinction is preserved in the log, not in the
 * response - a page that says "database unreachable" instead of "Organisation
 * not found" is telling an unauthenticated visitor about our infrastructure.
 */
export async function callRpc<T = unknown>(
  fn: string,
  params: Record<string, unknown>,
): Promise<RpcResult<T>> {
  const { data, error } = await serviceClient().rpc(fn, params)

  if (error) {
    // The params are NOT logged. p_token would put a live bearer credential in
    // the log stream, which is the one place the handoff design is careful to
    // keep it out of.
    console.error(`rpc ${fn} failed:`, error.message)
    return { ok: false, error: "Something went wrong. Please try again." }
  }

  if (data === null || typeof data !== "object") {
    console.error(`rpc ${fn} returned a non-envelope response`)
    return { ok: false, error: "Something went wrong. Please try again." }
  }

  const envelope = data as { success?: unknown; error?: unknown; data?: unknown }

  // Fail closed on a missing `success`. An RPC that returns a bare object
  // rather than the envelope is a bug, and reading that as success would let a
  // renamed function silently look like an empty-but-fine result.
  if (envelope.success !== true) {
    const message = typeof envelope.error === "string"
      ? envelope.error
      : "Something went wrong. Please try again."
    return { ok: false, error: message }
  }

  return { ok: true, data: (envelope.data ?? envelope) as T }
}

/**
 * Call an RPC on behalf of the cookie's subject.
 *
 * `actorId` always comes from a verified session. It is never read from a query
 * string, a form field or a header: `p_actor_id` is a service_role-only
 * impersonation parameter, and sourcing it from the request would let anyone
 * name any user.
 */
export function callForActor<T = unknown>(
  fn: string,
  actorId: string,
  params: Record<string, unknown> = {},
): Promise<RpcResult<T>> {
  return callRpc<T>(fn, { ...params, p_actor_id: actorId })
}
