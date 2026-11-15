package ca.emarshall.finance.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BackupTest {
    private val password = "correct horse".toCharArray()

    @Test fun encryptsAndDecrypts() {
        val plain = "account data".toByteArray()
        val file = BackupCrypto.encrypt(plain, password)
        assertTrue(BackupCrypto.looksLikeBackup(file))
        assertFalse(String(file, Charsets.ISO_8859_1).contains("account data"))
        assertArrayEquals(plain, BackupCrypto.decrypt(file, password))
        // A fresh salt and nonce every time: the same data never encrypts to the same bytes.
        assertFalse(file.contentEquals(BackupCrypto.encrypt(plain, password)))
    }

    @Test fun wrongPasswordAndDamageFailCleanly() {
        val file = BackupCrypto.encrypt("x".repeat(100).toByteArray(), password)
        try { BackupCrypto.decrypt(file, "wrong password".toCharArray()); fail() } catch (e: BackupException) { assertTrue(e.message!!.contains("Wrong password")) }
        val damaged = file.copyOf().also { it[it.size - 5] = (it[it.size - 5] + 1).toByte() }
        try { BackupCrypto.decrypt(damaged, password); fail() } catch (e: BackupException) { }
        try { BackupCrypto.decrypt("not a backup at all, just some text".toByteArray(), password); fail() } catch (e: BackupException) { assertTrue(e.message!!.contains("isn't")) }
        try { BackupCrypto.encrypt(byteArrayOf(1), "short".toCharArray()); fail() } catch (e: BackupException) { }
    }

    @Test fun contentsSurviveARoundTrip() {
        val contents = BackupContents(
            schemaVersion = 4, createdAtMillis = 1_700_000_000_000,
            tables = linkedMapOf(
                "accounts" to listOf(mapOf("id" to 1L, "nickname" to "Chequing", "last4" to null, "confirmed" to 1L)),
                "transactions" to listOf(
                    mapOf("id" to 7L, "accountId" to 1L, "amountCents" to -123_456L, "rawDescription" to "Café \"Quote\" \\ slash", "notes" to null),
                    mapOf("id" to 8L, "accountId" to 1L, "amountCents" to 9_007_199_254_740_993L, "rawDescription" to "42", "notes" to "x"),
                ),
                "empty" to emptyList(),
            ),
            statementFiles = mapOf(3L to byteArrayOf(0, 1, 2, -1, -128, 127)),
        )
        val back = BackupFormat.decode(BackupCrypto.decrypt(BackupCrypto.encrypt(BackupFormat.encode(contents), password), password))
        assertEquals(4, back.schemaVersion)
        assertEquals(1_700_000_000_000, back.createdAtMillis)
        assertEquals(listOf("accounts", "transactions", "empty"), back.tables.keys.toList())
        assertEquals(contents.tables["accounts"], back.tables["accounts"])
        val tx = back.tables.getValue("transactions")
        assertEquals(-123_456L, tx[0]["amountCents"])
        assertEquals("Café \"Quote\" \\ slash", tx[0]["rawDescription"])
        assertNull(tx[0]["notes"])
        // Cents stay exact Longs beyond what a double can hold, and a numeric-looking string stays a string.
        assertEquals(9_007_199_254_740_993L, tx[1]["amountCents"])
        assertEquals("42", tx[1]["rawDescription"])
        assertArrayEquals(byteArrayOf(0, 1, 2, -1, -128, 127), back.statementFiles[3L])
    }

    @Test fun rejectsOtherJsonAndNewerFormats() {
        try { BackupFormat.decode("{\"format\":\"something-else\"}".toByteArray()); fail() } catch (e: BackupException) { }
        try { BackupFormat.decode("not json".toByteArray()); fail() } catch (e: BackupException) { }
        try { BackupFormat.decode("{\"format\":\"finance-tracker-backup\",\"formatVersion\":99}".toByteArray()); fail() } catch (e: BackupException) { assertTrue(e.message!!.contains("newer")) }
    }
}
