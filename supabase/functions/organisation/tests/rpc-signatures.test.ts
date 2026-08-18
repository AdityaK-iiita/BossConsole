/**
 * Every RPC this function names must exist in the migrations with the parameters we send.
 *
 * THE BLIND SPOT THIS EXISTS TO CLOSE. `createRpcStub` keys its responses on the function NAME and
 * ignores the params entirely, so an RPC called with parameter names the database does not have
 * passes every route test, every time. PostgREST resolves a function by its argument names, so
 * such a call fails to resolve at runtime and the page renders a generic refusal.
 *
 * That is not hypothetical: `mark_organisation_domain_verified` was called through `callForActor`,
 * which appends `p_actor_id` to every call. That function takes `p_domain_id` and `p_verified_by`
 * and no `p_actor_id`, so DOMAIN VERIFICATION NEVER COMPLETED - the DNS probe passed and the write
 * that follows it failed, reported as "The change was refused." 159 tests were green throughout.
 *
 * Source-scanned rather than executed, because the failure is in the shape of a call and there is
 * no database here to make it against.
 */

import { assert, assertEquals } from "@std/assert"

const HERE = new URL(".", import.meta.url).pathname
const FUNCTION_DIR = `${HERE}..`
const MIGRATIONS_DIR = `${HERE}../../../migrations`

/** Every `.ts` file under the function, excluding the tests themselves. */
async function sourceFiles(dir: string): Promise<string[]> {
  const found: string[] = []
  for await (const entry of Deno.readDir(dir)) {
    const path = `${dir}/${entry.name}`
    if (entry.isDirectory) {
      if (entry.name === "tests") continue
      found.push(...await sourceFiles(path))
    } else if (entry.name.endsWith(".ts")) {
      found.push(path)
    }
  }
  return found
}

/**
 * Parameter names of every `public` function the migrations define, latest definition wins.
 *
 * Sorted by filename first, because a function can be dropped and recreated with a different
 * argument list - `submit_organisation_request` gained `p_website` that way - and the signature
 * that matters is the one the last migration leaves behind.
 */
async function migrationSignatures(): Promise<Map<string, Set<string>>> {
  const files: string[] = []
  for await (const entry of Deno.readDir(MIGRATIONS_DIR)) {
    if (entry.isFile && entry.name.endsWith(".sql")) files.push(entry.name)
  }
  files.sort()

  const signatures = new Map<string, Set<string>>()
  const declaration =
    /CREATE OR REPLACE FUNCTION\s+"public"\."([a-z0-9_]+)"\s*\(([\s\S]*?)\)\s*RETURNS/gi

  for (const name of files) {
    const sql = await Deno.readTextFile(`${MIGRATIONS_DIR}/${name}`)
    for (const match of sql.matchAll(declaration)) {
      const params = new Set(
        [...match[2].matchAll(/"(p_[a-z0-9_]+)"/gi)].map((m) => m[1].toLowerCase()),
      )
      signatures.set(match[1].toLowerCase(), params)
    }
  }
  return signatures
}

Deno.test("the migrations are readable from here, so the assertions are not vacuous", async () => {
  const signatures = await migrationSignatures()
  // A wrong path would make every loop below iterate over nothing and pass. This is the floor that
  // makes the rest mean something: a real number, not merely non-zero.
  assert(
    signatures.size > 30,
    `only ${signatures.size} functions parsed from ${MIGRATIONS_DIR} - the path or the regex is wrong`,
  )
  assert(signatures.has("submit_organisation_request"), "a known function is missing")
})

Deno.test("every callForActor target actually accepts p_actor_id", async () => {
  const signatures = await migrationSignatures()
  const files = await sourceFiles(FUNCTION_DIR)

  const targets = new Set<string>()
  for (const path of files) {
    const source = await Deno.readTextFile(path)
    for (const match of source.matchAll(/callForActor(?:<[^>]*>)?\(\s*"([a-z0-9_]+)"/g)) {
      targets.add(match[1])
    }
  }

  assert(targets.size > 0, "no callForActor call sites found - the scan is broken")

  for (const fn of targets) {
    const params = signatures.get(fn)
    assert(params, `callForActor("${fn}") names a function no migration defines`)
    assert(
      params.has("p_actor_id"),
      `callForActor("${fn}") appends p_actor_id, but that function takes only ` +
        `[${[...params].join(", ")}]. PostgREST resolves by argument name, so this call cannot ` +
        `resolve and every use of it fails as a generic refusal.`,
    )
  }
})

Deno.test("mark_organisation_domain_verified is not called through callForActor", async () => {
  // The specific regression, pinned by name. The sweep above would catch it too, but this states
  // what broke so a future reader does not have to reconstruct it from a failing sweep.
  const source = await Deno.readTextFile(`${FUNCTION_DIR}/routes/domains.ts`)
  assertEquals(
    /callForActor\(\s*"mark_organisation_domain_verified"/.test(source),
    false,
    "this RPC has no p_actor_id; it must go through callRpc with p_verified_by",
  )
  assert(
    /callRpc\(\s*"mark_organisation_domain_verified"[\s\S]{0,200}p_verified_by/.test(source),
    "the verify handler must record who verified the domain",
  )
})
