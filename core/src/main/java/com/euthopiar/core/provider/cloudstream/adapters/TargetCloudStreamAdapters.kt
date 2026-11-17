package com.euthopiar.core.provider.cloudstream.adapters

import com.euthopiar.core.provider.cloudstream.CloudStreamEcosystemCatalog
import com.euthopiar.core.provider.cloudstream.CloudStreamExtensionMetadata
import com.euthopiar.core.provider.cloudstream.CloudStreamProviderAdapter

/**
 * Adapter representing the Castle TV CloudStream extension.
 * Status: BLOCKED due to proprietary private response encryption & private token requirement.
 */
class CastleTvCloudStreamAdapter(
    meta: CloudStreamExtensionMetadata = CloudStreamEcosystemCatalog.AUDITED_TARGET_EXTENSIONS.first { it.id == "cs_castletv" }
) : CloudStreamProviderAdapter(meta)

/**
 * Adapter representing the VegaMovies CloudStream extension.
 * Status: BLOCKED due to Cloudflare Turnstile anti-bot gates and .cs3 Dalvik runtime dependency.
 */
class VegaMoviesCloudStreamAdapter(
    meta: CloudStreamExtensionMetadata = CloudStreamEcosystemCatalog.AUDITED_TARGET_EXTENSIONS.first { it.id == "cs_vegamovies" }
) : CloudStreamProviderAdapter(meta)

/**
 * Adapter representing the HDhub4u CloudStream extension.
 * Status: BLOCKED due to .cs3 compiled Dalvik bytecode and unlicensed scraper links.
 */
class HDhub4uCloudStreamAdapter(
    meta: CloudStreamExtensionMetadata = CloudStreamEcosystemCatalog.AUDITED_TARGET_EXTENSIONS.first { it.id == "cs_hdhub4u" }
) : CloudStreamProviderAdapter(meta)

/**
 * Adapter representing the Cricify CloudStream extension.
 * Status: BLOCKED due to unlicensed live sports broadcasts and incompatible .cs3 format.
 */
class CricifyCloudStreamAdapter(
    meta: CloudStreamExtensionMetadata = CloudStreamEcosystemCatalog.AUDITED_TARGET_EXTENSIONS.first { it.id == "cs_cricify" }
) : CloudStreamProviderAdapter(meta)

/**
 * Adapter representing the DaddyLive CloudStream extension.
 * Status: BLOCKED due to unlicensed rebroadcasts requiring referer token spoofing.
 */
class DaddyLiveCloudStreamAdapter(
    meta: CloudStreamExtensionMetadata = CloudStreamEcosystemCatalog.AUDITED_TARGET_EXTENSIONS.first { it.id == "cs_daddylive" }
) : CloudStreamProviderAdapter(meta)

/**
 * Adapter representing the HiAnime CloudStream extension.
 * Status: BLOCKED due to compiled .cs3 binary and unlicensed anime hosts.
 */
class HiAnimeCloudStreamAdapter(
    meta: CloudStreamExtensionMetadata = CloudStreamEcosystemCatalog.AUDITED_TARGET_EXTENSIONS.first { it.id == "cs_hianime" }
) : CloudStreamProviderAdapter(meta)
