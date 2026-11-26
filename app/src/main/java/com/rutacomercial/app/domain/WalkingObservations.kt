package com.rutacomercial.app.domain

import kotlin.math.*

/** Consumes fresh observations before the walking trace rejects jumps or suppresses stillness. */
class WalkingObservations {
    private val window = ArrayDeque<GpsFix>()
    private var stopAnchor: GpsFix? = null
    private var countedStop = false
    private var fast = false
    fun reset() { window.clear(); stopAnchor=null; countedStop=false; fast=false }
    fun accept(f: GpsFix): String? {
        if (!Geo.valid(f.latitude,f.longitude) || !f.accuracyMeters.isFinite() || f.accuracyMeters !in 0.01f..15f) { reset(); return null }
        fun distance(a:GpsFix,b:GpsFix) = Geo.distanceM(a.latitude,a.longitude,b.latitude,b.longitude)
        val previous=window.lastOrNull()
        if(previous != null && (f.elapsedRealtimeNanos-previous.elapsedRealtimeNanos !in 1..15_000_000_000L)) reset()
        window.addLast(f)
        while(window.size>1 && f.elapsedRealtimeNanos-window.first().elapsedRealtimeNanos>45_000_000_000L) window.removeFirst()
        val anchor=stopAnchor
        if(anchor==null || distance(anchor,f)>15) { stopAnchor=f; countedStop=false }
        else if(!countedStop && f.elapsedRealtimeNanos-anchor.elapsedRealtimeNanos>=30_000_000_000L) {
            countedStop=true; return "WALK_STOP"
        }
        if(window.size<4) return null
        val first=window.first()
        val seconds=(f.elapsedRealtimeNanos-first.elapsedRealtimeNanos)/1e9
        if(seconds<30) return null
        val mid=window.elementAt(window.size/2)
        fun lowerSpeed(a:GpsFix,b:GpsFix)=(distance(a,b)-a.accuracyMeters-b.accuracyMeters)/((b.elapsedRealtimeNanos-a.elapsedRealtimeNanos)/1e9)
        val sustained=lowerSpeed(first,f)>12/3.6 && lowerSpeed(first,mid)>2.5 && lowerSpeed(mid,f)>2.5
        if(sustained && !fast) { fast=true; return "FAST_INTERVAL" }
        if(lowerSpeed(first,f)<2) fast=false
        return null
    }
}

/** Share of sampled 50 m cells in the selected polygon that contain at least one accepted GPS point. */
object ZoneCoverage {
    fun percent(polygon:List<Pair<Double,Double>>, points: List<Pair<Double,Double>>):Int? {
        if(polygon.size<3 || points.isEmpty()) return null
        val south=polygon.minOf { it.first }; val west=polygon.minOf { it.second }
        val latStep=50.0/111_320
        val lonStep=latStep/cos(Math.toRadians(south)).coerceAtLeast(.01)
        val height=ceil((polygon.maxOf { it.first }-south)/latStep).toInt()
        val width=ceil((polygon.maxOf { it.second }-west)/lonStep).toInt()
        if(width.toLong()*height !in 1..20_000) return null
        val visited=points.filter { Geo.contains(polygon,it.first,it.second) }.map {
            floor((it.first-south)/latStep).toInt() to floor((it.second-west)/lonStep).toInt()
        }.toSet()
        var total=0; var seen=0
        for(y in 0 until height) for(x in 0 until width) {
            if(Geo.contains(polygon,south+(y+.5)*latStep,west+(x+.5)*lonStep)) { total++; if(y to x in visited) seen++ }
        }
        return if(total==0) null else (100.0*seen/total).roundToInt()
    }
}
