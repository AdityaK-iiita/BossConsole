-- pgTAP tests for handoff tokens, JWT claims, discovery and table shape
-- (migrations 20260801000000, 20260801050000, 20260801060000, 20260801030000).
-- Run with: supabase test db
--
-- The handoff token is a bearer credential for its short life, so the
-- assertions that matter are single-use, expiry, and the absence of any
-- parameter naming a subject. The JWT hook assertions are about the grant that
-- breaks every login if it is missing.

begin;
select plan(37);

-- ---------------------------------------------------------------------------
-- Fixtures
-- ---------------------------------------------------------------------------
insert into auth.users (id, email, email_confirmed_at) values
    ('70000000-0000-0000-0000-000000000001', 'hoowner@pgtap.test',    now()),
    ('70000000-0000-0000-0000-000000000002', 'homember@pgtap.test',   now()),
    ('70000000-0000-0000-0000-000000000003', 'hooutsider@pgtap.test', now());

select public.create_organisation_internal(
    p_slug=>'pgthpub', p_name=>'Public Handoff Org',
    p_description=>'findable',
    p_owner_id=>'70000000-0000-0000-0000-000000000001',
    p_visibility=>'public', p_join_policy=>'open');
select public.create_organisation_internal(
    p_slug=>'pgthpriv', p_name=>'Private Handoff Org',
    p_owner_id=>'70000000-0000-0000-0000-000000000001',
    p_visibility=>'private', p_join_policy=>'invite_only');

select set_config('request.jwt.claims', '{"role":"service_role"}', true);
select public.join_organisation(
    (select id from public.organisations where slug='pgthpub'),
    '70000000-0000-0000-0000-000000000002');


-- ===========================================================================
-- Table shape: the constraints the rest of the design leans on
-- ===========================================================================
select has_table('public', 'organisations', 'organisations exists');
select has_table('public', 'organisation_members', 'organisation_members exists');
select has_table('public', 'organisation_handoff_tokens', 'handoff tokens table exists');
select has_table('public', 'reserved_email_domains', 'reserved_email_domains exists');

-- owner_id is ON DELETE RESTRICT: deleting a user must not cascade away an
-- organisation along with its plugins and secrets.
select is(
    (select rc.delete_rule
       from information_schema.table_constraints tc
       join information_schema.referential_constraints rc
         on rc.constraint_name = tc.constraint_name
       join information_schema.key_column_usage kcu
         on kcu.constraint_name = tc.constraint_name
      where tc.table_name = 'organisations'
        and kcu.column_name = 'owner_id'
      limit 1),
    'RESTRICT',
    'organisations.owner_id is ON DELETE RESTRICT -- deleting a user must not delete an organisation'
);

-- A role belongs to at most one organisation, or org A's admins would govern org B.
select ok(
    exists (
        select 1 from pg_indexes
        where tablename = 'organisation_roles'
          and indexdef ilike '%unique%'
          and indexdef ilike '%role_id%'
    ),
    'organisation_roles.role_id is unique -- a role belongs to at most one organisation'
);

-- Consumer mailboxes must be unclaimable, or anyone registering gmail.com would
-- absorb every consumer signup.
select ok(
    exists (select 1 from public.reserved_email_domains where domain = 'gmail.com'),
    'gmail.com is a reserved email domain'
);
select ok(
    (select count(*) from public.reserved_email_domains) >= 5,
    'the reserved-domain list is seeded, not empty'
);

-- The hot path: every is_org_member() call, hence every org RLS check.
select ok(
    exists (
        select 1 from pg_indexes
        where tablename = 'organisation_members'
          and indexdef like '%org_id%'
          and indexdef like '%user_id%'
    ),
    'organisation_members is indexed on (org_id, user_id)'
);


