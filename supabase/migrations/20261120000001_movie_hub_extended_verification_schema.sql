-- ====================================================================
-- MOVIE HUB: PRODUCTION-HARDENED SUPABASE DATABASE MIGRATION
-- Migration: 20261120000001_movie_hub_extended_verification_schema.sql
-- Security Hardened Edition (One-Shot Production Final)
--
-- Core Security & Production Principles:
-- 1. Strictly-Scoped Permissions: REVOKE/GRANT operations target ONLY Movie Hub tables.
-- 2. Stream URL & Credential Protection: Column-level grants hide sensitive headers and URLs.
-- 3. Scrape-Resistant Playback Resolution: Validated SECURITY DEFINER procedure.
-- 4. Cryptographic Cache Ownership: Zero spoofable headers, strict creator boundary.
-- 5. Blind Telemetry Ingestion: Impersonation-proof INSERT, private SELECT, operator RPC.
-- 6. Zero Data Loss Upgrade Engine: Safe idempotent ALTER TABLE column checks.
-- 7. Controlled Search Path: pg_catalog, public enforced on all functions.
-- ====================================================================

-- --------------------------------------------------------------------
-- 0. EXTENSIONS & PREREQUISITES
-- --------------------------------------------------------------------
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- --------------------------------------------------------------------
-- 1. TABLE DEFINITIONS & SAFE COLUMN UPGRADE ENGINE (Zero Data Loss)
-- --------------------------------------------------------------------

-- 1.1 APP_SETTINGS (Remote Policy Flags & Feature Toggles)
CREATE TABLE IF NOT EXISTS public.app_settings (
    key TEXT PRIMARY KEY,
    value JSONB NOT NULL,
    category TEXT DEFAULT 'general',
    description TEXT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT timezone('utc'::text, now())
);
ALTER TABLE public.app_settings ADD COLUMN IF NOT EXISTS category TEXT DEFAULT 'general';
ALTER TABLE public.app_settings ADD COLUMN IF NOT EXISTS description TEXT;
ALTER TABLE public.app_settings ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT timezone('utc'::text, now());

-- 1.2 PROVIDERS (Streaming Provider Directory)
CREATE TABLE IF NOT EXISTS public.providers (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    provider_type TEXT NOT NULL DEFAULT 'addon',
    is_enabled BOOLEAN NOT NULL DEFAULT true,
    priority INT NOT NULL DEFAULT 50,
    base_url TEXT,
    requires_auth BOOLEAN NOT NULL DEFAULT false,
    auth_header_name TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT timezone('utc'::text, now()),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT timezone('utc'::text, now())
);
ALTER TABLE public.providers ADD COLUMN IF NOT EXISTS provider_type TEXT NOT NULL DEFAULT 'addon';
ALTER TABLE public.providers ADD COLUMN IF NOT EXISTS is_enabled BOOLEAN NOT NULL DEFAULT true;
ALTER TABLE public.providers ADD COLUMN IF NOT EXISTS priority INT NOT NULL DEFAULT 50;
ALTER TABLE public.providers ADD COLUMN IF NOT EXISTS base_url TEXT;
ALTER TABLE public.providers ADD COLUMN IF NOT EXISTS requires_auth BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE public.providers ADD COLUMN IF NOT EXISTS auth_header_name TEXT;
ALTER TABLE public.providers ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT timezone('utc'::text, now());

-- 1.3 PROVIDER_ENDPOINTS (Provider Endpoint Route Registry)
CREATE TABLE IF NOT EXISTS public.provider_endpoints (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    provider_id TEXT NOT NULL,
    endpoint_type TEXT NOT NULL,
    path_template TEXT NOT NULL,
    timeout_ms INT NOT NULL DEFAULT 10000,
    is_active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT timezone('utc'::text, now())
);
ALTER TABLE public.provider_endpoints ADD COLUMN IF NOT EXISTS timeout_ms INT NOT NULL DEFAULT 10000;
ALTER TABLE public.provider_endpoints ADD COLUMN IF NOT EXISTS is_active BOOLEAN NOT NULL DEFAULT true;
ALTER TABLE public.provider_endpoints ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT timezone('utc'::text, now());

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.table_constraints
        WHERE constraint_name = 'fk_provider_endpoints_provider'
          AND table_name = 'provider_endpoints'
    ) THEN
        ALTER TABLE public.provider_endpoints
            ADD CONSTRAINT fk_provider_endpoints_provider
            FOREIGN KEY (provider_id) REFERENCES public.providers(id) ON DELETE CASCADE;
    END IF;
