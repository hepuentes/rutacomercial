package com.rutacomercial.app.domain

import com.google.gson.Gson
import com.rutacomercial.app.data.*
import java.text.Normalizer
import java.util.Locale
import kotlin.math.*

object Names {
    fun normalize(text: String): String = Normalizer.normalize(text.trim(), Normalizer.Form.NFD).replace(Regex("\\p{M}"), "").lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), " ").trim()
    fun similarity(a: String, b: String): Double {
        val x=normalize(a); val y=normalize(b)
        if(x==y) return 1.0
        if(x.isEmpty() || y.isEmpty()) return 0.0
        var previous=IntArray(y.length+1){it}
        for(i in 1..x.length) { val current=IntArray(y.length+1); current[0]=i
            for(j in 1..y.length) current[j]=minOf(current[j-1]+1, previous[j]+1, previous[j-1]+if(x[i-1]==y[j-1])0 else 1)
            previous=current
        }
        return 1.0-previous[y.length].toDouble()/max(x.length,y.length)
    }
}

object Geo {
    fun valid(latitude: Double, longitude: Double): Boolean = latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0
    fun distanceM(lat1:Double,lon1:Double,lat2:Double,lon2:Double): Double {
        val dLat=Math.toRadians(lat2-lat1); val dLon=Math.toRadians(lon2-lon1)
        val a=sin(dLat/2).pow(2)+cos(Math.toRadians(lat1))*cos(Math.toRadians(lat2))*sin(dLon/2).pow(2)
        return 6371008.8*2*atan2(sqrt(a.coerceIn(0.0,1.0)),sqrt((1-a).coerceIn(0.0,1.0)))
    }
    fun parsePolygon(json:String): List<Pair<Double,Double>> = runCatching {
        Gson().fromJson(json, Array<DoubleArray>::class.java).map { require(it.size==2); require(valid(it[0],it[1])); it[0] to it[1] }
    }.getOrElse { throw IllegalArgumentException("El polígono contiene coordenadas inválidas") }
    fun polygonJson(points:List<Pair<Double,Double>>):String = Gson().toJson(points.map { doubleArrayOf(it.first,it.second) })
    fun contains(polygon:List<Pair<Double,Double>>,lat:Double,lon:Double):Boolean {
        var inside=false; var j=polygon.lastIndex
        for(i in polygon.indices) { val a=polygon[i]; val b=polygon[j]
            if((a.first>lat)!=(b.first>lat) && lon<(b.second-a.second)*(lat-a.first)/(b.first-a.first)+a.second) inside=!inside
            j=i
        }; return inside
    }
    fun validPolygon(points:List<Pair<Double,Double>>):Boolean {
        if(points.size<3 || points.size>1000 || points.distinct().size<3 || points.any{!valid(it.first,it.second)}) return false
        var area=0.0
        for(i in points.indices) { val a=points[i]; val b=points[(i+1)%points.size]; area += a.first*b.second-b.first*a.second }
        if(abs(area)<1e-10) return false
        fun cross(a:Pair<Double,Double>,b:Pair<Double,Double>,c:Pair<Double,Double>)=(b.first-a.first)*(c.second-a.second)-(b.second-a.second)*(c.first-a.first)
        for(i in points.indices) for(j in i+1 until points.size) {
            if(j==i+1 || i==0 && j==points.lastIndex) continue
            val a=points[i]; val b=points[(i+1)%points.size]; val c=points[j]; val d=points[(j+1)%points.size]
            if(cross(a,b,c)*cross(a,b,d)<0 && cross(c,d,a)*cross(c,d,b)<0) return false
        }
        return true
    }
}

data class GpsFix(val latitude:Double,val longitude:Double,val accuracyMeters:Float,val at:Long=System.currentTimeMillis(),val elapsedRealtimeNanos:Long,val isMock:Boolean=false)
data class GpsDecision(val accepted:Boolean,val reason:String,val breakSegment:Boolean=false,val distanceM:Double=0.0)
object GpsPolicy {
    const val MAX_ACCURACY_M=50f
    const val MAX_GAP_MS=60_000L
    const val MAX_SPEED_MPS=8.0
    fun evaluate(previous:GpsFix?,current:GpsFix,signalContinuous:Boolean=false):GpsDecision {
        if(!Geo.valid(current.latitude,current.longitude) || current.at<=0 || current.elapsedRealtimeNanos<=0) return GpsDecision(false,"Punto GPS inválido",true)
        if(!current.accuracyMeters.isFinite() || current.accuracyMeters<=0 || current.accuracyMeters>MAX_ACCURACY_M) return GpsDecision(false,"Precisión GPS insuficiente",true)
        if(previous==null) return GpsDecision(true,if(current.isMock) "Ubicación simulada declarada por Android" else "Inicio de segmento",true)
        val elapsedMs=(current.elapsedRealtimeNanos-previous.elapsedRealtimeNanos)/1_000_000
        if(elapsedMs<=0 || current.at<=previous.at) return GpsDecision(false,"Punto repetido o fuera de orden")
        if(abs((current.at-previous.at)-elapsedMs)>10_000 || elapsedMs>MAX_GAP_MS && !signalContinuous) return GpsDecision(true,"Señal recuperada: nuevo segmento",true)
        val distance=Geo.distanceM(previous.latitude,previous.longitude,current.latitude,current.longitude)
        if(distance/(elapsedMs/1000.0)>MAX_SPEED_MPS && distance>max(20.0,(previous.accuracyMeters+current.accuracyMeters).toDouble())) return GpsDecision(false,"Salto de ubicación incompatible con un recorrido a pie",true)
        if(distance<max(3.0,min(previous.accuracyMeters,current.accuracyMeters)*0.3)) return GpsDecision(false,"Sin desplazamiento significativo")
        return GpsDecision(true,if(current.isMock) "Ubicación simulada declarada por Android" else "Punto registrado",false,distance)
    }
}

object Inventory {
    fun balance(movements:List<StockMovement>):StockBalance {
        var depot=0L; var carried=0L; var delivered=0L; var damaged=0L
        val operations=HashSet<String>()
        for(m in movements) {
            require(m.quantity>0){"La cantidad debe ser mayor a cero"}
            require(operations.add(m.operationId)){"Movimiento repetido"}
            val q=m.quantity.toLong()
            when(m.type) {
                "INITIAL","ENTRY" -> depot+=q
                "ASSIGN" -> { depot-=q; carried+=q }
                "DELIVERY", "QUICK_DELIVERY" -> { carried-=q; delivered+=q }
                "QUICK_UNDO" -> { carried+=q; delivered-=q }
                "DAMAGE" -> { carried-=q; damaged+=q }
                "RETURN" -> { carried-=q; depot+=q }
                else -> throw IllegalArgumentException("Tipo de movimiento desconocido")
            }
            require(depot>=0 && carried>=0 && delivered>=0){"Stock insuficiente. Revise las tarjetas en depósito y las asignadas para repartir"}
        }
        return StockBalance(depot,carried,delivered,damaged)
    }
}
