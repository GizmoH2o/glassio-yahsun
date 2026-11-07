package com.glasslauncher.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/** Backups to a WebDAV folder (Nextcloud, Synology, Koofr…): names, the folder's listing, keeping the last ones, the errors. */
class WebDavTest {
    @Test fun `a backup's name sorts by when it was made`() {
        val zone = ZoneId.of("America/Jamaica")
        val a = WebDav.fileName(java.time.Instant.parse("2026-11-07T14:50:00Z").toEpochMilli(), zone)
        val b = WebDav.fileName(java.time.Instant.parse("2026-11-14T14:50:00Z").toEpochMilli(), zone)
        assertEquals("Glassio Yahsun backup 2026-11-07 0950.json", a)
        assertTrue(a < b)
    }

    @Test fun `the folder's address ends with a slash, a file's is encoded`() {
        assertEquals("https://cloud.example/remote.php/dav/files/me/Glassio/", WebDav.folder("https://cloud.example/remote.php/dav/files/me/Glassio"))
        assertEquals("https://cloud.example/dav/Glassio/", WebDav.folder("https://cloud.example/dav/Glassio/"))
        assertEquals("https://cloud.example/dav/Glassio/Glassio%20Yahsun%20backup%202026-11-07%200950.json",
            WebDav.file("https://cloud.example/dav/Glassio", "Glassio Yahsun backup 2026-11-07 0950.json"))
    }

    private val nextcloud = """<?xml version="1.0"?>
<d:multistatus xmlns:d="DAV:" xmlns:s="http://sabredav.org/ns" xmlns:oc="http://owncloud.org/ns">
 <d:response><d:href>/remote.php/dav/files/me/Glassio/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat></d:response>
 <d:response><d:href>/remote.php/dav/files/me/Glassio/Glassio%20Yahsun%20backup%202026-11-07%200950.json</d:href></d:response>
 <d:response><d:href>/remote.php/dav/files/me/Glassio/Glassio%20Yahsun%20backup%202026-10-31%200950.json</d:href></d:response>
 <d:response><d:href>/remote.php/dav/files/me/Glassio/notes.txt</d:href></d:response>
</d:multistatus>"""

    private val synology = """<?xml version="1.0" encoding="utf-8"?><D:multistatus xmlns:D="DAV:">
<D:response><D:href>https://nas.local:5006/home/Glassio/Glassio+Yahsun+backup+2026-11-01+0900.json</D:href></D:response>
<D:response><D:href>https://nas.local:5006/home/Glassio/Glassio Yahsun backup 2026-11-08 0900.json</D:href></D:response></D:multistatus>"""

    @Test fun `the backups in a folder's listing, newest first, other files left out`() {
        assertEquals(listOf("Glassio Yahsun backup 2026-11-07 0950.json", "Glassio Yahsun backup 2026-10-31 0950.json"), WebDav.names(nextcloud))
        assertEquals(listOf("Glassio Yahsun backup 2026-11-08 0900.json", "Glassio Yahsun backup 2026-11-01 0900.json"), WebDav.names(synology))
    }

    @Test fun `the last four are kept`() {
        val six = (1..6).map { "Glassio Yahsun backup 2026-11-0$it 0900.json" }
        assertEquals(listOf("Glassio Yahsun backup 2026-11-02 0900.json", "Glassio Yahsun backup 2026-11-01 0900.json"), WebDav.toDelete(six, 4))
        assertTrue(WebDav.toDelete(six.take(3), 4).isEmpty())
    }

    @Test fun `what went wrong, in words`() {
        assertTrue(WebDav.why(401).contains("username or password"))
        assertTrue(WebDav.why(404).contains("folder"))
        assertTrue(WebDav.why(507).contains("full"))
        assertTrue(WebDav.why(500).contains("500"))
    }
}
