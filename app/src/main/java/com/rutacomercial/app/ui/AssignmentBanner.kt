package com.rutacomercial.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.rutacomercial.app.R

@Composable
internal fun AssignmentBanner(title:String, detail:String, large:Boolean) {
    val shape=MaterialTheme.shapes.large
    Row(
        Modifier.fillMaxWidth().clip(shape).background(
            Brush.linearGradient(listOf(Color(0xFF123C37), Color(0xFF206554)))
        ).heightIn(min=if(large) 156.dp else 62.dp).padding(if(large) 20.dp else 10.dp),
        horizontalArrangement=Arrangement.spacedBy(12.dp), verticalAlignment=Alignment.CenterVertically
    ) {
        Image(painterResource(R.drawable.ic_launcher),null,
            Modifier.size(if(large) 60.dp else 40.dp).clip(MaterialTheme.shapes.medium))
        Column {
            if(com.rutacomercial.app.BuildConfig.WORKER_EDITION) {
                Text("RutaComercial Repartidor",style=MaterialTheme.typography.labelLarge,color=Color(0xFFE0EDE6))
            }
            Text(title,style=if(large) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleLarge,color=Color.White)
            if(detail.isNotBlank()) Text(detail,style=MaterialTheme.typography.bodyMedium,color=Color(0xFFDFEEE6))
        }
    }
}
