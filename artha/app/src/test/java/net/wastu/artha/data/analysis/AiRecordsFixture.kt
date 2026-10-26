package net.wastu.artha.data.analysis

/** Builds per-payment AI records from the separate maps older tests were written against. */
fun aiRecords(
    fingerprints: Map<String, String> = emptyMap(),
    confidence: Map<String, CategoryConfidence> = emptyMap(),
    failures: Map<String, String> = emptyMap(),
    consistent: Set<String> = emptySet(),
): Map<String, AiRecord> = (fingerprints.keys + confidence.keys + failures.keys + consistent).associateWith { key ->
    AiRecord(
        fingerprint = fingerprints[key],
        confidence = confidence[key]?.let(::percentOf),
        failure = failures[key],
        merchantConsistent = key in consistent,
    )
}

fun percentOf(level: CategoryConfidence): Int = when (level) {
    CategoryConfidence.HIGH -> 95
    CategoryConfidence.MEDIUM -> 93
    CategoryConfidence.LOW -> 50
}
