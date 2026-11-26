package com.rutacomercial.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rutacomercial.app.maps.*

@Composable
internal fun ColumnScope.ZoneSummary(reports:List<WorkReport>, outline:List<MapCoordinate>, title:String,completed:Boolean=false, open:(WorkReport)->Unit) {
    val ordered=remember(reports) { reports.sortedBy { it.at } }
    val lines=remember(ordered) { ordered.flatMap { it.lines } }
    val marks=remember(ordered) { ordered.flatMap { it.marks } }
    val points=remember(lines,marks,outline) { outline+lines.flatMap { it.points }+marks.map { it.point } }
    Text(title+" · "+ordered.size+" jornadas recibidas",style=MaterialTheme.typography.titleMedium)
    TerritoryMap(Modifier.fillMaxWidth().weight(1f),tracks=lines,marks=marks,fitArea=points,
        sectors=if(outline.isEmpty()) emptyList() else listOf(MapSector("zone",title,if(completed) "#68B75F" else "#087F75",outline,true,completed)))
    Text(number(ordered.sumOf { it.meters }/1000,2)+" km · "+hours(ordered.sumOf { it.duration })+" en total")
    var list by remember { mutableStateOf(false) }
    FilledTonalButton(onClick={list=true},modifier=Modifier.fillMaxWidth()) { Text("Ver detalle por jornada") }
    if(list) AlertDialog(onDismissRequest={list=false},title={Text("Jornadas recibidas")},text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            ordered.forEachIndexed { index,r ->
                FilledTonalButton(modifier=Modifier.fillMaxWidth(),onClick={list=false;open(r)}) {
                    Text("J${index+1} · "+dateText(r.at)+" · "+r.worker+"\n"+number(r.meters/1000,2)+" km · "+when(r.state) {
                        "PAUSED"->"Avance en pausa"; "ACTIVE"->"Avance en curso"; else->"Terminada"
                    })
                }
            }
        }
    },confirmButton={TextButton(onClick={list=false}) { Text("Cerrar") }})
}
