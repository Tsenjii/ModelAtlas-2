package dev.modelatlas.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderModelBatchStateTest {
    @Test fun explicitModelsAreBoundedDistinctAndNotSilentlyNormalized() {
        assertTrue(ProviderModelBatchState.validModels(listOf("one", "two")))
        assertTrue(ProviderModelBatchState.validModels((1..100).map { "m-$it" }))
        for (models in listOf(emptyList(), listOf("one", "one"), listOf(" one"), listOf("bad\nmodel"), listOf("a".repeat(513)), (1..101).map { "m-$it" })) {
            assertFalse(ProviderModelBatchState.validModels(models))
        }
    }
}
