package com.arcticfoxapps.peeriche

import org.junit.Assert.*
import org.junit.Test

class PeerTubeValidationTest {
    @Test fun acceptsEmptyPeerTubeAndOrdinaryVideoPages() {
        assertTrue(parsePeerTubeVideoPage("""{"total":0,"data":[]}""").data.isEmpty())
        val page = parsePeerTubeVideoPage("""{"total":2,"data":[{"uuid":"id","name":"Video"}]}""")
        assertEquals(2, page.total); assertEquals("id", page.data.single().uuid)
    }
    @Test fun rejectsSuccessfulHtmlWebsitesAndUnrelatedJson() {
        for (body in listOf("<!doctype html><html>Odysee</html>", "{}", "{\"error\":\"no API\"}", "{\"data\":null,\"total\":0}", "{\"data\":[],\"total\":\"zero\"}")) {
            assertThrows(NotPeerTubeServerException::class.java) { parsePeerTubeVideoPage(body) }
        }
    }
    @Test fun rejectsMalformedRowsInsteadOfAcceptingAWebsiteAsAServer() {
        assertThrows(NotPeerTubeServerException::class.java) { parsePeerTubeVideoPage("""{"data":[{"title":"news"}],"total":1}""") }
        assertThrows(NotPeerTubeServerException::class.java) { parsePeerTubeVideoPage("""{"data":[null],"total":1}""") }
    }
}
