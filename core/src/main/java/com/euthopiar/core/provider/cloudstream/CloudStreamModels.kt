package com.euthopiar.core.provider.cloudstream

import com.euthopiar.core.model.MediaType
import com.euthopiar.core.model.StreamContainer
import com.euthopiar.core.streams.StreamItem
import com.euthopiar.core.streams.StreamSubtitle

enum class CloudStreamIntegrationStatus {
    PASS,       // Fully integrated with authorized verified playback
    PARTIAL,    // Metadata/search active, direct stream needs specific configuration
    BLOCKED,    // Blocked due to encryption, CAPTCHA/Turnstile, .cs3 Dalvik runtime, or legal restrictions
    NOT_TESTED  // Extension discovered but not yet evaluated
}

data class CloudStreamExtensionMetadata(
    val id: String,
    val name: String,
    val internalName: String,
    val repositoryName: String, // "CNC Verse", "Phisher", "Official", etc.
    val packageUrl: String,     // e.g. .cs3 URL or manifest URL
    val version: Int = 1,
    val authors: List<String> = emptyList(),
    val description: String? = null,
    val supportedTypes: Set<MediaType> = setOf(MediaType.MOVIE, MediaType.TV_SERIES),
    val supportedLanguages: List<String> = listOf("hi", "en"),
    val status: CloudStreamIntegrationStatus = CloudStreamIntegrationStatus.NOT_TESTED,
    val statusReason: String = "Audited CloudStream extension"
)

data class CloudStreamResolvedLink(
    val url: String,
    val name: String,
    val quality: String = "HD",
    val container: StreamContainer = StreamContainer.AUTO,
    val headers: Map<String, String> = emptyMap(),
    val subtitles: List<StreamSubtitle> = emptyList()
)
