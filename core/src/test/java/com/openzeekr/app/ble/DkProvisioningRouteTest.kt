package com.openzeekr.app.ble

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import retrofit2.http.POST

class DkProvisioningRouteTest {
    @Test
    fun `owner account uses only owner create route`() {
        assertEquals(DkCreateRoute.OWNER, createRouteFor(owner = true))
        assertEquals("create-owner-blu-key", createRouteFor(owner = true).path)
    }

    @Test
    fun `shared account uses only shared create route`() {
        assertEquals(DkCreateRoute.SHARED, createRouteFor(owner = false))
        assertEquals("create-share-key", createRouteFor(owner = false).path)
    }

    @Test
    fun `retrofit interface keeps owner and shared endpoints distinct`() {
        fun postPath(method: String): String = DkApi::class.java.methods
            .single { it.name == method }
            .getAnnotation(POST::class.java)
            .value

        assertEquals(
            "ms-tsp-dkbs-geely/api/v1.0/app/digital-key-center/create-owner-blu-key",
            postPath("createOwnerBluKey"),
        )
        assertEquals(
            "ms-tsp-dkbs-geely/api/v1.0/app/digital-key-center/create-share-key",
            postPath("createShareKey"),
        )
    }

    @Test
    fun `shared create body matches official RequestShareKeyBean fields`() {
        val body = Json.encodeToString(
            OwnerKeyReq(deviceId = "device", proprietary = "", signature = "signature"),
        )
        assertEquals(
            "{\"deviceId\":\"device\",\"proprietary\":\"\",\"signature\":\"signature\"}",
            body,
        )
    }
}
