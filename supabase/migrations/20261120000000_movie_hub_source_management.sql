-- ====================================================================
-- MOVIE HUB: COMPLETE SUPABASE BACKEND SCHEMA & SOURCE MANAGEMENT
-- Migration: 20261120000000_movie_hub_source_management.sql
-- ====================================================================

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- --------------------------------------------------------------------
-- 1. APP CONFIGURATION (Dynamic Remote Control without APK rebuilds)
-- --------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS public.app_config (
    key TEXT PRIMARY KEY,
    value JSONB NOT NULL,
    description TEXT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT timezone('utc'::text, now())
);

ALTER TABLE public.app_config ENABLE ROW LEVEL SECURITY;

CREATE POLICY "Allow public read-only access to app_config"
    ON public.app_config FOR SELECT
    USING (true);

-- Seed default application configuration
INSERT INTO public.app_config (key, value, description)
VALUES
    ('streaming_policy', jsonb_build_object(
        'max_resolution', '1080p',
        'allow_4k', false,
        'hindi_first_priority', true,
        'fallback_to_original_language', true,
        'seeking_immunity_window_ms', 45000,
        'request_timeout_seconds', 12,
        'max_provider_retries', 2,
        'cache_ttl_discovery_minutes', 15,
        'cache_ttl_metadata_hours', 48
    ), 'Global streaming and source priority policies'),
    ('provider_directory', jsonb_build_object(
        'torrentio', jsonb_build_object('enabled', true, 'priority', 100, 'auth_required', false),
        'mediafusion', jsonb_build_object('enabled', true, 'priority', 90, 'auth_required', false),
        'plaviohd', jsonb_build_object('enabled', true, 'priority', 85, 'auth_required', false),
        'jellyfin', jsonb_build_object('enabled', true, 'priority', 80, 'auth_required', true),
        'custom_streams', jsonb_build_object('enabled', true, 'priority', 75, 'auth_required', false),
        'castle_tv', jsonb_build_object('enabled', false, 'priority', 0, 'auth_required', true, 'status', 'restricted'),
        'hdhub', jsonb_build_object('enabled', false, 'priority', 0, 'auth_required', false, 'status', 'unsupported'),
        'netmirror', jsonb_build_object('enabled', false, 'priority', 0, 'auth_required', true, 'status', 'unsupported')
    ), 'Centralized provider enablement and priority registry')
ON CONFLICT (key) DO UPDATE
    SET value = EXCLUDED.value, updated_at = now();

-- --------------------------------------------------------------------
-- 2. MEDIA METADATA
-- --------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS public.media_metadata (
    media_id TEXT PRIMARY KEY,
    imdb_id TEXT,
    title TEXT NOT NULL,
    media_type TEXT NOT NULL DEFAULT 'movie', -- 'movie', 'tv_series', 'anime'
    year INT,
    original_language TEXT NOT NULL DEFAULT 'en',
    has_hindi_release BOOLEAN NOT NULL DEFAULT false,
    overview TEXT,
    poster_url TEXT,
    backdrop_url TEXT,
    genres TEXT[] DEFAULT '{}',
    created_at TIMESTAMPTZ NOT NULL DEFAULT timezone('utc'::text, now()),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT timezone('utc'::text, now())
);

CREATE INDEX IF NOT EXISTS idx_media_metadata_imdb ON public.media_metadata(imdb_id);
CREATE INDEX IF NOT EXISTS idx_media_metadata_orig_lang ON public.media_metadata(original_language);

ALTER TABLE public.media_metadata ENABLE ROW LEVEL SECURITY;

CREATE POLICY "Allow public read access to media_metadata"
    ON public.media_metadata FOR SELECT
    USING (true);

CREATE POLICY "Allow authenticated insert/update to media_metadata"
    ON public.media_metadata FOR ALL
    USING (auth.role() = 'authenticated' OR auth.role() = 'anon')
    WITH CHECK (auth.role() = 'authenticated' OR auth.role() = 'anon');

