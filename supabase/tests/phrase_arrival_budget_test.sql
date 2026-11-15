-- ADR 0021: the phrase-arrival budget (10 per owner, 300 project-wide in 24 h), service role only.
begin;
create extension if not exists pgtap with schema extensions;

select plan(8);

-- Clients can't claim or see attempts.
select is(has_function_privilege('anon', 'public.claim_phrase_attempt(uuid)', 'execute'), false, 'anon cannot claim');
select is(has_function_privilege('authenticated', 'public.claim_phrase_attempt(uuid)', 'execute'), false, 'authenticated cannot claim');
select is(has_table_privilege('authenticated', 'private.phrase_attempts', 'select'), false, 'authenticated cannot read attempts');

delete from private.phrase_attempts;

-- 9 earlier today + 1 from two days ago for this owner: one more is allowed, then none.
insert into private.phrase_attempts (owner, attempted_at)
select '61111111-1111-1111-1111-111111111111', now() - interval '1 hour' from generate_series(1, 9);
insert into private.phrase_attempts (owner, attempted_at)
values ('61111111-1111-1111-1111-111111111111', now() - interval '2 days');

select is(public.claim_phrase_attempt('61111111-1111-1111-1111-111111111111'), true, '10th claim in 24 h is allowed');
select is(public.claim_phrase_attempt('61111111-1111-1111-1111-111111111111'), false, '11th claim in 24 h is refused');

-- Another owner is unaffected until the project-wide ceiling.
select is(public.claim_phrase_attempt('62222222-2222-2222-2222-222222222222'), true, 'another owner may claim');

insert into private.phrase_attempts (owner, attempted_at)
select gen_random_uuid(), now() - interval '10 minutes' from generate_series(1, 300);
select is(public.claim_phrase_attempt('63333333-3333-3333-3333-333333333333'), false, 'project-wide ceiling refuses');

-- The Edge Function claims as service_role: it needs the grants, not just postgres's ownership.
delete from private.phrase_attempts;
set local role service_role;
select is(public.claim_phrase_attempt('64444444-4444-4444-4444-444444444444'), true, 'service_role can claim');
reset role;

select * from finish();
rollback;