-- ===========================================================================
-- Handoff tokens
-- ===========================================================================
-- There is no p_user_id parameter, and that absence IS the security property:
-- the subject is auth.uid() server-side, so a caller cannot mint a handoff that
-- authenticates somebody else.
select is(
    (select count(*)::int
       from information_schema.parameters
      where specific_schema = 'public'
        and parameter_name in ('p_user_id', 'p_subject', 'p_actor_id')
        and specific_name in (
            select specific_name from information_schema.routines
             where routine_schema='public'
               and routine_name='mint_organisation_handoff_token')),
    0,
    'mint_organisation_handoff_token takes NO parameter naming a subject'
);

select ok(
    has_function_privilege('authenticated',
        'public.mint_organisation_handoff_token(uuid,text,integer)', 'execute'),
    'an authenticated user may mint their own handoff token'
);
select ok(
    NOT has_function_privilege('anon',
        'public.mint_organisation_handoff_token(uuid,text,integer)', 'execute'),
    'anon may not mint one'
);
select ok(
    NOT has_function_privilege('authenticated',
        'public.consume_organisation_handoff_token(text)', 'execute'),
    'only the edge function consumes tokens -- authenticated may not'
);
select ok(
    has_function_privilege('service_role',
        'public.consume_organisation_handoff_token(text)', 'execute'),
    'service_role consumes them'
);

-- Only the hash is stored, so a leaked table does not yield usable tokens.
-- has_column IS a test function; it returns a TAP line, not a boolean, so it is
-- called directly rather than wrapped in ok().
select has_column('public', 'organisation_handoff_tokens', 'token_hash',
    'only the hash is stored, so a leaked table yields no usable tokens');
select ok(
    NOT exists (
        select 1 from information_schema.columns
        where table_name='organisation_handoff_tokens' and column_name='token'
    ),
    'the plaintext token is NOT a column'
);

-- Mint one as the member, then consume it exactly once.
select set_config('request.jwt.claims',
    '{"sub":"70000000-0000-0000-0000-000000000002","role":"authenticated"}', true);

create temporary table t_tok as
select public.mint_organisation_handoff_token(
    (select id from public.organisations where slug='pgthpub'), 'org_view', 300) as r;

select ok(
    (select (r ->> 'success')::boolean from t_tok),
    'a member can mint a handoff token for their organisation'
);

select set_config('request.jwt.claims', '{"role":"service_role"}', true);

select is(
    (select public.consume_organisation_handoff_token((select r ->> 'token' from t_tok))
            ->> 'user_id'),
    '70000000-0000-0000-0000-000000000002',
    'consuming it yields the minting user'
);
select is(
    (select public.consume_organisation_handoff_token((select r ->> 'token' from t_tok))
            ->> 'error'),
    'Token is invalid, expired or already used',
    'the SAME token cannot be consumed twice'
);
select is(
    (select public.consume_organisation_handoff_token('not-a-real-token') ->> 'error'),
    'Token is invalid, expired or already used',
    'an unknown token reports exactly what a used one does -- no oracle'
);
select is(
    (select public.consume_organisation_handoff_token(null) ->> 'error'),
    'Token is invalid, expired or already used',
    'a null token reports the same'
);

-- An expired token is refused even though the row exists and is unconsumed.
select set_config('request.jwt.claims',
    '{"sub":"70000000-0000-0000-0000-000000000002","role":"authenticated"}', true);
create temporary table t_tok2 as
select public.mint_organisation_handoff_token(
    (select id from public.organisations where slug='pgthpub'), 'org_view', 300) as r;
select set_config('request.jwt.claims', '{"role":"service_role"}', true);

update public.organisation_handoff_tokens
   set expires_at = now() - interval '1 minute'
 where consumed_at is null;

select is(
    (select public.consume_organisation_handoff_token((select r ->> 'token' from t_tok2))
            ->> 'error'),
    'Token is invalid, expired or already used',
    'an expired but unconsumed token is refused'
);

-- A non-member cannot mint for a private organisation.
select set_config('request.jwt.claims',
    '{"sub":"70000000-0000-0000-0000-000000000003","role":"authenticated"}', true);
select ok(
    NOT (select public.mint_organisation_handoff_token(
        (select id from public.organisations where slug='pgthpriv'), 'org_view', 300)
        ->> 'success')::boolean,
    'a non-member cannot mint a token for a private organisation'
);
select set_config('request.jwt.claims', '{"role":"service_role"}', true);


