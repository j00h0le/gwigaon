-- join_trip(token): the contact page's only way in. Adds the calling (anonymous) user
-- to trip_viewers for the trip with this share token and returns its id.
-- Unknown token -> null, nothing written. Security definer because the caller can't
-- see the trip yet; it only ever acts for auth.uid().

create function public.join_trip(token text)
returns uuid
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_uid uuid := (select auth.uid());
  v_trip_id uuid;
begin
  if v_uid is null then
    raise exception 'not authenticated' using errcode = '42501';
  end if;

  if token is null or token !~ '^[A-Za-z0-9_-]{22,}$' then
    return null;
  end if;

  select t.id into v_trip_id
  from public.trips t
  where t.share_token = token;

  if v_trip_id is null then
    return null;
  end if;

  insert into public.trip_viewers (trip_id, viewer)
  values (v_trip_id, v_uid)
  on conflict (trip_id, viewer) do nothing;

  return v_trip_id;
end;
$$;

revoke all on function public.join_trip(text) from public, anon;
grant execute on function public.join_trip(text) to authenticated;

-- Realtime: contact page subscribes to postgres_changes filtered by trip id; RLS applies.
alter publication supabase_realtime add table public.trips, public.trip_events, public.trip_reports;
