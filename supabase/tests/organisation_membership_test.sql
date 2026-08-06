-- pgTAP tests for membership and the join/request decision
-- (migrations 20260801030000, 20260801000000).
-- Run with: supabase test db
--
-- organisation_available_action is the single source of truth: join_organisation
-- and request_organisation_membership both consult it rather than re-reading the
-- join policy, so most of these assertions are about that one function and the
-- two entry points agreeing with it.

begin;
select plan(27);

-- ---------------------------------------------------------------------------
-- Fixtures: one organisation per join policy, plus a domain-verified one.
-- ---------------------------------------------------------------------------
insert into auth.users (id, email, email_confirmed_at) values
    ('50000000-0000-0000-0000-000000000001', 'memowner@pgtap.test',   now()),
    ('50000000-0000-0000-0000-000000000002', 'memjoiner@pgtap.test',  now()),
    ('50000000-0000-0000-0000-000000000003', 'memasker@pgtap.test',   now()),
    ('50000000-0000-0000-0000-000000000004', 'staff@memcorp.test',    now()),
    ('50000000-0000-0000-0000-000000000005', 'unconfirmed@memcorp.test', null);

select public.create_organisation_internal(
    p_slug=>'pgtopen', p_name=>'Open Org',
    p_owner_id=>'50000000-0000-0000-0000-000000000001',
    p_visibility=>'public', p_join_policy=>'open');
select public.create_organisation_internal(
    p_slug=>'pgtreq', p_name=>'Request Org',
    p_owner_id=>'50000000-0000-0000-0000-000000000001',
    p_visibility=>'public', p_join_policy=>'request_to_join');
select public.create_organisation_internal(
    p_slug=>'pgtinvo', p_name=>'Invite Org',
    p_owner_id=>'50000000-0000-0000-0000-000000000001',
    p_visibility=>'private', p_join_policy=>'invite_only');

select set_config('request.jwt.claims', '{"role":"service_role"}', true);


-- ===========================================================================
-- organisation_available_action
-- ===========================================================================
select is(
    public.organisation_available_action('50000000-0000-0000-0000-000000000002',
        (select id from public.organisations where slug='pgtopen')),
    'join', 'an open organisation offers join'
);
select is(
    public.organisation_available_action('50000000-0000-0000-0000-000000000003',
        (select id from public.organisations where slug='pgtreq')),
    'request', 'a request_to_join organisation offers request'
);
select is(
    public.organisation_available_action('50000000-0000-0000-0000-000000000003',
        (select id from public.organisations where slug='pgtinvo')),
    'none', 'an invite-only organisation offers nothing'
);
select is(
    public.organisation_available_action('50000000-0000-0000-0000-000000000001',
        (select id from public.organisations where slug='pgtopen')),
    'member', 'the owner is already a member'
);
select is(
    public.organisation_available_action(null,
        (select id from public.organisations where slug='pgtopen')),
    'none', 'a null user is offered nothing rather than erroring'
);
select is(
    public.organisation_available_action('50000000-0000-0000-0000-000000000002',
        '00000000-0000-0000-0000-0000000000ff'),
    'none', 'a missing organisation is offered nothing'
);


-- ===========================================================================
-- join_organisation
-- ===========================================================================
select is(
    (select public.join_organisation(
        (select id from public.organisations where slug='pgtopen'),
        '50000000-0000-0000-0000-000000000002') ->> 'status'),
    'active', 'joining an open organisation is immediate'
);
select is(
    (select count(*)::int from public.organisation_members m
      join public.organisations o on o.id = m.org_id
     where o.slug='pgtopen' and m.user_id='50000000-0000-0000-0000-000000000002'
       and m.status='active'),
    1, 'the membership row is active'
);

-- The default member role is assigned on join, which is what makes the
-- organisation's permissions reach a new member at all.
select ok(
    exists (
        select 1 from public.user_roles ur
        join public.organisation_roles orl on orl.role_id = ur.role_id
        join public.organisations o on o.id = orl.org_id
        where o.slug='pgtopen' and ur.user_id='50000000-0000-0000-0000-000000000002'
          and orl.kind='user'
    ),
    'joining assigns the organisation''s default member role'
);

select is(
    (select public.join_organisation(
        (select id from public.organisations where slug='pgtopen'),
        '50000000-0000-0000-0000-000000000002') ->> 'already_member'),
    'true', 'joining twice is idempotent, not an error'
);

select is(
    (select public.join_organisation(
        (select id from public.organisations where slug='pgtreq'),
        '50000000-0000-0000-0000-000000000003') ->> 'error'),
    'This organisation requires approval -- use request_organisation_membership',
    'join is refused where approval is required, and says which call to make'
);
select is(
    (select public.join_organisation(
        (select id from public.organisations where slug='pgtinvo'),
        '50000000-0000-0000-0000-000000000003') ->> 'error'),
    'This organisation is invite-only',
    'join is refused for an invite-only organisation'
);
select is(
    (select public.join_organisation(
        (select id from public.organisations where slug='pgtopen'), null) ->> 'error'),
    'Not authenticated',
    'service_role with no named actor cannot join anybody'
);


