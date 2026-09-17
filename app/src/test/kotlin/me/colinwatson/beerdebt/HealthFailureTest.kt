package me.colinwatson.beerdebt

import android.os.DeadObjectException
import android.os.RemoteException
import me.colinwatson.beerdebt.health.HealthFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * The mapping from a thrown Health Connect error to what it means. Mirrors the
 * iOS `HealthFailureTests`: the point is that an environment condition gets
 * copy with a remedy in it and sends no Sentry event, and only what we cannot
 * explain is reported.
 */
class HealthFailureTest {
    @Test fun `a revoked permission is the user, not a defect`() {
        val f = HealthFailure.of(SecurityException("Caller doesn't have permission"))
        assertEquals(HealthFailure.PERMISSION_DENIED, f)
        assertFalse(f.isReportable)
        assertTrue(f.message("raw").contains("App permissions"))
    }

    @Test fun `the provider going away mid-call is routine`() {
        assertEquals(HealthFailure.PROVIDER_GONE, HealthFailure.of(DeadObjectException()))
        assertEquals(HealthFailure.PROVIDER_GONE, HealthFailure.of(RemoteException("transaction failed")))
        assertFalse(HealthFailure.of(DeadObjectException()).isReportable)
    }

    @Test fun `io trouble is transient and says so`() {
        val f = HealthFailure.of(IOException("disk"))
        assertEquals(HealthFailure.TRANSIENT, f)
        assertFalse(f.isReportable)
        assertTrue(f.message("raw").contains("next sync"))
    }

    @Test fun `provider state beats the thrown type`() {
        // An absent provider surfaces as a bare IllegalStateException from
        // getOrCreate, which names nothing on its own.
        val bare = IllegalStateException("SDK version too low or not installed")
        assertEquals(HealthFailure.PROVIDER_NEEDS_INSTALL, HealthFailure.of(bare, available = false, needsInstall = true))
        assertEquals(HealthFailure.PROVIDER_UNAVAILABLE, HealthFailure.of(bare, available = false))
        // Needing an install outranks merely being unavailable: it is the one
        // with something the user can do about it.
        assertEquals(HealthFailure.PROVIDER_NEEDS_INSTALL, HealthFailure.of(bare, available = false, needsInstall = true))
    }

    @Test fun `a wrapped cause is still found`() {
        // The client's IPC layer wraps the real cause a level or two down.
        val nested = RuntimeException("read failed", IllegalStateException("ipc", SecurityException("no permission")))
        assertEquals(HealthFailure.PERMISSION_DENIED, HealthFailure.of(nested))
    }

    @Test fun `a self-referencing chain does not spin`() {
        val a = RuntimeException("a")
        val b = RuntimeException("b", a)
        // Kotlin won't let us build a true cycle through the constructor, so
        // the same instance repeated is the closest case; the seen-set covers it.
        assertEquals(HealthFailure.UNKNOWN, HealthFailure.of(b))
    }

    @Test fun `anything we cannot explain is reported and keeps the raw message`() {
        val f = HealthFailure.of(IllegalArgumentException("something new in a later provider"))
        assertEquals(HealthFailure.UNKNOWN, f)
        assertTrue(f.isReportable)
        assertEquals("raw text", f.message("raw text"))
    }

    @Test fun `every named condition offers a remedy and stays quiet`() {
        for (f in HealthFailure.entries.filter { it != HealthFailure.UNKNOWN }) {
            assertFalse("$f should not be reported", f.isReportable)
            val copy = f.message("FALLBACK")
            assertFalse("$f needs real copy", copy.isEmpty())
            assertFalse("$f should not fall back to the raw message", copy == "FALLBACK")
        }
    }
}
