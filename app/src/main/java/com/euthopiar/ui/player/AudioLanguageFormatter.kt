package com.euthopiar.ui.player

import java.util.Locale

/**
 * Intelligent Audio Language Resolver and Formatter for MOVIE HUB.
 *
 * Normalizes ISO-639-1, ISO-639-2/B, ISO-639-2/T codes, container track labels,
 * and metadata tokens into clean, human-readable language names (e.g. "Hindi", "English", "Tamil").
 * Completely strips ugly technical codes ("und", "audio_0_hin_stereo", "hin-2ch-128k", "Track 1").
 */
object AudioLanguageFormatter {

    // Comprehensive standard language mapping covering Indian regional, Asian, European & World languages
    private val codeToLanguageName = mapOf(
        // Indian Regional Languages
        "hi" to "Hindi", "hin" to "Hindi",
        "ta" to "Tamil", "tam" to "Tamil",
        "te" to "Telugu", "tel" to "Telugu",
        "ml" to "Malayalam", "mal" to "Malayalam",
        "bn" to "Bengali", "ben" to "Bengali",
        "mr" to "Marathi", "mar" to "Marathi",
        "gu" to "Gujarati", "guj" to "Gujarati",
        "pa" to "Punjabi", "pan" to "Punjabi",
        "kn" to "Kannada", "kan" to "Kannada",
        "ur" to "Urdu", "urd" to "Urdu",
        "or" to "Odia", "ori" to "Odia",
        "as" to "Assamese", "asm" to "Assamese",
        "bho" to "Bhojpuri",

        // Global Common Languages
        "en" to "English", "eng" to "English",
        "ja" to "Japanese", "jpn" to "Japanese",
        "ko" to "Korean", "kor" to "Korean",
        "zh" to "Chinese", "chi" to "Chinese", "zho" to "Chinese", "cmn" to "Mandarin", "yue" to "Cantonese",
        "es" to "Spanish", "spa" to "Spanish",
        "fr" to "French", "fra" to "French", "fre" to "French",
        "de" to "German", "deu" to "German", "ger" to "German",
        "ru" to "Russian", "rus" to "Russian",
        "it" to "Italian", "ita" to "Italian",
        "pt" to "Portuguese", "por" to "Portuguese",
        "ar" to "Arabic", "ara" to "Arabic",
        "tr" to "Turkish", "tur" to "Turkish",
        "id" to "Indonesian", "ind" to "Indonesian",
        "th" to "Thai", "tha" to "Thai",
        "vi" to "Vietnamese", "vie" to "Vietnamese",
        "nl" to "Dutch", "nld" to "Dutch", "dut" to "Dutch",
        "pl" to "Polish", "pol" to "Polish",
        "sv" to "Swedish", "swe" to "Swedish",
        "da" to "Danish", "dan" to "Danish",
        "no" to "Norwegian", "nor" to "Norwegian",
        "fi" to "Finnish", "fin" to "Finnish",
        "el" to "Greek", "ell" to "Greek",
        "he" to "Hebrew", "heb" to "Hebrew",
        "fa" to "Persian", "fas" to "Persian", "per" to "Persian",
        "uk" to "Ukrainian", "ukr" to "Ukrainian",
        "ms" to "Malay", "msa" to "Malay", "may" to "Malay",
        "tl" to "Tagalog", "tgl" to "Tagalog", "fil" to "Filipino"
    )

    private fun langRegex(vararg tokens: String): Regex {
        val alternatives = tokens.joinToString("|") { Regex.escape(it) }
        return Regex("""(?i)(?:^|[\W_])(?:$alternatives)(?:[\W_]|$)""")
    }

    private val keywordPatterns = listOf(
        langRegex("hindi", "hin") to "Hindi",
        langRegex("english", "eng") to "English",
        langRegex("tamil", "tam") to "Tamil",
        langRegex("telugu", "tel") to "Telugu",
        langRegex("malayalam", "mal") to "Malayalam",
        langRegex("bengali", "ben") to "Bengali",
        langRegex("marathi", "mar") to "Marathi",
        langRegex("gujarati", "guj") to "Gujarati",
        langRegex("punjabi", "pan") to "Punjabi",
        langRegex("kannada", "kan") to "Kannada",
        langRegex("urdu", "urd") to "Urdu",
        langRegex("japanese", "jpn", "jap") to "Japanese",
        langRegex("korean", "kor") to "Korean",
        langRegex("chinese", "chi", "man", "mandarin") to "Chinese",
        langRegex("spanish", "spa", "esp", "lat", "latino") to "Spanish",
        langRegex("french", "fra", "fre") to "French",
        langRegex("german", "ger", "deu") to "German",
        langRegex("russian", "rus") to "Russian",
        langRegex("italian", "ita") to "Italian",
        langRegex("portuguese", "por") to "Portuguese",
        langRegex("arabic", "ara") to "Arabic",
        langRegex("turkish", "tur") to "Turkish",
        langRegex("thai", "tha") to "Thai",
        langRegex("indonesian", "ind") to "Indonesian"
    )