END $$;

-- 1.4 MOVIES (Canonical Movie Metadata)
CREATE TABLE IF NOT EXISTS public.movies (
    id TEXT PRIMARY KEY,
    title TEXT NOT NULL,
    tmdb_id INT,
    imdb_id TEXT,
    release_year INT,
    original_language TEXT NOT NULL DEFAULT 'en',
    has_hindi BOOLEAN NOT NULL DEFAULT false,
    poster_path TEXT,
    overview TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT timezone('utc'::text, now())
);
ALTER TABLE public.movies ADD COLUMN IF NOT EXISTS tmdb_id INT;
ALTER TABLE public.movies ADD COLUMN IF NOT EXISTS imdb_id TEXT;
ALTER TABLE public.movies ADD COLUMN IF NOT EXISTS release_year INT;
ALTER TABLE public.movies ADD COLUMN IF NOT EXISTS original_language TEXT NOT NULL DEFAULT 'en';
ALTER TABLE public.movies ADD COLUMN IF NOT EXISTS has_hindi BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE public.movies ADD COLUMN IF NOT EXISTS poster_path TEXT;
ALTER TABLE public.movies ADD COLUMN IF NOT EXISTS overview TEXT;
CREATE INDEX IF NOT EXISTS idx_movies_imdb ON public.movies(imdb_id);

-- 1.5 SERIES (Canonical TV Series Metadata)
CREATE TABLE IF NOT EXISTS public.series (
    id TEXT PRIMARY KEY,
    title TEXT NOT NULL,
    tmdb_id INT,
    imdb_id TEXT,
    first_air_date DATE,
    original_language TEXT NOT NULL DEFAULT 'en',
    has_hindi BOOLEAN NOT NULL DEFAULT false,
    poster_path TEXT,
    overview TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT timezone('utc'::text, now())
);
ALTER TABLE public.series ADD COLUMN IF NOT EXISTS tmdb_id INT;
ALTER TABLE public.series ADD COLUMN IF NOT EXISTS imdb_id TEXT;
ALTER TABLE public.series ADD COLUMN IF NOT EXISTS first_air_date DATE;
ALTER TABLE public.series ADD COLUMN IF NOT EXISTS original_language TEXT NOT NULL DEFAULT 'en';
ALTER TABLE public.series ADD COLUMN IF NOT EXISTS has_hindi BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE public.series ADD COLUMN IF NOT EXISTS poster_path TEXT;
ALTER TABLE public.series ADD COLUMN IF NOT EXISTS overview TEXT;
CREATE INDEX IF NOT EXISTS idx_series_imdb ON public.series(imdb_id);

-- 1.6 EPISODES (TV Episode Metadata)
CREATE TABLE IF NOT EXISTS public.episodes (
    id TEXT PRIMARY KEY,
    series_id TEXT NOT NULL,
    season_number INT NOT NULL,
    episode_number INT NOT NULL,
    title TEXT,
    air_date DATE,
    overview TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT timezone('utc'::text, now()),
    UNIQUE (series_id, season_number, episode_number)
);
ALTER TABLE public.episodes ADD COLUMN IF NOT EXISTS air_date DATE;
ALTER TABLE public.episodes ADD COLUMN IF NOT EXISTS overview TEXT;
ALTER TABLE public.episodes ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT timezone('utc'::text, now());

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.table_constraints
        WHERE constraint_name = 'fk_episodes_series'
          AND table_name = 'episodes'
    ) THEN
        ALTER TABLE public.episodes
            ADD CONSTRAINT fk_episodes_series
            FOREIGN KEY (series_id) REFERENCES public.series(id) ON DELETE CASCADE;
    END IF;
END $$;