-- --------------------------------------------------------------------
-- 3. SOURCES REGISTRY
-- --------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS public.sources (
    id TEXT PRIMARY KEY,
    provider_id TEXT NOT NULL,
    media_id TEXT NOT NULL,
    season INT,
    episode INT,
    title TEXT,
    url TEXT NOT NULL,
    format TEXT NOT NULL DEFAULT 'auto',
    quality TEXT NOT NULL DEFAULT '1080p',
    audio_languages TEXT[] NOT NULL DEFAULT '{}',
    has_hindi_audio BOOLEAN NOT NULL DEFAULT false,
    is_hindi_dubbed BOOLEAN NOT NULL DEFAULT false,
    is_multi_audio BOOLEAN NOT NULL DEFAULT false,
    is_enabled BOOLEAN NOT NULL DEFAULT true,
    priority_score INT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT timezone('utc'::text, now()),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT timezone('utc'::text, now())
);

CREATE INDEX IF NOT EXISTS idx_sources_lookup ON public.sources(media_id, season, episode, is_enabled);
CREATE INDEX IF NOT EXISTS idx_sources_hindi ON public.sources(has_hindi_audio);
CREATE INDEX IF NOT EXISTS idx_sources_provider ON public.sources(provider_id);

ALTER TABLE public.sources ENABLE ROW LEVEL SECURITY;

CREATE POLICY "Allow public read access to enabled sources"
    ON public.sources FOR SELECT
    USING (is_enabled = true);

CREATE POLICY "Allow authenticated source contribution"
    ON public.sources FOR ALL
    USING (auth.role() = 'authenticated' OR auth.role() = 'anon')
    WITH CHECK (auth.role() = 'authenticated' OR auth.role() = 'anon');

-- --------------------------------------------------------------------
-- 4. SOURCE HEALTH MONITORING
-- --------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS public.source_health (
    provider_id TEXT PRIMARY KEY,
    status TEXT NOT NULL DEFAULT 'ACTIVE', -- 'ACTIVE', 'DEGRADED', 'OFFLINE', 'RESTRICTED'
    latency_ms BIGINT NOT NULL DEFAULT 0,
    success_rate DOUBLE PRECISION NOT NULL DEFAULT 1.0,
    last_checked_at TIMESTAMPTZ NOT NULL DEFAULT timezone('utc'::text, now()),
    error_message TEXT
);

ALTER TABLE public.source_health ENABLE ROW LEVEL SECURITY;

CREATE POLICY "Allow public read access to source_health"
    ON public.source_health FOR SELECT
    USING (true);

CREATE POLICY "Allow upsert to source_health"
    ON public.source_health FOR ALL
    USING (auth.role() = 'authenticated' OR auth.role() = 'anon')
    WITH CHECK (auth.role() = 'authenticated' OR auth.role() = 'anon');

-- --------------------------------------------------------------------
-- 5. SOURCE FAILURES & DEAD-LINK TRACKING
-- --------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS public.source_failures (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    source_id TEXT,
    provider_id TEXT NOT NULL,
    media_id TEXT NOT NULL,
    error_code TEXT,
    error_message TEXT,
    retry_count INT NOT NULL DEFAULT 0,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT timezone('utc'::text, now())
);

CREATE INDEX IF NOT EXISTS idx_source_failures_media ON public.source_failures(media_id, provider_id);

ALTER TABLE public.source_failures ENABLE ROW LEVEL SECURITY;

CREATE POLICY "Allow public report insertion for source_failures"
    ON public.source_failures FOR INSERT
    WITH CHECK (true);

CREATE POLICY "Allow public read access to source_failures"
    ON public.source_failures FOR SELECT
    USING (true);

