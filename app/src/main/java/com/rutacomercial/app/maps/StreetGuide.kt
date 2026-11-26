package com.rutacomercial.app.maps

import com.rutacomercial.app.domain.Geo
import kotlin.math.*

internal data class StreetNode(val id: Long, val point: MapCoordinate)
internal data class GuideStreet(val name: String, val nodes: List<StreetNode>)
internal data class GuideStep(val street: String, val points: List<MapCoordinate>, val section: Int)
internal data class StreetGuide(val steps: List<GuideStep>, val streets: List<List<MapCoordinate>>, val doneLegs: Int = 0, val totalLegs: Int = 0)

private fun distance(a: MapCoordinate, b: MapCoordinate) =
    Geo.distanceM(a.latitude, a.longitude, b.latitude, b.longitude)

private data class Leg(val from: StreetNode, val to: StreetNode, val street: String) {
    private val dx = (to.point.longitude-from.point.longitude)*cos(Math.toRadians(from.point.latitude))
    private val dy = to.point.latitude-from.point.latitude
    private val angle = atan2(dx,dy)
    fun straightness(next: Leg) = cos(angle-next.angle)
}

/** Split even through concave boundaries. Only shared OSM node IDs connect two streets. */
private class ZoneClip(val zone: List<MapCoordinate>) {
    private val ring = zone.map { it.latitude to it.longitude }
    private var boundaryId = -1L
    private fun onBoundary(p:MapCoordinate):Boolean = zone.indices.any { i ->
        val a=zone[i]
        val b=zone[(i+1)%zone.size]
        val dx=b.longitude-a.longitude
        val dy=b.latitude-a.latitude
        val length2=dx*dx+dy*dy
        if(length2==0.0) false else {
            val t=(((p.longitude-a.longitude)*dx+(p.latitude-a.latitude)*dy)/length2).coerceIn(0.0,1.0)
            hypot(a.longitude+t*dx-p.longitude,a.latitude+t*dy-p.latitude)<1e-7
        }
    }
    fun inside(a: StreetNode, b: StreetNode): List<Pair<StreetNode,StreetNode>> {
        val p=a.point
        val dx=b.point.longitude-p.longitude
        val dy=b.point.latitude-p.latitude
        val cuts=mutableListOf(0.0,1.0)
        for(i in zone.indices) {
            val v=zone[i]
            val w=zone[(i+1)%zone.size]
            val sx=w.longitude-v.longitude
            val sy=w.latitude-v.latitude
            val det=dx*sy-dy*sx
            if(abs(det)<1e-16) continue
            val vx=v.longitude-p.longitude
            val vy=v.latitude-p.latitude
            val t=(vx*sy-vy*sx)/det
            val u=(vx*dy-vy*dx)/det
            if(t>1e-8 && t<1.0-1e-8 && u in 0.0..1.0) cuts.add(t)
        }
        fun point(t:Double)=MapCoordinate(p.latitude+dy*t,p.longitude+dx*t)
        fun node(t:Double)=when(t) { 0.0->a; 1.0->b; else->StreetNode(boundaryId--,point(t)) }
        return cuts.sorted().zipWithNext().mapNotNull { (start,end) ->
            val mid=point((start+end)/2)
            if(end-start<1e-8 || (!Geo.contains(ring,mid.latitude,mid.longitude) && !onBoundary(mid))) null else node(start) to node(end)
        }
    }
}

/**
 * Marks which directed legs a walked track already covered. Direction stands in for the sidewalk:
 * phone GPS (5-15 m) cannot tell sides apart, but it reliably tells which way the worker walked.
 */
