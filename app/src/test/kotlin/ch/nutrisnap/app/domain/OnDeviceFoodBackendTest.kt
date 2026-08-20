package ch.nutrisnap.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnDeviceFoodBackendTest {

    @Test
    fun registryPrefersReadyBackend() {
        val active = OnDeviceFoodBackendRegistry.active()
        assertTrue(active.isReady())
        assertEquals("ML Kit", active.displayName)
    }

    @Test
    fun futureLlmNotReady() {
        assertFalse(FutureLlmFoodBackend.isReady())
        assertEquals("On-Device LLM (noch nicht aktiv)", FutureLlmFoodBackend.displayName)
    }

    @Test
    fun mlKitIsReady() {
        assertTrue(MlKitFoodBackend.isReady())
        assertEquals("ML Kit", MlKitFoodBackend.displayName)
    }
}
