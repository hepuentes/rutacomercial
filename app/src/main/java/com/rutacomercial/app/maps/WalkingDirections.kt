package com.rutacomercial.app.maps

import com.rutacomercial.app.domain.Geo
import kotlin.math.*

internal fun metersBetween(a:MapCoordinate,b:MapCoordinate)=Geo.distanceM(a.latitude,a.longitude,b.latitude,b.longitude)

internal fun nearestOnLeg(a:MapCoordinate,b:MapCoordinate,p:MapCoordinate):MapCoordinate {
    val scale=cos(Math.toRadians(p.latitude))
    val dx=(b.longitude-a.longitude)*scale
    val dy=b.latitude-a.latitude
    val length=dx*dx+dy*dy
    val dot=(p.longitude-a.longitude)*scale*dx+(p.latitude-a.latitude)*dy
    val t=if(length<1e-18) 0.0 else (dot/length).coerceIn(0.0,1.0)
    return MapCoordinate(a.latitude+t*dy,a.longitude+t*(b.longitude-a.longitude))
}

internal fun zoneEntrance(polygon:List<MapCoordinate>,p:MapCoordinate):MapCoordinate? =
    polygon.indices.map { i->nearestOnLeg(polygon[i],polygon[(i+1)%polygon.size],p) }.minByOrNull { metersBetween(it,p) }

internal data class StepProgress(val remaining:Double,val offRoute:Double,val advance:Boolean)
internal class StepFollower(private val path:List<MapCoordinate>) {
    private val lengths=path.zipWithNext(::metersBetween)
    private val total=lengths.sum()
    private var firstAlong:Double?=null
    fun sample(here:MapCoordinate,accuracy:Float?):StepProgress? {
        if(lengths.isEmpty() || accuracy==null || accuracy !in 0f..25f) return null
        var walked=0.0
        var nearest=Double.POSITIVE_INFINITY
        var along=0.0
        for(i in lengths.indices) {
            val candidate=nearestOnLeg(path[i],path[i+1],here)
            val distance=metersBetween(here,candidate)
            if(distance<nearest) { nearest=distance;along=walked+metersBetween(path[i],candidate) }
            walked+=lengths[i]
        }
        val remaining=(total-along).coerceAtLeast(0.0)
        if(nearest>maxOf(15.0,accuracy.toDouble())) return StepProgress(remaining,nearest,false)
        val previous=firstAlong
        firstAlong=minOf(previous ?: along,along)
        val moved=previous!=null && along-previous>=minOf(12.0,total*.45)
        val arrived=moved && metersBetween(here,path.last())<=minOf(12.0,maxOf(3.0,total*.28))
        return StepProgress(remaining,nearest,arrived)
    }
}

internal fun turnArrow(current:GuideStep,next:GuideStep?):String {
    if(next==null) return "⚑"
    if(current.section!=next.section) return "⚑"
    fun bearing(a:MapCoordinate,b:MapCoordinate)=Math.toDegrees(atan2((b.longitude-a.longitude)*cos(Math.toRadians(a.latitude)),b.latitude-a.latitude))
    val a=current.points
    val b=next.points
    val delta=(bearing(b.first(),b[1])-bearing(a[a.lastIndex-1],a.last())+540)%360-180
    return when { abs(delta)>=145 -> "↶";delta>=35 -> "↱";delta<=-35 -> "↰";else -> "↑" }
}
