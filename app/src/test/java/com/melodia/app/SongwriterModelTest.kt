package com.melodia.app

import com.melodia.app.engine.Songwriter
import org.junit.Assert.assertEquals
import org.junit.Test

class SongwriterModelTest {
    @Test
    fun picksTheBiggestSongwriterUnlessOneWasChosen() {
        val models = listOf("acestep-5Hz-lm-0.6B-Q8_0.gguf", "acestep-5Hz-lm-4B-Q5_K_M.gguf", "acestep-5Hz-lm-1.7B-Q8_0.gguf")
        assertEquals("acestep-5Hz-lm-4B-Q5_K_M.gguf", Songwriter.preferredModel(models))
        assertEquals("acestep-5Hz-lm-0.6B-Q8_0.gguf", Songwriter.preferredModel(models, "acestep-5Hz-lm-0.6B-Q8_0.gguf"))
        assertEquals("acestep-5Hz-lm-1.7B-Q8_0.gguf", Songwriter.preferredModel(models.take(1) + models.last(), "missing.gguf"))
        assertEquals("", Songwriter.preferredModel(emptyList()))
    }
}
