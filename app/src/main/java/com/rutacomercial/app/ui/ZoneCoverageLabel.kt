package com.rutacomercial.app.ui

import androidx.compose.runtime.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.rutacomercial.app.data.TrackPoint
import com.rutacomercial.app.domain.ZoneCoverage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun ZoneCoverageLabel(boundary:List<Pair<Double,Double>>, points:List<TrackPoint>, journeyId:String) {
    val percent by produceState<Int?>(null,boundary,points,journeyId) {
        value=withContext(Dispatchers.Default) {
            ZoneCoverage.percent(boundary,points.filter { it.journeyId==journeyId }.map { it.latitude to it.longitude })
        }
    }
    Text(percent?.let { "Cuadrícula de 50 m · $it % de celdas con puntos GPS" } ?: "Cobertura de cuadrícula sin estimación",style=MaterialTheme.typography.labelSmall)
}
