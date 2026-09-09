-- The broker schemas are operated outside this repository. Model their exact
-- encrypted-column contract locally; no production keys or rows are used.
begin;
select plan(5);
create table public.qbo_token_state (
    id integer primary key, client_id_enc text, client_secret_enc text,
    refresh_token_enc text, access_token_enc text);
create table public.google_token_state (
    id integer primary key, private_key_enc text, access_token_enc text);
select vault.create_secret(encode(extensions.gen_random_bytes(32), 'base64'),
    'master_encryption_key', 'transaction-local rotation fixture');
insert into public.qbo_token_state values (1, public.encrypt_text('client'),
    public.encrypt_text('secret'), public.encrypt_text('refresh'), public.encrypt_text('access'));
insert into public.google_token_state values (1, public.encrypt_text('pem'), public.encrypt_text('token'));
create temp table rotation_initial_key as select public.get_encryption_key() as value;
-- Rotate the Vault master_encryption_key, re-encrypting everything under it.
--
-- Run 2026-09-09. Kept because a rotation is not a one-off: this is the
-- procedure, and it is parameterless apart from the column list at the top.
--
-- Properties that make it safe to run against production:
--   * ONE DO block, so it is atomic no matter how the caller wraps statements.
--   * It fingerprints every row's PLAINTEXT (md5) under the old key BEFORE
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
  secret_id  uuid;
  backup_name text;
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
  -- Serialize rotations and freeze writers before reading the key or data.
  -- Lock the Vault row first, then tables in a fixed order. A waiting writer
  -- resumes only after the new key and all ciphertext commit together.
  select id into strict secret_id from vault.secrets
    where name = 'master_encryption_key' for update;
  lock table public.google_token_state, public.qbo_token_state,
    public.secret_metadata, public.secrets in access exclusive mode;
  old_key := public.get_encryption_key();

  -- #417 adds another encrypted field using a different encoding. Refuse a
  -- rotation until that format has an explicit adapter in this script.
  if to_regprocedure('public.safe_decrypt_twofa_secret(text)') is not null then
    raise exception 'TOTP encryption is installed; extend rotation coverage before running';
  end if;
  if exists (
    select 1 from information_schema.columns c
    where c.table_schema = 'public' and c.column_name ~ '(_enc$|_encrypted$)'
      and not exists (
        select 1 from generate_subscripts(cols, 1) j
        where cols[j][1] = c.table_name and cols[j][2] = c.column_name)
  ) then
    raise exception 'Unmapped encrypted column; extend rotation coverage before running';
  end if;

  -- pgcrypto truncates a key to the cipher key size, so a different length
  -- would silently change which bytes are the key.
  if length(new_key) <> length(old_key) then
    raise exception 'key shape mismatch: new % vs old %', length(new_key), length(old_key);
  end if;


  -- 1. Fingerprint every row's PLAINTEXT under the old key. md5 only.
  create temp table rot_fp(tbl text, col text, pk text, fp text) on commit drop;
  expected := 0;
  for i in 1 .. array_length(cols, 1) loop
    execute format($f$
      insert into rot_fp
      select %1$L, %2$L, %3$I::text,
             md5(pg_catalog.convert_from(
               extensions.decrypt(pg_catalog.decode(%2$I,'base64'), $1::bytea, 'aes'), 'utf8'))
      from public.%1$I where %2$I is not null
    $f$, cols[i][1], cols[i][2], cols[i][3]) using old_key;
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
  backup_name := 'master_encryption_key_retired_' || gen_random_uuid()::text;
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
        count(*) filter (where f.fp = md5(public.decrypt_text(t.%2$I))),
        count(*) filter (where f.fp is distinct from md5(public.decrypt_text(t.%2$I)))
      from public.%1$I t
      join rot_fp f on f.tbl = %1$L and f.col = %2$L and f.pk = t.%3$I::text
      where t.%2$I is not null
    $f$, cols[i][1], cols[i][2], cols[i][3]) into n, mismatched;
    verified := verified + n;
    if mismatched > 0 then
      raise exception 'ROLLING BACK: % rows of %.% no longer decrypt to their original plaintext',
        mismatched, cols[i][1], cols[i][2];
    end if;
  end loop;

  if verified <> expected then
    raise exception 'ROLLING BACK: verified % rows but fingerprinted % - a row was missed', verified, expected;
  end if;

  raise notice 'ROTATED. % of % rows verified byte-identical under the new key.', verified, expected;
