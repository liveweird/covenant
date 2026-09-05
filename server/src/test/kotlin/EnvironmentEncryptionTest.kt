package ch.nokillswit

import ch.nokillswit.environments.EnvironmentService
import ch.nokillswit.infra.crypto.FieldCipher
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The environments' encrypted columns: the backfill wraps legacy plaintext, a rotation re-wraps under the new key. */
class EnvironmentEncryptionTest {

    @Test
    fun `legacy plaintext rows are wrapped once, and a rotation re-encrypts under the current key`() = testApplication {
        usePostgresTestcontainer()
        val systemId = TestContracts.seedSystem("env-enc")
        val id = TestEnvironments.seedLegacyPlaintext(systemId, "legacy-${System.nanoTime()}", "plain-secret")
        assertEquals("plain-secret", TestEnvironments.rawRow(id).pgPassword)
        val wrapped = TestEnvironments.service.encryptLegacyRows()
        assertTrue(wrapped >= 1)
        val enveloped = TestEnvironments.rawRow(id).pgPassword!!
        assertTrue(enveloped.startsWith(FieldCipher.PREFIX))
        assertEquals("plain-secret", TestEnvironments.service.resolveTarget(id)!!.postgres!!.password)
        assertEquals(0, TestEnvironments.service.encryptLegacyRows(), "idempotent — nothing left in plaintext")
        // Rotation: a service on the NEW key with the old one as fallback re-wraps every row; the old key alone no longer reads it.
        val newKey = strongEncryptionKey()
        val rotatedCipher = FieldCipher(newKey, previousKeyHex = TestEnvironments.TEST_DATA_ENCRYPTION_KEY)
        val rotated = EnvironmentService(sharedDatabaseForTests(), rotatedCipher)
        assertTrue(rotated.encryptLegacyRows(reencryptAll = true) >= 1)
        val rewrapped = TestEnvironments.rawRow(id).pgPassword!!
        assertTrue(rewrapped != enveloped && rewrapped.startsWith(FieldCipher.PREFIX))
        val onNewKeyOnly = EnvironmentService(sharedDatabaseForTests(), FieldCipher(newKey))
        assertEquals("plain-secret", onNewKeyOnly.resolveTarget(id)!!.postgres!!.password)
        // Put the row back under the shared key so the rest of the suite (and re-runs) read it.
        val restore = EnvironmentService(sharedDatabaseForTests(), FieldCipher(TestEnvironments.TEST_DATA_ENCRYPTION_KEY, newKey))
        assertTrue(restore.encryptLegacyRows(reencryptAll = true) >= 1)
    }
}