-- 1.7 SOURCES (Stream Source Registry)
CREATE TABLE IF NOT EXISTS public.sources (
    id TEXT PRIMARY KEY,
    provider_id TEXT NOT NULL,
    media_id TEXT NOT NULL,
    season INT,
    episode INT,
    title TEXT,
    url TEXT NOT NULL,
    format TEXT NOT NULL DEFAULT 'hls',
    quality TEXT NOT NULL DEFAULT '1080p',
    audio_languages TEXT[] NOT NULL DEFAULT '{}',
    has_hindi BOOLEAN NOT NULL DEFAULT false,
    is_hindi_dubbed BOOLEAN NOT NULL DEFAULT false,
    is_multi_audio BOOLEAN NOT NULL DEFAULT false,
    verified BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL DEFAULT timezone('utc'::text, now())
);
ALTER TABLE public.sources ADD COLUMN IF NOT EXISTS format TEXT NOT NULL DEFAULT 'hls';
ALTER TABLE public.sources ADD COLUMN IF NOT EXISTS quality TEXT NOT NULL DEFAULT '1080p';
ALTER TABLE public.sources ADD COLUMN IF NOT EXISTS audio_languages TEXT[] NOT NULL DEFAULT '{}';
ALTER TABLE public.sources ADD COLUMN IF NOT EXISTS has_hindi BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE public.sources ADD COLUMN IF NOT EXISTS is_hindi_dubbed BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE public.sources ADD COLUMN IF NOT EXISTS is_multi_audio BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE public.sources ADD COLUMN IF NOT EXISTS verified BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE public.sources ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT timezone('utc'::text, now());

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.table_constraints
        WHERE constraint_name = 'fk_sources_provider'
          AND table_name = 'sources'
    ) THEN
        ALTER TABLE public.sources
            ADD CONSTRAINT fk_sources_provider
            FOREIGN KEY (provider_id) REFERENCES public.providers(id) ON DELETE CASCADE;
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_sources_media ON public.sources(media_id, season, episode);
CREATE INDEX IF NOT EXISTS idx_sources_hindi ON public.sources(has_hindi);
CREATE INDEX IF NOT EXISTS idx_sources_verified ON public.sources(verified);

-- 1.8 SOURCE_CHECKS (Source Health Checks & Error Reports)
CREATE TABLE IF NOT EXISTS public.source_checks (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    source_id TEXT,
    provider_id TEXT NOT NULL,
    media_id TEXT,
    status_code INT,
    latency_ms INT,
    is_working BOOLEAN NOT NULL DEFAULT false,
    error_code TEXT,
    error_message TEXT,
    retry_count INT DEFAULT 0,
    checked_at TIMESTAMPTZ NOT NULL DEFAULT timezone('utc'::text, now())
);
ALTER TABLE public.source_checks ADD COLUMN IF NOT EXISTS media_id TEXT;
ALTER TABLE public.source_checks ADD COLUMN IF NOT EXISTS status_code INT;
ALTER TABLE public.source_checks ADD COLUMN IF NOT EXISTS latency_ms INT;
ALTER TABLE public.source_checks ADD COLUMN IF NOT EXISTS is_working BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE public.source_checks ADD COLUMN IF NOT EXISTS error_code TEXT;
ALTER TABLE public.source_checks ADD COLUMN IF NOT EXISTS error_message TEXT;
ALTER TABLE public.source_checks ADD COLUMN IF NOT EXISTS retry_count INT DEFAULT 0;
ALTER TABLE public.source_checks ADD COLUMN IF NOT EXISTS checked_at TIMESTAMPTZ NOT NULL DEFAULT timezone('utc'::text, now());

-- 1.9 PROVIDER_METRICS (Provider Request Volume & Reliability Metrics)
CREATE TABLE IF NOT EXISTS public.provider_metrics (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    provider_id TEXT NOT NULL,
    total_requests INT NOT NULL DEFAULT 0,
    successful_requests INT NOT NULL DEFAULT 0,
    failed_requests INT NOT NULL DEFAULT 0,
    avg_latency_ms INT NOT NULL DEFAULT 0,
    consecutive_failures INT NOT NULL DEFAULT 0,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT timezone('utc'::text, now())
);
ALTER TABLE public.provider_metrics ADD COLUMN IF NOT EXISTS total_requests INT NOT NULL DEFAULT 0;
ALTER TABLE public.provider_metrics ADD COLUMN IF NOT EXISTS successful_requests INT NOT NULL DEFAULT 0;
ALTER TABLE public.provider_metrics ADD COLUMN IF NOT EXISTS failed_requests INT NOT NULL DEFAULT 0;
ALTER TABLE public.provider_metrics ADD COLUMN IF NOT EXISTS avg_latency_ms INT NOT NULL DEFAULT 0;
ALTER TABLE public.provider_metrics ADD COLUMN IF NOT EXISTS consecutive_failures INT NOT NULL DEFAULT 0;
ALTER TABLE public.provider_metrics ADD COLUMN IF NOT EXISTS recorded_at TIMESTAMPTZ NOT NULL DEFAULT timezone('utc'::text, now());

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.table_constraints
        WHERE constraint_name = 'fk_provider_metrics_provider'
          AND table_name = 'provider_metrics'
    ) THEN
        ALTER TABLE public.provider_metrics
            ADD CONSTRAINT fk_provider_metrics_provider
            FOREIGN KEY (provider_id) REFERENCES public.providers(id) ON DELETE CASCADE;
    END IF;
