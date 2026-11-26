package com.rutacomercial.app.domain

object ZoneColors {
    val palette = listOf("#6F42B5", "#C25818", "#087F75", "#BC3275", "#3370B0", "#718123", "#956137", "#476C80")
    fun next(used: List<String>): String = palette.minBy { color -> used.count { it.equals(color, true) } }
}
