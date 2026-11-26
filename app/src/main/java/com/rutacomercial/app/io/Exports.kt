package com.rutacomercial.app.io

import com.google.gson.Gson
import com.rutacomercial.app.data.DataSnapshot
import com.rutacomercial.app.data.VisitStatus
import java.time.Instant

object Exports {
    /** Quotes every cell and neutralizes spreadsheet formulas, including control-prefix variants. */
    fun csvCell(value: Any?): String {
        var text = value?.toString().orEmpty()
        if (text.trimStart().firstOrNull() in listOf('=', '+', '-', '@') || text.startsWith('\t') || text.startsWith('\r')) text = "'" + text
        return "\"${text.replace("\"", "\"\"")}\""
    }
    internal fun csv(rows: List<List<Any?>>) = "\uFEFF" + rows.joinToString("\r\n") { it.joinToString(";", transform = ::csvCell) } + "\r\n"
    private fun date(at: Long?) = at?.let { Instant.ofEpochMilli(it).toString() }.orEmpty()

    fun commercialCsv(data: DataSnapshot): String {
        val rows = mutableListOf<List<Any?>>(listOf("Tipo", "Negocio_ID", "Nombre", "Categoría", "Dirección", "Barrio", "Sector", "Estado", "Visita_UTC", "Campaña", "Tarjetas_declaradas", "Comentario", "Latitud", "Longitud", "Teléfono", "Próximo_contacto_UTC", "Revisión"))
        for (business in data.visibleBusinesses.sortedBy { it.name }) {
            val visits = data.visits.filter { it.businessId == business.id }.sortedBy { it.at }
            if (visits.isEmpty()) rows += listOf("Establecimiento", business.id, business.name, business.category, business.address, business.neighborhood, data.sectors.find { it.id == business.sectorId }?.name, VisitStatus.labelOf(business.status), "", "", 0, business.notes, business.latitude, business.longitude, business.phone, date(business.nextContactAt), "Sin visita")
            for (visit in visits) rows += listOf("Visita declarada", business.id, business.name, business.category, business.address, business.neighborhood, data.sectors.find { it.id == visit.sectorId }?.name, VisitStatus.labelOf(visit.status), date(visit.at), data.campaigns.find { it.id == visit.campaignId }?.name, visit.cards, visit.comments, business.latitude, business.longitude, business.phone, date(business.nextContactAt), visit.reviewState)
        }
        for (q in data.quickDeliveries.sortedBy { it.at }) {
            val business = data.businesses.find { it.id == q.businessId }
            rows += listOf("Entrega a un toque", q.businessId, business?.name ?: "Sin identificar", "", "", "", data.sectors.find { it.id == q.sectorId }?.name,
                if (q.undoneAt == null) "Declarada" else "Anulada", date(q.at), data.campaigns.find { it.id == q.campaignId }?.name,
                if (q.undoneAt == null) 1 else 0, "ID=${q.id}; precisión=${q.accuracyMeters}; GPS=${date(q.fixAt)}; anulación=${date(q.undoneAt)}", q.latitude, q.longitude, "", "", "DECLARED")
        }
        return csv(rows)
    }

    fun inventoryCsv(data: DataSnapshot): String = csv(listOf(listOf("Movimiento_ID", "Operación_ID", "Campaña", "Tipo", "Cantidad", "Fecha_UTC", "Jornada_ID", "Visita_ID", "Nota")) + data.movements.sortedBy { it.at }.map { movement ->
        listOf(movement.id, movement.operationId, data.campaigns.find { it.id == movement.campaignId }?.name, movement.type, movement.quantity, date(movement.at), movement.journeyId, movement.visitId, movement.note)
    })

    private fun xml(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")

    fun gpx(data: DataSnapshot, journeyId: String? = null): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<gpx version=\"1.1\" creator=\"RutaComercial\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        data.journeys.filter { journeyId == null || it.id == journeyId }.forEach { journey ->
            append("<trk><name>Jornada ${xml(date(journey.startedAt))}</name><desc>Trayecto GPS observado; no acredita entregas.</desc>\n")
            data.points.filter { it.journeyId == journey.id }.sortedBy { it.at }.groupBy { it.segment }.values.forEach { segment ->
                append("<trkseg>\n")
                segment.forEach { p -> append("<trkpt lat=\"${p.latitude}\" lon=\"${p.longitude}\"><time>${xml(date(p.at))}</time></trkpt>\n") }
                append("</trkseg>\n")
            }
            append("</trk>\n")
        }
        append("</gpx>\n")
    }

    fun geoJson(data: DataSnapshot, journeyId: String? = null): String {
        val features = data.journeys.filter { journeyId == null || it.id == journeyId }.flatMap { journey ->
            data.points.filter { it.journeyId == journey.id }.sortedBy { it.at }.groupBy { it.segment }.map { (segment, points) ->
                val geometry = if (points.size == 1) mapOf("type" to "Point", "coordinates" to listOf(points[0].longitude, points[0].latitude))
                else mapOf("type" to "LineString", "coordinates" to points.map { listOf(it.longitude, it.latitude) })
                mapOf("type" to "Feature", "properties" to mapOf("journey_id" to journey.id, "segment" to segment, "started_at" to date(points.first().at), "ended_at" to date(points.last().at), "evidence" to "observed_gps_not_delivery"), "geometry" to geometry)
            }
        }
        return Gson().toJson(mapOf("type" to "FeatureCollection", "features" to features))
    }
}
