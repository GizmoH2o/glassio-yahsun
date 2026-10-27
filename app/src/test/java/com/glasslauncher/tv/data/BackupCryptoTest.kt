package com.glasslauncher.tv.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

/** The backup's lock: what goes in comes out with the right code, and nothing comes out otherwise. */
class BackupCryptoTest {
    // Fewer rounds than a real backup, so the tests stay quick; the code path is the same.
    private val rounds = 1_000
    private val payload = """{"prefs":{"glass":{"theme":{"t":"t","v":"liquid"}}},"files":{"live-login.json":"eyJzZXJ2ZXIiOiJ4In0="}}"""
        .toByteArray(Charsets.UTF_8)

    @Test fun rightCodeOpensIt() {
        val sealed = BackupCrypto.seal(payload, "correct-horse", rounds)
        assertArrayEquals(payload, BackupCrypto.open(sealed, "correct-horse", rounds))
    }

    @Test fun realRoundsOpenToo() {
        val sealed = BackupCrypto.seal(payload, "correct-horse")
        assertArrayEquals(payload, BackupCrypto.open(sealed, "correct-horse"))
    }

    @Test fun wrongCodeIsRefused() {
        val sealed = BackupCrypto.seal(payload, "correct-horse", rounds)
        assertThrows(BackupCrypto.WrongCode::class.java) { BackupCrypto.open(sealed, "correct-hors3", rounds) }
    }

    @Test fun changedFileIsRefused() {
        val sealed = BackupCrypto.seal(payload, "correct-horse", rounds)
        val data = sealed.data.copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 1).toByte() }
        assertThrows(BackupCrypto.WrongCode::class.java) {
            BackupCrypto.open(BackupCrypto.Sealed(sealed.salt, sealed.iv, data), "correct-horse", rounds)
        }
    }

    @Test fun cutShortFileIsRefused() {
        val sealed = BackupCrypto.seal(payload, "correct-horse", rounds)
        val data = sealed.data.copyOf(sealed.data.size - 5)
        assertThrows(BackupCrypto.WrongCode::class.java) {
            BackupCrypto.open(BackupCrypto.Sealed(sealed.salt, sealed.iv, data), "correct-horse", rounds)
        }
    }

    @Test fun changedSaltIsRefused() {
        val sealed = BackupCrypto.seal(payload, "correct-horse", rounds)
        val salt = sealed.salt.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertThrows(BackupCrypto.WrongCode::class.java) {
            BackupCrypto.open(BackupCrypto.Sealed(salt, sealed.iv, sealed.data), "correct-horse", rounds)
        }
    }

    @Test fun eachBackupIsLockedAfresh() {
        val a = BackupCrypto.seal(payload, "correct-horse", rounds)
        val b = BackupCrypto.seal(payload, "correct-horse", rounds)
        assertFalse(a.salt.contentEquals(b.salt))
        assertFalse(a.iv.contentEquals(b.iv))
        assertFalse(a.data.contentEquals(b.data))
    }

    @Test fun nothingReadableInTheLockedFile() {
        val sealed = BackupCrypto.seal(payload, "correct-horse", rounds)
        val text = String(sealed.data, Charsets.ISO_8859_1)
        assertFalse(text.contains("live-login") || text.contains("liquid"))
    }
}
