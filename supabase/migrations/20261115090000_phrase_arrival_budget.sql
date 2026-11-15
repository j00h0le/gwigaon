-- ADR 0021: phrase-arrival asks Claude for one spoken sentence when 설정 is saved with arrival notes.
-- The function is reachable by any signed-in (anonymous) user, so each call is counted first, like summaries:
-- 10 per owner and 300 in the whole project in any 24 h. Only the Edge Function (service role) claims.

create table private.phrase_attempts (
  id bigint generated always as identity primary key,
  owner uuid not null,
  attempted_at timestamptz not null default now()
);

-- Lives in the unexposed private schema with RLS on and no policies: clients never read or write it.
alter table private.phrase_attempts enable row level security;
revoke all on table private.phrase_attempts from public, anon, authenticated;
-- The Edge Function claims as service_role, which needs the table grants (RLS doesn't apply to it).
grant select, insert on table private.phrase_attempts to service_role;

create index phrase_attempts_owner_idx on private.phrase_attempts (owner, attempted_at);
create index phrase_attempts_attempted_idx on private.phrase_attempts (attempted_at);

-- true = the caller may call Claude now (attempt recorded); false = a budget is used up.
create or replace function public.claim_phrase_attempt(p_owner uuid)
returns boolean
language plpgsql
volatile
set search_path = ''
as $$
begin
  -- One claim at a time project-wide, so the counts are exact (claims happen once per 설정 save).
  perform pg_advisory_xact_lock(hashtextextended('claim_phrase_attempt', 0));

  if (select count(*) from private.phrase_attempts a
      where a.owner = p_owner and a.attempted_at > now() - interval '24 hours') >= 10
     or (select count(*) from private.phrase_attempts a
         where a.attempted_at > now() - interval '24 hours') >= 300 then
    return false;
  end if;

  insert into private.phrase_attempts (owner) values (p_owner);
  return true;
end;
$$;

revoke all on function public.claim_phrase_attempt(uuid) from public, anon, authenticated;
grant execute on function public.claim_phrase_attempt(uuid) to service_role;
