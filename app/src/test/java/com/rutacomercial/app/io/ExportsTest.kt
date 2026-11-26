package com.rutacomercial.app.io

import com.google.gson.JsonParser
import com.rutacomercial.app.data.*
import org.junit.Assert.*
import org.junit.Test
import javax.xml.parsers.DocumentBuilderFactory

class ExportsTest {
    @Test fun exportsPreserveGpsGaps() {
        val journey = Journey(startedAt = 1000, endedAt = 20_000, state = "FINISHED", nextSegment = 1)
        val points = listOf(
            TrackPoint(journeyId = journey.id, latitude = -34.6, longitude = -58.4, accuracyMeters = 5f, at = 1000, elapsedRealtimeNanos = 1000, segment = 0),
            TrackPoint(journeyId = journey.id, latitude = -34.6001, longitude = -58.4001, accuracyMeters = 5f, at = 5000, elapsedRealtimeNanos = 5000, segment = 0),
            TrackPoint(journeyId = journey.id, latitude = -34.61, longitude = -58.41, accuracyMeters = 5f, at = 20_000, elapsedRealtimeNanos = 20_000, segment = 1)
        )
        val snapshot = DataSnapshot(journeys = listOf(journey), points = points)
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(Exports.gpx(snapshot).byteInputStream())
        assertEquals(2, document.getElementsByTagName("trkseg").length)
        assertEquals(3, document.getElementsByTagName("trkpt").length)
        val features = JsonParser.parseString(Exports.geoJson(snapshot)).asJsonObject.getAsJsonArray("features")
        assertEquals(2, features.size())
        assertEquals("LineString", features[0].asJsonObject.getAsJsonObject("geometry").get("type").asString)
        assertEquals("Point", features[1].asJsonObject.getAsJsonObject("geometry").get("type").asString)
    }
    @Test fun emptyExportsRemainValid() {
        assertTrue(Exports.commercialCsv(DataSnapshot()).startsWith("\uFEFF"))
        assertEquals(0, JsonParser.parseString(Exports.geoJson(DataSnapshot())).asJsonObject.getAsJsonArray("features").size())
    }
}
