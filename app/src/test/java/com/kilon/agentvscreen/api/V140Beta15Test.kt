package com.kilon.agentvscreen.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 1.4.0-beta15: who holds a port, read from the kernel's socket table. */
class V140Beta15Test {
    private val table = """
  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode
   0: 0100007F:494D 00000000:0000 0A 00000000:00000000 00:00000000 00000000 1099912        0 12345 1 0000000000000000 100 0 0 10 0
   1: 0100007F:494E 0100007F:B2C4 01 00000000:00000000 00:00000000 00000000 10234        0 12346 1 0000000000000000 20 4 30 10 -1
"""

    @Test fun listeningUidIsFound() = assertEquals(1099912, PortHolder.uidIn(table, 18765))

    @Test fun aConnectionIsNotAListener() = assertNull(PortHolder.uidIn(table, 18766))

    @Test fun nobodyOnAnotherPort() = assertNull(PortHolder.uidIn(table, 18770))
}