-- --------------------------------------------------------------------
-- 6. PLAYBACK SESSIONS (Session State & Source Switching Immunity)
-- --------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS public.playback_sessions (
    id TEXT PRIMARY KEY, -- Client-generated Session Token
    user_id UUID REFERENCES auth.users(id) ON DELETE SET NULL,
    media_id TEXT NOT NULL,
    season INT,
    episode INT,
    active_source_id TEXT,
    active_provider_id TEXT,
    selected_audio_language TEXT NOT NULL DEFAULT 'hi',
    selected_quality TEXT NOT NULL DEFAULT '1080p',
    playback_position_ms BIGINT NOT NULL DEFAULT 0,
    duration_ms BIGINT NOT NULL DEFAULT 0,
    is_locked BOOLEAN NOT NULL DEFAULT true,
    started_at TIMESTAMPTZ NOT NULL DEFAULT timezone('utc'::text, now()),
    last_heartbeat_at TIMESTAMPTZ NOT NULL DEFAULT timezone('utc'::text, now()),
    ended_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_playback_sessions_media ON public.playback_sessions(media_id, user_id);

ALTER TABLE public.playback_sessions ENABLE ROW LEVEL SECURITY;

CREATE POLICY "Allow users to read and update their own playback sessions"
    ON public.playback_sessions FOR ALL
    USING (auth.uid() = user_id OR user_id IS NULL)
    WITH CHECK (auth.uid() = user_id OR user_id IS NULL);

-- --------------------------------------------------------------------
-- 7. SOURCE CACHE (Remote Persistent Caching Layer)
-- --------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS public.source_cache (
    key TEXT PRIMARY KEY,
    category TEXT NOT NULL, -- 'metadata', 'sources', 'health', 'mappings'
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT timezone('utc'::text, now()),
    expires_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_source_cache_expiry ON public.source_cache(category, expires_at);

ALTER TABLE public.source_cache ENABLE ROW LEVEL SECURITY;

CREATE POLICY "Allow public read of unexpired cache entries"
    ON public.source_cache FOR SELECT
    USING (expires_at > timezone('utc'::text, now()));

CREATE POLICY "Allow public cache writes"
    ON public.source_cache FOR ALL
    USING (true)
    WITH CHECK (true);

-- --------------------------------------------------------------------
-- 8. USER PREFERENCES (Sync Across Restarts & Devices)
-- --------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS public.user_preferences (
    user_id UUID PRIMARY KEY REFERENCES auth.users(id) ON DELETE CASCADE,
    preferred_audio_language TEXT NOT NULL DEFAULT 'hi',
    preferred_quality TEXT NOT NULL DEFAULT 'Auto',
    max_auto_resolution TEXT NOT NULL DEFAULT '1080p',
    smart_failover_enabled BOOLEAN NOT NULL DEFAULT true,
    debrid_enabled BOOLEAN NOT NULL DEFAULT false,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT timezone('utc'::text, now())
);

ALTER TABLE public.user_preferences ENABLE ROW LEVEL SECURITY;

CREATE POLICY "Users can only read and update their own preferences"
    ON public.user_preferences FOR ALL
    USING (auth.uid() = user_id)
    WITH CHECK (auth.uid() = user_id);

-- --------------------------------------------------------------------
-- 9. SOURCE OPERATIONAL ANALYTICS
-- --------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS public.source_analytics (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_type TEXT NOT NULL, -- 'discovery_timing', 'playback_startup', 'quality_adaptation'
    provider_id TEXT,
    duration_ms BIGINT,
    success BOOLEAN NOT NULL DEFAULT true,
    network_type TEXT, -- 'WIFI', '4G_STRONG', '4G_NORMAL', '4G_WEAK'
    effective_bandwidth_kbps BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT timezone('utc'::text, now())
);

ALTER TABLE public.source_analytics ENABLE ROW LEVEL SECURITY;

CREATE POLICY "Allow public insertion of operational analytics"
    ON public.source_analytics FOR INSERT
    WITH CHECK (true);

CREATE POLICY "Allow public read-only access to analytics"
    ON public.source_analytics FOR SELECT
    USING (true);

-- --------------------------------------------------------------------
-- 10. PLAYBACK REPORTS
-- --------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS public.playback_reports (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id TEXT,
    media_id TEXT NOT NULL,
    buffering_count INT NOT NULL DEFAULT 0,
    total_buffering_time_ms BIGINT NOT NULL DEFAULT 0,
    switched_sources BOOLEAN NOT NULL DEFAULT false,
    switch_reason TEXT,
    reported_at TIMESTAMPTZ NOT NULL DEFAULT timezone('utc'::text, now())
);

ALTER TABLE public.playback_reports ENABLE ROW LEVEL SECURITY;

CREATE POLICY "Allow public insertion of playback reports"
    ON public.playback_reports FOR INSERT
    WITH CHECK (true);

CREATE POLICY "Allow public read-only access to playback reports"
    ON public.playback_reports FOR SELECT
    USING (true);
