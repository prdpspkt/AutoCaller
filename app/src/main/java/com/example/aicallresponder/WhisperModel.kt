package com.example.aicallresponder

/**
 * A downloadable whisper.cpp ggml model. Files come from the official whisper.cpp model repo.
 */
data class WhisperModel(
    val id: String,
    val name: String,
    val sizeLabel: String,
    val approxBytes: Long,   // used for the progress bar if the server omits Content-Length
    val fileName: String,
    val note: String
) {
    val url: String get() = BASE_URL + fileName

    companion object {
        private const val BASE_URL =
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/"
    }
}

/**
 * Curated catalog, lightest → heaviest. Notes are geared at the Nepali-over-a-phone-call use case:
 * larger = more accurate but slower and more RAM. Quantized (q5_0) variants trade a little accuracy
 * for a big size/speed win.
 */
object WhisperCatalog {
    val models: List<WhisperModel> = listOf(
        WhisperModel(
            "tiny", "tiny", "~75 MB", 75_000_000,
            "ggml-tiny.bin", "Fastest, lowest accuracy. Good only for a quick smoke test."
        ),
        WhisperModel(
            "base", "base", "~142 MB", 142_000_000,
            "ggml-base.bin", "Fast, modest accuracy."
        ),
        WhisperModel(
            "small", "small", "~466 MB", 466_000_000,
            "ggml-small.bin", "Balanced. Reasonable Nepali accuracy, a few seconds per reply."
        ),
        WhisperModel(
            "medium", "medium", "~1.5 GB", 1_500_000_000,
            "ggml-medium.bin", "High accuracy, noticeably slower and heavier on RAM."
        ),
        WhisperModel(
            "large-v3-turbo-q5", "large-v3-turbo (q5_0)", "~574 MB", 574_000_000,
            "ggml-large-v3-turbo-q5_0.bin",
            "Near-large accuracy, quantized for smaller size/faster load. Best practical pick."
        ),
        WhisperModel(
            "large-v3-turbo", "large-v3-turbo", "~1.6 GB", 1_600_000_000,
            "ggml-large-v3-turbo.bin", "Almost large-v3 accuracy, faster decoding."
        ),
        WhisperModel(
            "large-v3", "large-v3", "~3.1 GB", 3_095_033_483,
            "ggml-large-v3.bin",
            "Best accuracy. Very heavy: large download, high RAM, slowest per reply on a phone."
        )
    )

    fun byId(id: String?): WhisperModel? = models.firstOrNull { it.id == id }
}