END $$;

-- 1.10 PLAYBACK_DIAGNOSTICS (Diagnostic Telemetry & Playback Sessions)
CREATE TABLE IF NOT EXISTS public.playback_diagnostics (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id TEXT NOT NULL,
    user_id UUID,
    media_id TEXT NOT NULL,
    season INT,
    episode INT,
    provider_id TEXT NOT NULL,
    stream_url TEXT,
    startup_time_ms INT,
    buffer_events INT NOT NULL DEFAULT 0,
    total_seek_count INT NOT NULL DEFAULT 0,
    playback_position_ms BIGINT DEFAULT 0,
    duration_ms BIGINT DEFAULT 0,
    is_locked BOOLEAN DEFAULT true,
    error_code TEXT,
    network_type TEXT DEFAULT 'cellular_4g',
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT timezone('utc'::text, now())
);
ALTER TABLE public.playback_diagnostics ADD COLUMN IF NOT EXISTS user_id UUID;
ALTER TABLE public.playback_diagnostics ADD COLUMN IF NOT EXISTS season INT;
ALTER TABLE public.playback_diagnostics ADD COLUMN IF NOT EXISTS episode INT;
ALTER TABLE public.playback_diagnostics ADD COLUMN IF NOT EXISTS stream_url TEXT;
ALTER TABLE public.playback_diagnostics ADD COLUMN IF NOT EXISTS startup_time_ms INT;
ALTER TABLE public.playback_diagnostics ADD COLUMN IF NOT EXISTS buffer_events INT NOT NULL DEFAULT 0;
ALTER TABLE public.playback_diagnostics ADD COLUMN IF NOT EXISTS total_seek_count INT NOT NULL DEFAULT 0;
ALTER TABLE public.playback_diagnostics ADD COLUMN IF NOT EXISTS playback_position_ms BIGINT DEFAULT 0;
ALTER TABLE public.playback_diagnostics ADD COLUMN IF NOT EXISTS duration_ms BIGINT DEFAULT 0;
ALTER TABLE public.playback_diagnostics ADD COLUMN IF NOT EXISTS is_locked BOOLEAN DEFAULT true;
ALTER TABLE public.playback_diagnostics ADD COLUMN IF NOT EXISTS network_type TEXT DEFAULT 'cellular_4g';
ALTER TABLE public.playback_diagnostics ADD COLUMN IF NOT EXISTS recorded_at TIMESTAMPTZ NOT NULL DEFAULT timezone('utc'::text, now());
CREATE INDEX IF NOT EXISTS idx_playback_diag_user ON public.playback_diagnostics(user_id);
CREATE INDEX IF NOT EXISTS idx_playback_diag_session ON public.playback_diagnostics(session_id);

-- 1.11 CACHE_METADATA (Cloud Stream & Configuration Cache)
CREATE TABLE IF NOT EXISTS public.cache_metadata (
    cache_key TEXT PRIMARY KEY,
    category TEXT NOT NULL,
    created_by UUID,
    is_public BOOLEAN NOT NULL DEFAULT true,
    size_bytes INT NOT NULL DEFAULT 0,
    item_count INT NOT NULL DEFAULT 1,
    ttl_seconds INT NOT NULL DEFAULT 900,
    expires_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT timezone('utc'::text, now())
);
ALTER TABLE public.cache_metadata ADD COLUMN IF NOT EXISTS created_by UUID;
ALTER TABLE public.cache_metadata ADD COLUMN IF NOT EXISTS is_public BOOLEAN NOT NULL DEFAULT true;
ALTER TABLE public.cache_metadata ADD COLUMN IF NOT EXISTS size_bytes INT NOT NULL DEFAULT 0;
ALTER TABLE public.cache_metadata ADD COLUMN IF NOT EXISTS item_count INT NOT NULL DEFAULT 1;
ALTER TABLE public.cache_metadata ADD COLUMN IF NOT EXISTS ttl_seconds INT NOT NULL DEFAULT 900;
ALTER TABLE public.cache_metadata ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT timezone('utc'::text, now());
CREATE INDEX IF NOT EXISTS idx_cache_expires ON public.cache_metadata(expires_at);

