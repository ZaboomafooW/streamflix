alter table public.user_media_state
    add column if not exists tmdb_id integer,
    add column if not exists imdb_id text,
    add column if not exists parent_show_tmdb_id integer,
    add column if not exists parent_show_imdb_id text;