-- ===========================================================================
-- JWT claims
-- ===========================================================================
-- Omitting this grant breaks EVERY login, because GoTrue calls the hook as
-- supabase_auth_admin and a permission error there fails the whole token issue.
select ok(
    has_function_privilege('supabase_auth_admin',
        'public.get_user_orgs_for_hook(uuid)', 'execute'),
    'supabase_auth_admin may call the claims hook -- without this, every login breaks'
);
select ok(
    NOT has_function_privilege('authenticated',
        'public.get_user_orgs_for_hook(uuid)', 'execute'),
    'authenticated may not call it directly'
);

-- SLUGS, not ids. A token carries these to every request, and a slug is both
-- shorter and the thing a policy is actually written against.
select ok(
    public.get_user_orgs_for_hook('70000000-0000-0000-0000-000000000002') -> 'orgs'
        ? 'pgthpub',
    'the hook lists the organisation by slug'
);
select ok(
    NOT (public.get_user_orgs_for_hook('70000000-0000-0000-0000-000000000002') -> 'orgs'
        ? 'pgthpriv'),
    'and does not list one the user does not belong to'
);
select ok(
    public.get_user_orgs_for_hook('70000000-0000-0000-0000-000000000001') -> 'org_admin'
        ? 'pgthpub',
    'the owner is listed as an admin of their organisation'
);
select ok(
    NOT (public.get_user_orgs_for_hook('70000000-0000-0000-0000-000000000002') -> 'org_admin'
        ? 'pgthpub'),
    'an ordinary member is not'
);
select ok(
    public.get_user_orgs_for_hook('70000000-0000-0000-0000-000000000003') -> 'orgs'
        = '[]'::jsonb,
    'a user in no organisation gets an empty array, not null'
);

-- The hook must never throw: an exception there is a failed login, not a
-- missing claim.
select lives_ok(
    $$ select public.get_user_orgs_for_hook('00000000-0000-0000-0000-0000000000ff') $$,
    'the hook tolerates an unknown user rather than throwing'
);
select ok(
    public.get_user_orgs_for_hook('00000000-0000-0000-0000-0000000000ff') -> 'orgs'
        = '[]'::jsonb,
    'and degrades to empty claims rather than a missing key'
);


-- ===========================================================================
-- Discovery
-- ===========================================================================
select ok(
    exists (
        select 1 from jsonb_array_elements(
            public.search_organisations('Public Handoff', 20, 0,
                '70000000-0000-0000-0000-000000000003') -> 'data') e
        where e ->> 'slug' = 'pgthpub'
    ),
    'a public organisation is discoverable by an outsider'
);
select ok(
    NOT exists (
        select 1 from jsonb_array_elements(
            public.search_organisations('Private Handoff', 20, 0,
                '70000000-0000-0000-0000-000000000003') -> 'data') e
        where e ->> 'slug' = 'pgthpriv'
    ),
    'a PRIVATE organisation is not discoverable, even by exact name'
);
select ok(
    NOT exists (
        select 1 from jsonb_array_elements(
            public.search_organisations('pgthpriv', 20, 0,
                '70000000-0000-0000-0000-000000000003') -> 'data') e
        where e ->> 'slug' = 'pgthpriv'
    ),
    'nor by exact slug -- search is not a slug oracle'
);
select is(
    (select e ->> 'available_action'
       from jsonb_array_elements(
            public.search_organisations('Public Handoff', 20, 0,
                '70000000-0000-0000-0000-000000000003') -> 'data') e
      where e ->> 'slug' = 'pgthpub'),
    'join',
    'discovery carries the action the server decided, not one the client infers'
);
select is(
    (select e ->> 'available_action'
       from jsonb_array_elements(
            public.search_organisations('Public Handoff', 20, 0,
                '70000000-0000-0000-0000-000000000002') -> 'data') e
      where e ->> 'slug' = 'pgthpub'),
    'member',
    'an existing member sees member, not join'
);

select * from finish();
rollback;