-- --------------------------------------------------------------------
-- 2. SEED DATA (Streaming Policy Clamping & Provider Directory)
-- --------------------------------------------------------------------

INSERT INTO public.app_settings (key, value, category, description)
VALUES
    ('streaming_policy', jsonb_build_object(
        'max_resolution', '1080p',
        'allow_4k', false,
        'hindi_first_priority', true,
        'fallback_to_original_language', true,
        'seeking_immunity_window_ms', 45000,
        'request_timeout_seconds', 12,
        'max_provider_retries', 2
    ), 'streaming', 'Global streaming policy clamping to 1080p and prioritizing Hindi audio'),
    ('provider_directory', jsonb_build_object(
        'torrentio', jsonb_build_object('enabled', true, 'priority', 100, 'auth_required', false),
        'mediafusion', jsonb_build_object('enabled', true, 'priority', 90, 'auth_required', false),
        'plaviohd', jsonb_build_object('enabled', true, 'priority', 85, 'auth_required', false),
        'jellyfin', jsonb_build_object('enabled', true, 'priority', 80, 'auth_required', true),
        'custom_streams', jsonb_build_object('enabled', true, 'priority', 75, 'auth_required', false),
        'castle_tv', jsonb_build_object('enabled', false, 'priority', 0, 'auth_required', true, 'status', 'restricted'),
        'hdhub', jsonb_build_object('enabled', false, 'priority', 0, 'auth_required', false, 'status', 'unsupported'),
        'netmirror', jsonb_build_object('enabled', false, 'priority', 0, 'auth_required', true, 'status', 'unsupported')
    ), 'providers', 'Provider enablement and priority registry')
ON CONFLICT (key) DO UPDATE
    SET value = EXCLUDED.value, updated_at = now();

INSERT INTO public.providers (id, name, provider_type, is_enabled, priority, base_url, requires_auth)
VALUES
    ('torrentio', 'Torrentio', 'addon', true, 100, 'https://torrentio.strem.fun', false),
    ('mediafusion', 'Mediafusion', 'addon', true, 90, 'https://mediafusion.elfhosted.com', false),
    ('plaviohd', 'PlavioHD', 'direct', true, 85, 'https://plaviohd.net', false),
    ('jellyfin', 'Jellyfin User Node', 'self_hosted', true, 80, NULL, true),
    ('custom_streams', 'Custom Streams', 'direct', true, 75, NULL, false),
    ('castle_tv', 'Castle TV (Restricted)', 'direct', false, 0, 'https://api.hlowb.com', true),
    ('hdhub', 'HDHub (Unsupported Scraping)', 'scraper', false, 0, NULL, false),
    ('netmirror', 'NetMirror (Unsupported Proxy)', 'proxy', false, 0, NULL, true)
ON CONFLICT (id) DO UPDATE
    SET is_enabled = EXCLUDED.is_enabled, priority = EXCLUDED.priority, updated_at = now();

-- --------------------------------------------------------------------
-- 3. DEFENSE-IN-DEPTH: STRICTLY-SCOPED GRANTS & REVOKES
-- Scoped STRICTLY to the 11 Movie Hub tables (Never modifies unrelated tables)
-- --------------------------------------------------------------------

-- 3.1 Strip all privileges on Movie Hub tables from public/anon/authenticated
REVOKE ALL ON
    public.app_settings,
    public.providers,
    public.provider_endpoints,
    public.movies,
    public.series,
    public.episodes,
    public.sources,
    public.source_checks,
    public.provider_metrics,
    public.playback_diagnostics,
    public.cache_metadata
FROM PUBLIC, anon, authenticated;

-- 3.2 Ensure service_role has administrative permissions on Movie Hub tables
GRANT USAGE ON SCHEMA public TO anon, authenticated, service_role;
GRANT ALL ON
    public.app_settings,
    public.providers,
    public.provider_endpoints,
    public.movies,
    public.series,
    public.episodes,
    public.sources,
    public.source_checks,
    public.provider_metrics,
    public.playback_diagnostics,
    public.cache_metadata
TO service_role;

