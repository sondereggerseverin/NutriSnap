package ch.nutrisnap.app.utils

import org.junit.Test

class AppLogTest {

    @Test
    fun `warn wirft in JVM-Tests nicht`() {
        AppLog.warn("Test", "Meldung")
        AppLog.warn("Test", "Meldung mit Fehler", IllegalStateException("x"))
    }

    @Test
    fun `error wirft ohne initialisierten CrashLogger nicht`() {
        AppLog.error("Test", "Meldung")
        AppLog.error("Test", "Meldung mit Fehler", RuntimeException("x"))
    }
}
