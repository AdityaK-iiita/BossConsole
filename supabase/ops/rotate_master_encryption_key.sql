-- Rotate the Vault master_encryption_key, re-encrypting everything under it.
--
-- Run 2026-09-09. Kept because a rotation is not a one-off: this is the
-- procedure, and it is parameterless apart from the column list at the top.
--
-- Properties that make it safe to run against production:
--   * ONE DO block, so it is atomic no matter how the caller wraps statements.
--   * It fingerprints every row's plaintext (HMAC-SHA256 with an ephemeral per-run key) under the old key BEFORE
--     touching anything, and after the swap re-derives those fingerprints
--     through public.decrypt_text - which re-reads the Vault, so the check
--     proves both that the swap took effect and that every plaintext survived.
--     Any mismatch, or any row count drift, RAISES and therefore rolls back.
--     A broken rotation cannot commit.
--   * No key and no plaintext is ever returned to the caller.
--   * The old key is preserved under an explicit name. Do NOT delete it while
--     any pre-rotation database backup is still retained - those backups are
--     encrypted under it and are unrecoverable without it.
--
-- Before running, re-derive the column list. Two independent methods should
-- agree, as they did here (8 columns, 184 rows):
--   a) functions whose body calls encrypt_text/decrypt_text, and on what;
--   b) every text column in schema public named ~ '(_enc$|_encrypted$|...)'.
-- A column present in neither is the one risk atomicity does not cover.

-- Rotate master_encryption_key. ONE DO block, so it is atomic regardless of how
-- the caller wraps statements: any verification failure raises, and nothing
-- commits. No key and no plaintext is ever returned to the caller.
--
-- Why rotation and not just the revoke: public.get_encryption_key() was
-- SECURITY DEFINER and executable by `anon`, and the project anon key is
-- compiled into the public BossConsole repo. A live unauthenticated call
-- returned the key. Revoking access does not un-disclose it.
do $$
declare
  old_key    text;
  new_key    text := encode(extensions.gen_random_bytes(32), 'base64');
  fingerprint_key bytea := extensions.gen_random_bytes(32);
  secret_id  uuid;
  backup_name text;
  table_name text;
  cols text[][] := array[
    array['secrets','password_encrypted','id'],
    array['secret_metadata','recovery_codes_encrypted','id'],
    array['qbo_token_state','client_id_enc','id'],
    array['qbo_token_state','client_secret_enc','id'],
    array['qbo_token_state','refresh_token_enc','id'],
    array['qbo_token_state','access_token_enc','id'],
    array['google_token_state','private_key_enc','id'],
    array['google_token_state','access_token_enc','id']
  ];
  i int; n int; expected int; verified int; mismatched int;