-- 3.3 Explicit Grants for Catalog & Public Application Settings
GRANT SELECT ON public.app_settings TO anon, authenticated;
GRANT SELECT ON public.provider_endpoints TO anon, authenticated;
GRANT SELECT ON public.movies TO anon, authenticated;
GRANT SELECT ON public.series TO anon, authenticated;
GRANT SELECT ON public.episodes TO anon, authenticated;
GRANT SELECT ON public.cache_metadata TO anon, authenticated;

-- Column-Level Protection: Hide auth_header_name from public inspection
GRANT SELECT (id, name, provider_type, is_enabled, priority, base_url, requires_auth, created_at, updated_at)
    ON public.providers TO anon, authenticated;

-- Column-Level Protection: Exclude raw stream URL from direct anonymous SELECT
GRANT SELECT (id, provider_id, media_id, season, episode, title, format, quality, audio_languages, has_hindi, is_hindi_dubbed, is_multi_audio, verified, created_at)
    ON public.sources TO anon;
GRANT SELECT ON public.sources TO authenticated;

-- 3.4 Telemetry INSERT Grants (Client Blind Ingestion)
GRANT INSERT ON public.source_checks TO anon, authenticated;
GRANT INSERT ON public.provider_metrics TO anon, authenticated;
GRANT INSERT ON public.playback_diagnostics TO anon, authenticated;
GRANT INSERT, UPDATE ON public.cache_metadata TO anon, authenticated;

-- Authenticated Users Telemetry Read (Protected by RLS: Own records only)
GRANT SELECT ON public.playback_diagnostics TO authenticated;

-- --------------------------------------------------------------------
-- 4. DYNAMIC POLICY CLEANUP (Movie Hub Tables Only)
-- --------------------------------------------------------------------

DO $$
DECLARE
    r RECORD;
BEGIN
    FOR r IN (
        SELECT policyname, tablename
        FROM pg_policies
        WHERE schemaname = 'public'
          AND tablename IN (
              'app_settings', 'providers', 'provider_endpoints',
              'movies', 'series', 'episodes', 'sources',
              'source_checks', 'provider_metrics',
              'playback_diagnostics', 'cache_metadata'
          )
    ) LOOP
        EXECUTE format('DROP POLICY IF EXISTS %I ON public.%I', r.policyname, r.tablename);
    END LOOP;
END $$;

-- --------------------------------------------------------------------
-- 5. ROW LEVEL SECURITY (RLS) POLICIES
-- --------------------------------------------------------------------

-- 5.1 Enable RLS on all 11 Movie Hub tables
ALTER TABLE public.app_settings ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.providers ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.provider_endpoints ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.movies ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.series ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.episodes ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.sources ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.source_checks ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.provider_metrics ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.playback_diagnostics ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.cache_metadata ENABLE ROW LEVEL SECURITY;

-- 5.2 Settings & Provider Policies
CREATE POLICY "Public read app_settings"
    ON public.app_settings FOR SELECT
    TO anon, authenticated
    USING (true);

-- Anonymous users only see enabled providers; authenticated users see full catalog
CREATE POLICY "Public read active providers"
    ON public.providers FOR SELECT
    TO anon, authenticated
    USING (is_enabled = true OR (auth.jwt()->>'role' = 'service_role' OR auth.jwt()->'app_metadata'->>'is_admin' = 'true'));

CREATE POLICY "Public read active provider_endpoints"
    ON public.provider_endpoints FOR SELECT
    TO anon, authenticated
    USING (is_active = true);

-- 5.3 Catalog Read Policies
CREATE POLICY "Public read movies"
    ON public.movies FOR SELECT
    TO anon, authenticated
    USING (true);

CREATE POLICY "Public read series"
    ON public.series FOR SELECT
    TO anon, authenticated
    USING (true);

CREATE POLICY "Public read episodes"
    ON public.episodes FOR SELECT
    TO anon, authenticated
    USING (true);

-- 5.4 Sources RLS: Only verified sources queryable by specific media_id
CREATE POLICY "Client read verified sources"
    ON public.sources FOR SELECT
    TO anon, authenticated
    USING (verified = true AND (media_id IS NOT NULL));

-- 5.5 Source Checks: Blind INSERT only; SELECT restricted to verified operators
CREATE POLICY "Client blind insert source_checks"
    ON public.source_checks FOR INSERT
    TO anon, authenticated
    WITH CHECK (provider_id IS NOT NULL);

CREATE POLICY "Operator read source_checks"
    ON public.source_checks FOR SELECT
    TO authenticated
    USING (auth.jwt()->>'role' = 'service_role' OR auth.jwt()->'app_metadata'->>'is_admin' = 'true');

