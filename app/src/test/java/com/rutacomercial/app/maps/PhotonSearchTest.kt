package com.rutacomercial.app.maps

import org.junit.Assert.assertEquals
import org.junit.Test

class PhotonSearchTest {
    @Test fun readsAddressesAndPlaces() {
        val body = """{"features":[
          {"geometry":{"coordinates":[-68.8392,-32.8893]},"properties":{"housenumber":"1200","street":"Avenida General San Martín","city":"Mendoza"}},
          {"geometry":{"coordinates":[-68.845,-32.886]},"properties":{"name":"Plaza Independencia","city":"Mendoza"}},
          {"geometry":{"coordinates":[0,0]},"properties":{}}]}"""
        val places = PhotonSearch.parse(body)
        assertEquals(2, places.size)
        assertEquals("Avenida General San Martín 1200 · Mendoza", places[0].name)
        assertEquals(-32.8893, places[0].point.latitude, 1e-6)
        assertEquals("Plaza Independencia · Mendoza", places[1].name)
    }
}
