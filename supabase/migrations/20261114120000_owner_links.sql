-- One standing link per account (ADR 0019, supersedes ADR 0016).
-- The car makes the token, shows it as a QR in 설정, and registers it with set_owner_link. Whoever opens the link
-- calls join_owner_link and from then on sees this account's trips that started after the link was made, while the
-- link isn't replaced, running or up to 24 h after they end or go silent (the ADR 0011 window).
-- Clients never write these tables directly: both functions are security definer and act only for auth.uid().

-- ── Tables ─────────────────────────────────────────────────────
create table public.owner_links (
  -- >= 128-bit random, url-safe base64 (the car uses 18 random bytes -> 24 chars), same rule as trips.share_token.
  token text primary key check (token ~ '^[A-Za-z0-9_-]{22,}$'),
  owner uuid not null references auth.users (id) on delete cascade,
  created_at timestamptz not null default now(),
  revoked_at timestamptz
);

comment on table public.owner_links is 'Standing contact link of one account (ADR 0019). At most one active (revoked_at is null) per owner.';

create unique index owner_links_one_active_idx on public.owner_links (owner) where revoked_at is null;

alter table public.owner_links enable row level security;
revoke all on table public.owner_links from anon, authenticated;
grant select on table public.owner_links to authenticated;

create policy "owner selects own links" on public.owner_links
  for select to authenticated
  using ((select auth.uid()) = owner);

create table public.owner_link_viewers (
  token text not null references public.owner_links (token) on delete cascade,
  viewer uuid not null references auth.users (id) on delete cascade,
  joined_at timestamptz not null default now(),
  primary key (token, viewer)
);

comment on table public.owner_link_viewers is 'Phones that opened a standing link (joined via join_owner_link). At most 5 per link.';

create index owner_link_viewers_viewer_idx on public.owner_link_viewers (viewer);

alter table public.owner_link_viewers enable row level security;
revoke all on table public.owner_link_viewers from anon, authenticated;
grant select on table public.owner_link_viewers to authenticated;

-- The owner sees who joined (설정 shows 연결됨); a viewer sees its own membership.
create policy "viewer sees own membership, owner sees viewers of own link" on public.owner_link_viewers
  for select to authenticated
  using (
    viewer = (select auth.uid())
    or exists (select 1 from public.owner_links l where l.token = owner_link_viewers.token and l.owner = (select auth.uid()))
  );

-- ── set_owner_link: register (or replace) the caller's link ────
-- Idempotent: calling again with the caller's current token changes nothing, so the car can retry after a lost
-- answer. A new token revokes the caller's active one in the same transaction (QR 다시 만들기). created_at comes
-- from the car (the QR may be shown offline before it is uploaded), clamped to [now - 7 days, now].
-- Returns false for a token that belongs to someone else or was revoked.
create function public.set_owner_link(p_token text, p_created_at timestamptz default null)
returns boolean
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_uid uuid := (select auth.uid());
  v_row public.owner_links;
begin
  if v_uid is null then
    raise exception 'not authenticated' using errcode = '42501';
  end if;

  if p_token is null or p_token !~ '^[A-Za-z0-9_-]{22,}$' then
    return false;
  end if;

  -- One registration at a time per owner, so two retries can't both insert.
  perform pg_advisory_xact_lock(hashtextextended(v_uid::text, 1));

  select * into v_row from public.owner_links l where l.token = p_token;
  if found then
    return v_row.owner = v_uid and v_row.revoked_at is null;
  end if;

  update public.owner_links set revoked_at = now() where owner = v_uid and revoked_at is null;

  insert into public.owner_links (token, owner, created_at)
  values (p_token, v_uid, greatest(least(coalesce(p_created_at, now()), now()), now() - interval '7 days'));
  return true;
end;
$$;

revoke all on function public.set_owner_link(text, timestamptz) from public, anon;
grant execute on function public.set_owner_link(text, timestamptz) to authenticated;

-- ── join_owner_link: the contact page's way in with #c= ────────
-- Returns the link's owner (the page then follows that owner's newest visible trip), or null for an unknown,
-- revoked or full link (5 phones). A phone that already joined always gets back in while the link is active.
create function public.join_owner_link(token text)
returns uuid
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_uid uuid := (select auth.uid());
  v_owner uuid;
begin
  if v_uid is null then
    raise exception 'not authenticated' using errcode = '42501';
  end if;

  if token is null or token !~ '^[A-Za-z0-9_-]{22,}$' then
    return null;
  end if;

  -- Serialize joins per link so the phone cap is exact.
  perform pg_advisory_xact_lock(hashtextextended(token, 2));

  select l.owner into v_owner from public.owner_links l where l.token = join_owner_link.token and l.revoked_at is null;
  if v_owner is null then
    return null;
  end if;

  if exists (select 1 from public.owner_link_viewers v where v.token = join_owner_link.token and v.viewer = v_uid) then
    return v_owner;
  end if;

  if (select count(*) from public.owner_link_viewers v where v.token = join_owner_link.token) >= 5 then
    return null;
  end if;

  insert into public.owner_link_viewers (token, viewer) values (join_owner_link.token, v_uid);
  return v_owner;
end;
$$;

revoke all on function public.join_owner_link(text) from public, anon;
grant execute on function public.join_owner_link(text) to authenticated;

-- ── Visibility: trips, events and reports ──────────────────────
-- Every select policy on trips, trip_events and trip_reports asks private.is_trip_viewer, so widening it here
-- covers all three (and Realtime, which applies the same policies). Per-trip viewers (join_trip) are unchanged.
create or replace function private.is_trip_viewer(p_trip_id uuid)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select exists (
    select 1 from public.trip_viewers v
    where v.trip_id = p_trip_id and v.viewer = (select auth.uid())
  ) or exists (
    select 1
    from public.trips t
    join public.owner_links l on l.owner = t.owner
    join public.owner_link_viewers lv on lv.token = l.token
    where t.id = p_trip_id
      and lv.viewer = (select auth.uid())
      and l.revoked_at is null
      and t.started_at >= l.created_at
      and coalesce(t.ended_at, t.last_seen_at) > now() - interval '24 hours'
  );
$$;

-- The contact page follows a new trip of the owner as it starts (filtered by owner; RLS still applies).
create index trips_owner_started_idx on public.trips (owner, started_at desc);