end $$;

select isnt(public.get_encryption_key(), (select value from rotation_initial_key),
    'rotation replaces the master key');
select is((select public.decrypt_text(client_secret_enc) from public.qbo_token_state where id=1),
    'secret', 'QBO value survives rotation');
select is((select public.decrypt_text(private_key_enc) from public.google_token_state where id=1),
    'pem', 'Google key survives rotation');
-- A normal second invocation starts a fresh transaction and has no rot_fp.
drop table pg_temp.rot_fp;
-- Rotate the Vault master_encryption_key, re-encrypting everything under it.
--
-- Run 2026-09-09. Kept because a rotation is not a one-off: this is the
-- procedure, and it is parameterless apart from the column list at the top.
--
-- Properties that make it safe to run against production:
--   * ONE DO block, so it is atomic no matter how the caller wraps statements.
--   * It fingerprints every row's PLAINTEXT (md5) under the old key BEFORE
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
  secret_id  uuid;
  backup_name text;
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
  -- Serialize rotations and freeze writers before reading the key or data.
  -- Lock the Vault row first, then tables in a fixed order. A waiting writer
  -- resumes only after the new key and all ciphertext commit together.
  select id into strict secret_id from vault.secrets
    where name = 'master_encryption_key' for update;
  lock table public.google_token_state, public.qbo_token_state,
    public.secret_metadata, public.secrets in access exclusive mode;
  old_key := public.get_encryption_key();

  -- #417 adds another encrypted field using a different encoding. Refuse a
  -- rotation until that format has an explicit adapter in this script.
  if to_regprocedure('public.safe_decrypt_twofa_secret(text)') is not null then
    raise exception 'TOTP encryption is installed; extend rotation coverage before running';
  end if;
  if exists (
    select 1 from information_schema.columns c
    where c.table_schema = 'public' and c.column_name ~ '(_enc$|_encrypted$)'
      and not exists (
        select 1 from generate_subscripts(cols, 1) j
        where cols[j][1] = c.table_name and cols[j][2] = c.column_name)
  ) then
    raise exception 'Unmapped encrypted column; extend rotation coverage before running';
  end if;

  -- pgcrypto truncates a key to the cipher key size, so a different length
  -- would silently change which bytes are the key.
  if length(new_key) <> length(old_key) then
    raise exception 'key shape mismatch: new % vs old %', length(new_key), length(old_key);
  end if;


  -- 1. Fingerprint every row's PLAINTEXT under the old key. md5 only.
  create temp table rot_fp(tbl text, col text, pk text, fp text) on commit drop;
  expected := 0;
  for i in 1 .. array_length(cols, 1) loop
    execute format($f$
      insert into rot_fp
      select %1$L, %2$L, %3$I::text,
             md5(pg_catalog.convert_from(
               extensions.decrypt(pg_catalog.decode(%2$I,'base64'), $1::bytea, 'aes'), 'utf8'))
      from public.%1$I where %2$I is not null
    $f$, cols[i][1], cols[i][2], cols[i][3]) using old_key;
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
  backup_name := 'master_encryption_key_retired_' || gen_random_uuid()::text;
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
        count(*) filter (where f.fp = md5(public.decrypt_text(t.%2$I))),
        count(*) filter (where f.fp is distinct from md5(public.decrypt_text(t.%2$I)))
      from public.%1$I t
      join rot_fp f on f.tbl = %1$L and f.col = %2$L and f.pk = t.%3$I::text
      where t.%2$I is not null
    $f$, cols[i][1], cols[i][2], cols[i][3]) into n, mismatched;
    verified := verified + n;
    if mismatched > 0 then
      raise exception 'ROLLING BACK: % rows of %.% no longer decrypt to their original plaintext',
        mismatched, cols[i][1], cols[i][2];
    end if;
  end loop;

  if verified <> expected then
    raise exception 'ROLLING BACK: verified % rows but fingerprinted % - a row was missed', verified, expected;
  end if;

  raise notice 'ROTATED. % of % rows verified byte-identical under the new key.', verified, expected;
end $$;

select is((select count(*) from vault.secrets where name like 'master_encryption_key_retired_%'),
    2::bigint, 'both outgoing keys are retained across repeated rotations');
select is((select public.decrypt_text(refresh_token_enc) from public.qbo_token_state where id=1),
    'refresh', 'value survives the second rotation');
select * from finish();
rollback;