internal class WalkedCoverage(tracks: List<List<MapCoordinate>>) {
    private data class Seg(val ax: Double, val ay: Double, val bx: Double, val by: Double)
    private val lat0 = tracks.firstOrNull { it.isNotEmpty() }?.first()?.latitude ?: 0.0
    private val kx = 111_320.0 * cos(Math.toRadians(lat0))
    private val ky = 110_540.0
    private val cell = 30.0
    private val grid = HashMap<Long, MutableList<Seg>>()
    private fun x(p: MapCoordinate) = p.longitude * kx
    private fun y(p: MapCoordinate) = p.latitude * ky
    private fun key(cx: Long, cy: Long) = cx * 2_000_003L + cy
    init {
        for (track in tracks) for ((a, b) in track.zipWithNext()) {
            val seg = Seg(x(a), y(a), x(b), y(b))
            if (hypot(seg.bx - seg.ax, seg.by - seg.ay) < 0.5) continue
            val cx = floor((seg.ax + seg.bx) / 2 / cell).toLong(); val cy = floor((seg.ay + seg.by) / 2 / cell).toLong()
            grid.getOrPut(key(cx, cy)) { mutableListOf() }.add(seg)
        }
    }
    private fun near(px: Double, py: Double, dx: Double, dy: Double): Boolean {
        val cx = floor(px / cell).toLong(); val cy = floor(py / cell).toLong()
        for (i in -1..1) for (j in -1..1) for (s in grid[key(cx + i, cy + j)].orEmpty()) {
            val sx = s.bx - s.ax; val sy = s.by - s.ay; val len2 = sx * sx + sy * sy
            val t = (((px - s.ax) * sx + (py - s.ay) * sy) / len2).coerceIn(0.0, 1.0)
            if (hypot(s.ax + t * sx - px, s.ay + t * sy - py) > 20.0) continue
            if ((sx * dx + sy * dy) / sqrt(len2) > 0.5) return true
        }
        return false
    }
    /** Covered when at least 60 % of points along the leg have a track nearby going the same way. */
    fun covers(from: MapCoordinate, to: MapCoordinate): Boolean {
        if (grid.isEmpty()) return false
        val ax = x(from); val ay = y(from); val bx = x(to); val by = y(to)
        val length = hypot(bx - ax, by - ay)
        if (length < 1.0) return false
        val dx = (bx - ax) / length; val dy = (by - ay) / length
        val samples = (length / 10).toInt().coerceIn(2, 40)
        val hits = (0..samples).count { i -> val t = i.toDouble() / samples; near(ax + t * (bx - ax), ay + t * (by - ay), dx, dy) }
        return hits >= 0.6 * (samples + 1)
    }
}

internal fun planStreetGuide(streets: List<GuideStreet>, zone: List<MapCoordinate>, origin: MapCoordinate,
    walked: List<List<MapCoordinate>> = emptyList()): StreetGuide {
    val coverage = WalkedCoverage(walked)
    var total = 0
    var done = 0
    val clip=ZoneClip(zone)
    val adjacent=mutableMapOf<StreetNode,MutableList<Leg>>()
    val seen=mutableSetOf<Pair<Long,Long>>()
    val outlines=mutableListOf<List<MapCoordinate>>()
    for(street in streets) for((start,end) in street.nodes.zipWithNext()) {
        if(!seen.add(minOf(start.id,end.id) to maxOf(start.id,end.id))) continue
        for((a,b) in clip.inside(start,end)) {
            if(distance(a.point,b.point)<0.1) continue
            adjacent.getOrPut(a){mutableListOf()}.add(Leg(a,b,street.name))
            adjacent.getOrPut(b){mutableListOf()}.add(Leg(b,a,street.name))
            outlines.add(listOf(a.point,b.point))
        }
    }
    // An Euler circuit visits each street segment once in each direction.
    // Prefer staying on the same street. A disconnected part starts a separate walk.
    val steps=mutableListOf<GuideStep>()
    var section=0
    for(start in adjacent.keys.sortedBy { distance(it.point,origin) }) {
        if(adjacent.getValue(start).isEmpty()) continue
        section++
        val stack=ArrayDeque<Leg>()
        val reversed=mutableListOf<Leg>()
        var node=start
        while(true) {
            val choices=adjacent.getValue(node)
            val last=stack.lastOrNull()
            if(choices.isNotEmpty()) {
                val next=if(last==null) choices.first() else choices.maxBy {
                    last.straightness(it) + (if(last.street==it.street) 2 else 0) - (if(it.to==last.from) 5 else 0)
                }
                choices.remove(next)
                stack.addLast(next)
                node=next.to
            } else {
                val back=stack.removeLastOrNull() ?: break
                reversed.add(back)
                node=back.from
            }
        }
        var previous:Leg?=null
        var points=mutableListOf<MapCoordinate>()
        for(leg in reversed.asReversed()) {
            total++
            if(coverage.covers(leg.from.point,leg.to.point)) {
                done++
                previous?.let { steps.add(GuideStep(it.street,points,section)) }
                previous=null
                continue
            }
            val prior=previous
            if(prior==null || prior.street!=leg.street || prior.straightness(leg)<0.45) {
                if(prior!=null) steps.add(GuideStep(prior.street,points,section))
                points=mutableListOf(leg.from.point)
            }
            points.add(leg.to.point)
            previous=leg
        }
        previous?.let { steps.add(GuideStep(it.street,points,section)) }
    }
    return StreetGuide(steps,outlines,done,total)
}
