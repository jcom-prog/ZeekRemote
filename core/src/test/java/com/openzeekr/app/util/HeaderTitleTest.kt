package com.openzeekr.app.util

import org.junit.Assert.assertEquals
import org.junit.Test

class HeaderTitleTest {
    @Test fun aHandTypedVersionIsReplacedByTheInstalledOne() {
        assertEquals("ZeekRemote 0.1.62", HeaderTitle.title("ZeekRemote 1.1.60", "0.1.62-work"))
        assertEquals("ZeekRemote 0.1.62", HeaderTitle.title("ZeekRemote v1.1.60-work ", "0.1.62-work"))
    }

    @Test fun aNameWithoutVersionGetsTheVersionAppended() {
        assertEquals("My 7GT 0.1.62", HeaderTitle.title("My 7GT", "0.1.62-work"))
        assertEquals("My Zeekr 0.1.62", HeaderTitle.title("", "0.1.62-work"))
        assertEquals("ZeekRemote 0.1.62", HeaderTitle.title("ZeekRemote", "0.1.62"))
    }

    @Test fun numbersInsideTheNameStayAndAMissingVersionShowsTheNameOnly() {
        assertEquals("Zeekr 7GT 0.1.62", HeaderTitle.title("Zeekr 7GT", "0.1.62-work"))
        assertEquals("Zeekr 001", HeaderTitle.title("Zeekr 001", null))
        assertEquals("ZeekRemote", HeaderTitle.baseName("ZeekRemote 1.1.60"))
    }
}
