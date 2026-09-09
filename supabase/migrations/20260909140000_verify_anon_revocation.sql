-- Enforce the postcondition even when REVOKE only emits a warning.
-- Preserve existing effective signed-in/server access before removing PUBLIC.
-- This includes access derived only from PUBLIC, including at CREATE time;
-- new sensitive helpers must explicitly revoke authenticated as well as anon.
-- Replacements with an already restricted ACL do not gain that role. Deliberate
-- anonymous grants must be re-issued after CREATE OR REPLACE as well as CREATE.
-- Failure must abort the DDL, not leave an exposed routine behind a warning.
create or replace function public.enforce_explicit_anon_grants()
returns event_trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  obj record;
  role_name text;
begin
  for obj in select * from pg_catalog.pg_event_trigger_ddl_commands() loop
    if obj.schema_name = 'public' and obj.object_type in ('function', 'procedure') then
      foreach role_name in array array['authenticated', 'service_role'] loop
        if pg_catalog.has_function_privilege(role_name, obj.objid, 'EXECUTE') then
          execute format('grant execute on routine %s to %I', obj.object_identity, role_name);
        end if;
      end loop;
      execute format('revoke all on routine %s from public, anon', obj.object_identity);
      -- GRANT/REVOKE can warn without changing an ACL. Verify the effective
      -- postcondition, including permissions inherited through another role.
      if pg_catalog.has_function_privilege('anon', obj.objid, 'EXECUTE') then
        raise exception using errcode = '42501',
          message = format('Anonymous EXECUTE remains on %s', obj.object_identity),
          hint = 'Review routine ownership and inherited anon grants. The guard must be authorized to revoke access; do not bypass it for extension routines in public.';
      end if;
    end if;
  end loop;
end;
$$;
revoke all on function public.enforce_explicit_anon_grants() from public, anon, authenticated;
