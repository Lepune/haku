package fi.lepune.haku.data

data class ModelSpec(
    val id: String,
    val name: String,
    val repo: String,
    val fileName: String,
    val approxSizeMb: Int,
    val description: String,
    /** Model can drive the search tools. */
    val supportsTools: Boolean = true,
    /** Precompiled for one NPU only; never try GPU/CPU with it. */
    val npuOnly: Boolean = false,
    /** Context window to allocate. null = model default. */
    val maxTokens: Int? = 12288,
) {
    val url: String get() = "https://huggingface.co/$repo/resolve/main/$fileName?download=true"
}

object ModelCatalog {
    val GEMMA4_E4B = ModelSpec(
        id = "gemma4-e4b",
        name = "Gemma 4 E4B",
        repo = "litert-community/gemma-4-E4B-it-litert-lm",
        fileName = "gemma-4-E4B-it.litertlm",
        approxSizeMb = 3660,
        description = "Best quality that runs well on the phone. Recommended. GPU.",
    )

    val GEMMA4_E2B = ModelSpec(
        id = "gemma4-e2b",
        name = "Gemma 4 E2B",
        repo = "litert-community/gemma-4-E2B-it-litert-lm",
        fileName = "gemma-4-E2B-it.litertlm",
        approxSizeMb = 2600,
        description = "About 3x faster, noticeably dumber. Good for quick lookups.",
    )

    val GEMMA3_1B_G4_NPU = ModelSpec(
        id = "gemma3-1b-g4npu",
        name = "Gemma 3 1B · Tensor G4 NPU test",
        repo = "litert-community/Gemma3-1B-IT-Tensor-G4-NPU",
        fileName = "Gemma3-1B-IT_int4hadamard_aot_ekv4096_G4.litertlm",
        approxSizeMb = 875,
        description = "Only exists to prove the NPU works. Too small for search; chat only. " +
            "May fail if the bundled NPU runtime doesn't match the phone's firmware.",
        supportsTools = false,
        npuOnly = true,
        maxTokens = null,
    )

    val ALL = listOf(GEMMA4_E4B, GEMMA4_E2B, GEMMA3_1B_G4_NPU)
    val DEFAULT = GEMMA4_E4B

    fun byId(id: String) = ALL.firstOrNull { it.id == id }
}
