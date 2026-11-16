package com.euthopiar.ui.player.sequential

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekPoint
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.TsExtractor

/**
 * UniversalSafeExtractorsFactory provides bulletproof seek safety for PlavioHD and remote progressive
 * cloud streams (MKV, MP4, WebM, TS).
 *
 * Problem:
 * Remote MKV and MP4 files streamed over HTTPS often have Cues/Moov at the tail of the stream.
 * Standard ExoPlayer extractors return SeekPoint.START (00:00) when Cues haven't been loaded yet,
 * causing forward-seeking (skip 10s) to violently reset playback back to the beginning (00:00)!
 *
 * Solution:
 * 1. Sets [MatroskaExtractor.FLAG_DISABLE_SEEK_FOR_CUES] so MatroskaExtractor doesn't fail HTTP byte-stream connections.
 * 2. Wraps SeekMap with [UniversalSafeSeekMap] which guarantees that seeks for non-zero timestamps NEVER return 00:00.
 */
@UnstableApi
class UniversalSafeExtractorsFactory(
    private val durationMsProvider: () -> Long
) : ExtractorsFactory {

    override fun createExtractors(): Array<Extractor> {
        val extractors = DefaultExtractorsFactory()
            .setConstantBitrateSeekingEnabled(true)
            .setConstantBitrateSeekingAlwaysEnabled(true)
            .setMatroskaExtractorFlags(MatroskaExtractor.FLAG_DISABLE_SEEK_FOR_CUES)
            .setTsExtractorFlags(
                DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS or
                DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
                DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
            )
            .setTsExtractorTimestampSearchBytes(1500 * TsExtractor.TS_PACKET_SIZE)
            .createExtractors()

        return extractors.map { delegate ->
            object : Extractor {
                override fun sniff(input: ExtractorInput): Boolean = delegate.sniff(input)

                override fun init(output: ExtractorOutput) {
                    delegate.init(object : ExtractorOutput {
                        override fun track(id: Int, type: Int): TrackOutput = output.track(id, type)

                        override fun endTracks() = output.endTracks()

                        override fun seekMap(seekMap: SeekMap) {
                            output.seekMap(
                                UniversalSafeSeekMap(
                                    delegate = seekMap,
                                    durationMsProvider = durationMsProvider
                                )
                            )
                        }
                    })
                }

                override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int =
                    delegate.read(input, seekPosition)

                override fun seek(position: Long, timeUs: Long) = delegate.seek(position, timeUs)

                override fun release() = delegate.release()
            }
        }.toTypedArray()
    }
}

/**
 * UniversalSafeSeekMap wraps any underlying SeekMap to guarantee forward/backward seeking never snaps to 00:00.
 */
@UnstableApi
class UniversalSafeSeekMap(
    private val delegate: SeekMap,
    private val durationMsProvider: () -> Long
) : SeekMap {

    override fun isSeekable(): Boolean = true

    override fun getDurationUs(): Long {
        if (delegate.durationUs != C.TIME_UNSET && delegate.durationUs > 0) {
            return delegate.durationUs
        }
        val durMs = durationMsProvider()
        return if (durMs > 0L) durMs * 1000L else C.TIME_UNSET
    }

    override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints {
        if (timeUs <= 0L) {
            return delegate.getSeekPoints(0L)
        }

        val delegatePoints = delegate.getSeekPoints(timeUs)
        val p1 = delegatePoints.first

        // If the underlying extractor parsed valid non-zero seek points, use them!
        if (delegate.isSeekable && (p1.timeUs > 0L || p1.position > 0L)) {
            return delegatePoints
        }

        // Delegate returned SeekPoint.START (00:00) or claims unseekable.
        // We MUST NEVER return SeekPoint.START for non-zero seeks!
        // Preserve requested timeUs and synthesize safe non-zero point:
        val safePoint = SeekPoint(timeUs, maxOf(1L, p1.position))
        return SeekMap.SeekPoints(safePoint)
    }
}