-- 5.6 Provider Metrics: Ingestion only; public raw SELECT blocked
CREATE POLICY "Client insert provider_metrics"
    ON public.provider_metrics FOR INSERT
    TO anon, authenticated
    WITH CHECK (provider_id IS NOT NULL);

CREATE POLICY "Operator read provider_metrics"
    ON public.provider_metrics FOR SELECT
    TO authenticated
    USING (auth.jwt()->>'role' = 'service_role' OR auth.jwt()->'app_metadata'->>'is_admin' = 'true');

-- 5.7 Playback Diagnostics: Anti-Impersonation & Private Telemetry
-- Enforce user_id = auth.uid() for authenticated users; anonymous callers MUST set user_id to NULL.
CREATE POLICY "Client blind insert playback_diagnostics"
    ON public.playback_diagnostics FOR INSERT
    TO anon, authenticated
    WITH CHECK (
        (auth.uid() IS NOT NULL AND user_id = auth.uid())
        OR
        (auth.uid() IS NULL AND user_id IS NULL)
    );

-- Users can ONLY query their OWN playback diagnostic sessions
CREATE POLICY "Users read own playback_diagnostics"
    ON public.playback_diagnostics FOR SELECT
    TO authenticated
    USING (user_id IS NOT NULL AND user_id = auth.uid());

-- 5.8 Cache Metadata: Creator-Locked Ownership & Anonymous Warm Read
CREATE POLICY "Read valid cache_metadata"
    ON public.cache_metadata FOR SELECT
    TO anon, authenticated
    USING (
        expires_at > timezone('utc'::text, now())
        AND (is_public = true OR (created_by IS NOT NULL AND created_by = auth.uid()))
    );

CREATE POLICY "Client insert cache_metadata"
    ON public.cache_metadata FOR INSERT
    TO anon, authenticated
    WITH CHECK (
        expires_at > timezone('utc'::text, now())
        AND expires_at <= timezone('utc'::text, now()) + interval '7 days'
        AND (
            (auth.uid() IS NOT NULL AND created_by = auth.uid())
            OR
            (auth.uid() IS NULL AND created_by IS NULL)
        )
    );

-- Only the original creator can update an active cache entry
CREATE POLICY "Owner update cache_metadata"
    ON public.cache_metadata FOR UPDATE
    TO authenticated
    USING (
        created_by IS NOT NULL
        AND created_by = auth.uid()
        AND expires_at > timezone('utc'::text, now())
    )
    WITH CHECK (
        created_by IS NOT NULL
        AND created_by = auth.uid()
        AND expires_at > timezone('utc'::text, now())
    );

-- --------------------------------------------------------------------
-- 6. SECURITY DEFINER STORED PROCEDURES (Hardened Search Path & Validation)
-- --------------------------------------------------------------------

-- 6.1 Secure Cache Refresh Procedure (Parameter Limits & Creator Guard)
CREATE OR REPLACE FUNCTION public.refresh_cache_entry(
    p_cache_key TEXT,
    p_category TEXT,
    p_is_public BOOLEAN DEFAULT true,
    p_size_bytes INT DEFAULT 0,
    p_item_count INT DEFAULT 1,
    p_ttl_seconds INT DEFAULT 900
)
RETURNS BOOLEAN
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_clamped_ttl INT;
    v_caller_id UUID;
BEGIN
    -- Parameter validation
    IF p_cache_key IS NULL OR length(trim(p_cache_key)) = 0 OR length(p_cache_key) > 256 THEN
        RAISE EXCEPTION 'Invalid cache_key: must be 1-256 characters';
    END IF;
    IF p_category IS NULL OR length(trim(p_category)) = 0 OR length(p_category) > 64 THEN
        RAISE EXCEPTION 'Invalid category: must be 1-64 characters';
    END IF;
    IF p_size_bytes < 0 OR p_size_bytes > 2097152 THEN -- Max 2MB cache payload
        RAISE EXCEPTION 'Payload size exceeds 2MB limit';
    END IF;
    IF p_item_count < 1 OR p_item_count > 5000 THEN
        RAISE EXCEPTION 'Item count out of range (1-5000)';
    END IF;

    -- Clamp TTL between 60 seconds and 7 days
    v_clamped_ttl := GREATEST(60, LEAST(p_ttl_seconds, 604800));
    v_caller_id := auth.uid();

    INSERT INTO public.cache_metadata (
        cache_key, category, created_by, is_public, size_bytes, item_count, ttl_seconds, expires_at, updated_at
    )
    VALUES (
        trim(p_cache_key),
        trim(p_category),
        v_caller_id,
        p_is_public,
        p_size_bytes,
        p_item_count,
        v_clamped_ttl,
        timezone('utc'::text, now()) + (v_clamped_ttl || ' seconds')::interval,
        timezone('utc'::text, now())
    )
    ON CONFLICT (cache_key) DO UPDATE
        SET size_bytes = EXCLUDED.size_bytes,
            item_count = EXCLUDED.item_count,
            ttl_seconds = EXCLUDED.ttl_seconds,
            expires_at = timezone('utc'::text, now()) + (EXCLUDED.ttl_seconds || ' seconds')::interval,
            updated_at = timezone('utc'::text, now())
        WHERE cache_metadata.expires_at <= timezone('utc'::text, now())
           OR (cache_metadata.created_by IS NOT NULL AND cache_metadata.created_by = v_caller_id);

    RETURN FOUND;