begin
  -- Serialize invocations, then block readers and writers on mapped tables before
  -- reading the key. Supabase's postgres role cannot SELECT FOR UPDATE on
  -- vault.secrets: writes are exposed only through vault.update_secret.
  -- Coordinate any other key-management operation outside this procedure.
  perform pg_catalog.pg_advisory_xact_lock(423, 1200);
  -- Core vault tables are required. Brokers are optional deployments; absent
  -- tables contain nothing to rotate. Existing broker tables keep the exact
  -- documented column contract and an unexpected schema still fails closed.
  if to_regclass('public.secrets') is null or to_regclass('public.secret_metadata') is null then
    raise exception 'Core secret tables are missing';
  end if;
  select array_agg(array[cols[j][1], cols[j][2], cols[j][3]] order by j)
    into cols
    from generate_subscripts(cols, 1) j
    where to_regclass(format('public.%I', cols[j][1])) is not null;
  for table_name in
    select distinct cols[j][1] from generate_subscripts(cols, 1) j order by 1
  loop
    execute format('lock table public.%I in access exclusive mode', table_name);
  end loop;
  select id into strict secret_id from vault.secrets
    where name = 'master_encryption_key';
  old_key := public.get_encryption_key();

  -- #417 adds another encrypted field using a different encoding. Refuse a
  -- rotation until that format has an explicit adapter in this script.
  if to_regprocedure('public.safe_decrypt_twofa_secret(text)') is not null then
    raise exception 'TOTP encryption is installed; extend rotation coverage before running';
  end if;
  if exists (
    select 1 from information_schema.columns c
    join pg_catalog.pg_namespace ns on ns.nspname = c.table_schema
    join pg_catalog.pg_class rel on rel.relnamespace = ns.oid and rel.relname = c.table_name
    where c.table_schema = 'public' and c.column_name ~ '(_enc$|_encrypted$)'
      and rel.relkind in ('r', 'p')
      and not exists (
        select 1 from generate_subscripts(cols, 1) j
        where cols[j][1] = c.table_name and cols[j][2] = c.column_name)
  ) then
    raise exception 'Unmapped encrypted column; extend rotation coverage before running';
  end if;

  -- The old key may be the documented hex string or a deployed base64 key.
  -- Decrypt with its original bytes, then encrypt with the new key's bytes;
  -- equal textual lengths are not a cryptographic requirement.

  -- 1. Fingerprint plaintext with a per-run HMAC key kept only in this block.
  -- A spilled temp page must not provide unsalted password hashes.
  create temp table rot_fp(tbl text, col text, pk text, fp text) on commit drop;
  expected := 0;
  for i in 1 .. array_length(cols, 1) loop
    execute format($f$
      insert into rot_fp
      select %1$L, %2$L, %3$I::text,
             pg_catalog.encode(extensions.hmac(
               extensions.decrypt(pg_catalog.decode(%2$I,'base64'), $1::bytea, 'aes'),
               $2, 'sha256'), 'hex')
      from public.%1$I where %2$I is not null
    $f$, cols[i][1], cols[i][2], cols[i][3]) using old_key, fingerprint_key;
    get diagnostics n = row_count;
    expected := expected + n;
  end loop;
  raise notice 'fingerprinted % rows', expected;

  -- 2. Re-encrypt in place, old key to new key. Never materialises plaintext.
  for i in 1 .. array_length(cols, 1) loop
    execute format($f$
      update public.%1$I
         set %2$I = pg_catalog.encode(
               extensions.encrypt(
                 extensions.decrypt(pg_catalog.decode(%2$I,'base64'), $1::bytea, 'aes'),
                 $2::bytea, 'aes'), 'base64')
       where %2$I is not null
    $f$, cols[i][1], cols[i][2]) using old_key, new_key;
  end loop;

  -- 3. Keep EVERY outgoing key, including on subsequent rotations. Retain it
  -- while any backup encrypted under it exists. Never delete on verification.
  backup_name := 'master_encryption_key_retired_' || pg_catalog.gen_random_uuid()::text;
  perform vault.create_secret(old_key, backup_name,
    'Retired master key. Retain while any backup encrypted under this key exists.');

  -- 4. Swap the live key.
  perform vault.update_secret(secret_id, new_key, 'master_encryption_key',
    'Master key for encrypting user secrets. Rotated 2026-09-09 after the previous value was found to be retrievable by unauthenticated callers.');

  -- 5. Verify through public.decrypt_text, which re-reads the vault - so this
  --    proves the swap took effect AND that every plaintext survived.
  verified := 0; mismatched := 0;
  for i in 1 .. array_length(cols, 1) loop
    execute format($f$
      select
        count(*) filter (where f.fp = pg_catalog.encode(extensions.hmac(pg_catalog.convert_to(public.decrypt_text(t.%2$I), 'utf8'), $1, 'sha256'), 'hex')),
        count(*) filter (where f.fp is distinct from pg_catalog.encode(extensions.hmac(pg_catalog.convert_to(public.decrypt_text(t.%2$I), 'utf8'), $1, 'sha256'), 'hex'))
      from public.%1$I t
      join rot_fp f on f.tbl = %1$L and f.col = %2$L and f.pk = t.%3$I::text
      where t.%2$I is not null
    $f$, cols[i][1], cols[i][2], cols[i][3]) into n, mismatched using fingerprint_key;
    verified := verified + n;
    if mismatched > 0 then
      raise exception 'ROLLING BACK: % rows of %.% no longer decrypt to their original plaintext',
        mismatched, cols[i][1], cols[i][2];
    end if;
  end loop;

  if verified <> expected then
    raise exception 'ROLLING BACK: verified % rows but fingerprinted % - a row was missed', verified, expected;
  end if;

  -- Remove fingerprints now, including when the operator invokes us again
  -- inside the same outer transaction. Errors roll back their creation.
  drop table pg_temp.rot_fp;
  raise notice 'ROTATED. % of % rows verified byte-identical under the new key.', verified, expected;
end $$;
