package net.boswell.phone

import net.boswell.phone.home.HomeServer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeTroubleTest {
    @Test fun a_tailnet_address_is_known_by_its_name_or_its_range() {
        assertTrue(HomeServer.onTailnet("https://home.example-tailnet.ts.net"))
        assertTrue(HomeServer.onTailnet("http://100.94.208.83:8765"))
        assertTrue(HomeServer.onTailnet("http://100.127.0.1:8765"))
        assertFalse(HomeServer.onTailnet("http://100.128.0.1:8765"))
        assertFalse(HomeServer.onTailnet("http://192.168.1.20:8765"))
        assertFalse(HomeServer.onTailnet(null))
    }

    @Test fun a_handshake_cut_off_points_at_tailscale() {
        // 10-11: Tailscale off, the HTTPS address opened and closed in the handshake.
        assertTrue("Tailscale" in HomeServer.explain("SSLHandshakeException: connection closed"))
        assertTrue("Tailscale" in HomeServer.explain("Unable to resolve host \"home.example-tailnet.ts.net\""))
        assertTrue("Boswell Server running" in HomeServer.explain("Failed to connect to /100.94.208.83:8765"))
    }
}
