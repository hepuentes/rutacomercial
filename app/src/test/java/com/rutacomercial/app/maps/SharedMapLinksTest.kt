package com.rutacomercial.app.maps

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class SharedMapLinksTest {
    @Test fun sharesWithNamesCoordinatesAndGeoPinsResolve() {
        val resolver = SharedMapResolver()
        assertEquals(MapCoordinate(-32.89, -68.84), resolver.resolve("Mi lugar\nhttps://www.google.com/maps?q=-32.89%2C-68.84").point)
        assertEquals(MapCoordinate(-32.89, -68.84), resolver.resolve("-32.89, -68.84").point)
        assertEquals(MapCoordinate(-32.89, -68.84), resolver.resolve("geo:0,0?q=-32.89,-68.84(Centro)").point)
        assertNull(SharedMapLinks.parse("geo:0,0?q=Centro+Mendoza"))
    }
    @Test fun selectedPlaceTakesPrecedenceOverDifferentViewport() {
        val place = SharedMapLinks.parse("https://www.google.com/maps/place/Test/@-34.60,-58.38,17z/data=!4m2!3d-32.89!4d-68.84")!!
        assertEquals(MapCoordinate(-32.89, -68.84), place.point)
        assertFalse(place.approximate)
        assertNull(SharedMapLinks.parse("https://www.google.com/maps/place/Test/@-34.60,-58.38,17z"))
        assertTrue(SharedMapLinks.parse("https://www.google.com/maps/@-34.60,-58.38,17z")!!.approximate)
    }
    @Test fun ambiguityInvalidCoordinatesDirectionsAndNonMapsLinksAreRejected() {
        listOf("91,0", "0,-181", "NaN,1", "https://www.google.com/maps/dir/?origin=1,2&destination=3,4",
            "https://evil.example/maps?q=1,2", "https://www.google.com.evil.example/maps?q=1,2",
            "https://google.com@evil.example/maps?q=1,2", "http://www.google.com/maps?q=1,2",
            "https://www.google.com:8443/maps?q=1,2", "https://www.google.com/maps/data=!3d1!4d2!3d3!4d4",
            "https://www.google.com/maps?q=Address&ll=1,2").forEach { assertNull(it, SharedMapLinks.parse(it)) }
        assertThrows(IllegalArgumentException::class.java) { SharedMapLinks.input("https://maps.app.goo.gl/a https://maps.app.goo.gl/b") }
    }
    @Test fun shortLinkFollowsOnlyAllowedRedirectsAndDoesNotReadHtml() {
        var requests = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(302).message("Found")
                .header("Location", "https://www.google.com/maps?q=-32.89,-68.84").body("".toResponseBody()).build()
        }.build()
        assertEquals(MapCoordinate(-32.89,-68.84), SharedMapResolver(client).resolve("https://maps.app.goo.gl/example").point)
        assertEquals(1, requests)
    }
    @Test fun redirectToAnotherHostIsNeverFetched() {
        var requests = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(302).message("Found")
                .header("Location", "https://127.0.0.1/private").body("".toResponseBody()).build()
        }.build()
        assertThrows(IllegalArgumentException::class.java) { SharedMapResolver(client).resolve("https://maps.app.goo.gl/example") }
        assertEquals(1, requests)
    }
    @Test fun shortLinkWithoutCoordinatesOrWithRedirectLoopGivesActionableError() {
        listOf(200, 302).forEach { code ->
            var requests = 0
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                requests++
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("Response")
                    .header("Location", "https://maps.app.goo.gl/loop").body("HTML is not parsed".toResponseBody()).build()
            }.build()
            val error = assertThrows(IllegalArgumentException::class.java) { SharedMapResolver(client).resolve("https://maps.app.goo.gl/loop") }
            assertTrue(error.message!!.contains("pin"))
            assertEquals(1, requests)
        }
    }
}