END;
$$;

-- 6.2 Secure Playback Source Resolver (Validates Source Belonging & Provider Status)
CREATE OR REPLACE FUNCTION public.resolve_playback_source(
    p_source_id TEXT,
    p_media_id TEXT
)
RETURNS TABLE (
    id TEXT,
    provider_id TEXT,
    media_id TEXT,
    url TEXT,
    format TEXT,
    quality TEXT
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
BEGIN
    -- Parameter validation
    IF p_source_id IS NULL OR length(trim(p_source_id)) = 0 THEN
        RAISE EXCEPTION 'Invalid source_id';
    END IF;
    IF p_media_id IS NULL OR length(trim(p_media_id)) = 0 THEN
        RAISE EXCEPTION 'Invalid media_id';
    END IF;

    -- Return strictly if source is verified and belongs to an enabled provider
    RETURN QUERY
    SELECT s.id, s.provider_id, s.media_id, s.url, s.format, s.quality
    FROM public.sources s
    INNER JOIN public.providers p ON s.provider_id = p.id
    WHERE s.id = trim(p_source_id)
      AND s.media_id = trim(p_media_id)
      AND s.verified = true
      AND p.is_enabled = true
    LIMIT 1;
END;
$$;

-- 6.3 Operator-Only Diagnostics Inspection Procedure
CREATE OR REPLACE FUNCTION public.get_operator_playback_diagnostics(p_limit INT DEFAULT 100)
RETURNS SETOF public.playback_diagnostics
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
BEGIN
    -- Server-side verified operator check (service_role or app_metadata.is_admin)
    IF (
        auth.jwt()->>'role' = 'service_role'
        OR auth.jwt()->'app_metadata'->>'is_admin' = 'true'
        OR current_setting('request.jwt.claim.role', true) = 'service_role'
    ) THEN
        RETURN QUERY
        SELECT * FROM public.playback_diagnostics
        ORDER BY recorded_at DESC
        LIMIT LEAST(p_limit, 500);
    ELSE
        RAISE EXCEPTION 'Access denied: operator privileges required.';
    END IF;
END;
$$;

-- 6.4 Safe Public Provider Health Summary View (Aggregate only, no raw client metrics)
CREATE OR REPLACE VIEW public.provider_status_summary AS
SELECT
    p.id AS provider_id,
    p.name AS provider_name,
    p.is_enabled,
    p.priority,
    COALESCE(SUM(m.total_requests), 0) AS total_requests,
    COALESCE(SUM(m.successful_requests), 0) AS successful_requests,
    COALESCE(SUM(m.failed_requests), 0) AS failed_requests,
    COALESCE(ROUND(AVG(m.avg_latency_ms)), 0) AS avg_latency_ms
FROM public.providers p
LEFT JOIN public.provider_metrics m ON p.id = m.provider_id
WHERE p.is_enabled = true
GROUP BY p.id, p.name, p.is_enabled, p.priority;

-- Procedure & View Grants
REVOKE ALL ON FUNCTION public.get_operator_playback_diagnostics(INT) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.get_operator_playback_diagnostics(INT) TO authenticated;

GRANT EXECUTE ON FUNCTION public.refresh_cache_entry TO anon, authenticated;
GRANT EXECUTE ON FUNCTION public.resolve_playback_source TO anon, authenticated;
GRANT SELECT ON public.provider_status_summary TO anon, authenticated;