-- ===========================================================================
-- request_organisation_membership
-- ===========================================================================
select is(
    (select public.request_organisation_membership(
        (select id from public.organisations where slug='pgtreq'),
        'please', '50000000-0000-0000-0000-000000000003') ->> 'status'),
    'pending', 'requesting membership leaves the applicant pending'
);

-- The load-bearing one: a pending applicant must hold NO organisation role.
select is(
    (select count(*)::int from public.user_roles ur
      join public.organisation_roles orl on orl.role_id = ur.role_id
      join public.organisations o on o.id = orl.org_id
     where o.slug='pgtreq' and ur.user_id='50000000-0000-0000-0000-000000000003'),
    0,
    'a PENDING applicant holds no organisation role -- approval is what grants it'
);

select is(
    (select public.request_organisation_membership(
        (select id from public.organisations where slug='pgtreq'),
        null, '50000000-0000-0000-0000-000000000003') ->> 'status'),
    'pending', 'requesting twice is idempotent'
);
select is(
    (select public.request_organisation_membership(
        (select id from public.organisations where slug='pgtopen'),
        null, '50000000-0000-0000-0000-000000000004') ->> 'error'),
    'This organisation can be joined directly -- use join_organisation',
    'requesting is refused where joining is open, and says which call to make'
);
select is(
    (select public.request_organisation_membership(
        (select id from public.organisations where slug='pgtinvo'),
        null, '50000000-0000-0000-0000-000000000003') ->> 'error'),
    'This organisation is invite-only',
    'requesting is refused for an invite-only organisation'
);


-- ===========================================================================
-- Approval and removal
-- ===========================================================================
select is(
    (select public.approve_organisation_member(
        (select id from public.organisations where slug='pgtreq'),
        '50000000-0000-0000-0000-000000000003',
        '50000000-0000-0000-0000-000000000002') ->> 'error'),
    'Permission denied',
    'a non-admin cannot approve a pending member'
);
select ok(
    (select public.approve_organisation_member(
        (select id from public.organisations where slug='pgtreq'),
        '50000000-0000-0000-0000-000000000003',
        '50000000-0000-0000-0000-000000000001') ->> 'success')::boolean,
    'an organisation admin can approve'
);
select ok(
    exists (
        select 1 from public.user_roles ur
        join public.organisation_roles orl on orl.role_id = ur.role_id
        join public.organisations o on o.id = orl.org_id
        where o.slug='pgtreq' and ur.user_id='50000000-0000-0000-0000-000000000003'
          and orl.kind='user'
    ),
    'approval is what assigns the default member role'
);
select is(
    public.organisation_available_action('50000000-0000-0000-0000-000000000003',
        (select id from public.organisations where slug='pgtreq')),
    'member', 'an approved applicant reads as a member'
);

select ok(
    (select public.remove_organisation_member(
        (select id from public.organisations where slug='pgtreq'),
        '50000000-0000-0000-0000-000000000003',
        '50000000-0000-0000-0000-000000000001') ->> 'success')::boolean,
    'an admin can remove a member'
);
select is(
    (select count(*)::int from public.user_roles ur
      join public.organisation_roles orl on orl.role_id = ur.role_id
      join public.organisations o on o.id = orl.org_id
     where o.slug='pgtreq' and ur.user_id='50000000-0000-0000-0000-000000000003'),
    0,
    'removal revokes the organisation roles too -- a stale role would outlive the membership'
);

-- The owner is the one member who cannot be removed: organisations.owner_id is
-- ON DELETE RESTRICT precisely so an organisation cannot be orphaned.
select isnt(
    (select public.remove_organisation_member(
        (select id from public.organisations where slug='pgtreq'),
        '50000000-0000-0000-0000-000000000001',
        '50000000-0000-0000-0000-000000000001') ->> 'error'),
    null,
    'the owner cannot be removed'
);


-- ===========================================================================
-- Domain-based entry
-- ===========================================================================
-- Added and verified through the real RPCs rather than a raw insert: that path
-- generates the verification_token the table requires, and exercises the admin
-- gate on both calls.
select public.add_organisation_domain(
    (select id from public.organisations where slug='pgtreq'),
    'memcorp.test', false, '50000000-0000-0000-0000-000000000001');
select public.mark_organisation_domain_verified(
    (select d.id from public.organisation_domains d
       join public.organisations o on o.id = d.org_id
      where o.slug='pgtreq' and d.domain='memcorp.test'),
    '50000000-0000-0000-0000-000000000001');

select is(
    public.organisation_available_action('50000000-0000-0000-0000-000000000004',
        (select id from public.organisations where slug='pgtreq')),
    'join',
    'a verified-domain address skips the approval queue on a request_to_join organisation'
);

-- An unconfirmed address must NOT: otherwise signing up as anyone@theirdomain
-- and never confirming would walk straight into their organisation.
select is(
    public.organisation_available_action('50000000-0000-0000-0000-000000000005',
        (select id from public.organisations where slug='pgtreq')),
    'request',
    'an UNCONFIRMED address at the same domain still has to ask'
);

select * from finish();
rollback;
