package com.dropsync.domain.library

/**
 * Vierstufige Markerzuordnung, streng in dieser Reihenfolge (Bauplan 5.1):
 *
 * 1. Import-SHA-256 stimmt mit einem bereits gespeicherten externen Hash
 *    ueberein. Ohne gespeicherte Hashes wird diese Stufe uebersprungen.
 * 2. relativePath + displayName + sizeBytes + durationMs exakt.
 * 3. displayName + sizeBytes + durationMs mit genau einem Treffer.
 * 4. Sonst nicht zugeordnet; bei mehreren Treffern wird nie geraten.
 */
class MarkerMatcher {
    fun match(
        track: ImportedTrack,
        candidates: List<SongFingerprint>,
    ): MatchResult {
        // Stufe 1: gespeicherter externer Hash.
        val importHash = track.sha256?.lowercase()
        if (importHash != null) {
            val hashHits = candidates.filter { it.knownSha256?.lowercase() == importHash }
            when {
                hashHits.size == 1 -> {
                    return MatchResult.Matched(hashHits.single().mediaStoreId, MatchMethod.HASH)
                }

                hashHits.size > 1 -> {
                    return MatchResult.Ambiguous(hashHits.map { it.mediaStoreId })
                }
                // 0 Treffer: Stufe wird uebersprungen.
            }
        }

        // Stufe 2: strikte Metadaten.
        val strictHits =
            candidates.filter {
                it.relativePath == track.relativePath &&
                    it.displayName == track.displayName &&
                    it.sizeBytes == track.sizeBytes &&
                    it.durationMs == track.durationMs
            }
        when {
            strictHits.size == 1 -> {
                return MatchResult.Matched(strictHits.single().mediaStoreId, MatchMethod.METADATA_STRICT)
            }

            strictHits.size > 1 -> {
                return MatchResult.Ambiguous(strictHits.map { it.mediaStoreId })
            }
        }

        // Stufe 3: lose Metadaten, nur bei genau einem Treffer.
        val looseHits =
            candidates.filter {
                it.displayName == track.displayName &&
                    it.sizeBytes == track.sizeBytes &&
                    it.durationMs == track.durationMs
            }
        return when {
            looseHits.size == 1 -> {
                MatchResult.Matched(looseHits.single().mediaStoreId, MatchMethod.METADATA_LOOSE)
            }

            looseHits.size > 1 -> {
                MatchResult.Ambiguous(looseHits.map { it.mediaStoreId })
            }

            // Stufe 4: nicht zugeordnet.
            else -> {
                MatchResult.Unmatched
            }
        }
    }
}