    /**
     * Resolves human-readable language name from ISO code or track label.
     * Returns "Unknown" if no recognized language can be determined.
     */
    fun resolveLanguageName(
        isoCode: String?,
        rawLabel: String?
    ): String {
        // 1. Direct lookup from normalized ISO code
        val cleanCode = isoCode?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotBlank() && it != "und" && it != "auto" }
        if (cleanCode != null) {
            codeToLanguageName[cleanCode]?.let { return it }
            // Try standard Locale fallback for standard codes
            try {
                val locale = Locale.forLanguageTag(cleanCode)
                val display = locale.getDisplayLanguage(Locale.ENGLISH).replaceFirstChar { it.uppercase(Locale.ROOT) }
                if (display.isNotBlank() && !display.equals(cleanCode, ignoreCase = true)) {
                    return display
                }
            } catch (_: Exception) {}
        }

        // 2. Scan raw label for explicit language tokens (e.g. "Hindi Dubbed", "English [Original]", "hin_stereo")
        if (!rawLabel.isNullOrBlank()) {
            for ((pattern, langName) in keywordPatterns) {
                if (pattern.containsMatchIn(rawLabel)) {
                    return langName
                }
            }
        }

        // 3. Scan code string if not matched yet
        if (cleanCode != null) {
            for ((pattern, langName) in keywordPatterns) {
                if (pattern.containsMatchIn(cleanCode)) {
                    return langName
                }
            }
        }

        return "Unknown"
    }

    /**
     * Builds a clean, professional display title for the audio track selector.
     * Example outputs:
     * - "Hindi"
     * - "Hindi [Dubbed]"
     * - "English (5.1 Surround)"
     * - "Tamil (Stereo)"
     * - "Japanese [Original]"
     * - "Unknown (Track 1)"
     */
    fun formatTrackDisplayName(
        isoCode: String?,
        rawLabel: String?,
        channelCount: Int = 0,
        sampleMimeType: String? = null,
        trackIndex: Int = 0,
        originalLanguage: String? = null
    ): String {
        val resolvedLang = resolveLanguageName(isoCode, rawLabel)
        val builder = StringBuilder()

        val isUnknown = resolvedLang == "Unknown"
        if (isUnknown) {
            builder.append("Unknown (Track ${trackIndex + 1})")
        } else {
            builder.append(resolvedLang)
        }

        // Add Dubbed / Original tag if applicable
        val labelLower = rawLabel.orEmpty().lowercase(Locale.ROOT)
        val isExplicitDub = labelLower.contains("dub")
        val isExplicitOrig = labelLower.contains("original") || labelLower.contains("orig")

        val isHindi = resolvedLang.equals("Hindi", ignoreCase = true)
        val isNonHindiOriginal = !originalLanguage.isNullOrBlank() &&
                !originalLanguage.equals("hi", ignoreCase = true) &&
                !originalLanguage.equals("hin", ignoreCase = true)

        if (isExplicitDub || (isHindi && isNonHindiOriginal)) {
            builder.append(" [Dubbed]")
        } else if (isExplicitOrig) {
            builder.append(" [Original]")
        }

        // Clean Channel / Codec details
        val channelStr = when (channelCount) {
            1 -> "Mono"
            2 -> "Stereo"
            6 -> "5.1"
            8 -> "7.1"
            else -> if (channelCount > 0) "${channelCount}ch" else null
        }

        val codecStr = when {
            sampleMimeType?.contains("eac3-joc", ignoreCase = true) == true -> "Atmos"
            sampleMimeType?.contains("eac3", ignoreCase = true) == true -> "DDP"
            sampleMimeType?.contains("true-hd", ignoreCase = true) == true -> "TrueHD"
            sampleMimeType?.contains("dts", ignoreCase = true) == true -> "DTS"
            sampleMimeType?.contains("ac3", ignoreCase = true) == true -> "AC3"
            sampleMimeType?.contains("opus", ignoreCase = true) == true -> "Opus"
            sampleMimeType?.contains("aac", ignoreCase = true) == true || sampleMimeType?.contains("mp4a", ignoreCase = true) == true -> "AAC"
            else -> null
        }

        val audioDetails = listOfNotNull(channelStr, codecStr).joinToString(" \u00B7 ")
        if (audioDetails.isNotBlank()) {
            builder.append(" ($audioDetails)")
        }

        return builder.toString()
    }
}
