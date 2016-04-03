/*
 * Copyright 2015 Distributed Ledger Group LLC.  Distributed as Licensed Company IP to DLG Group Members
 * pursuant to the August 7, 2015 Advisory Services Agreement and subject to the Company IP License terms
 * set forth therein.
 *
 * All other rights reserved.
 */

package core.node.services

import core.Party
import core.crypto.DummyPublicKey
import core.messaging.SingleMessageRecipient
import java.util.*

/** Info about a network node that has operated by some sort of verified identity. */
data class LegallyIdentifiableNode(val address: SingleMessageRecipient, val identity: Party,
                                   val physicalLocation: PhysicalLocation? = null)

/**
 * A network map contains lists of nodes on the network along with information about their identity keys, services
 * they provide and host names or IP addresses where they can be connected to. A reasonable architecture for the
 * network map service might be one like the Tor directory authorities, where several nodes linked by RAFT or Paxos
 * elect a leader and that leader distributes signed documents describing the network layout. Those documents can
 * then be cached by every node and thus a network map can be retrieved given only a single successful peer connection.
 *
 * This interface assumes fast, synchronous access to an in-memory map.
*/
interface NetworkMapService {
    val timestampingNodes: List<LegallyIdentifiableNode>
    val ratesOracleNodes: List<LegallyIdentifiableNode>
    val partyNodes: List<LegallyIdentifiableNode>

    fun nodeForPartyName(name: String): LegallyIdentifiableNode? = partyNodes.singleOrNull { it.identity.name == name }
}

// TODO: Move this to the test tree once a real network map is implemented and this scaffolding is no longer needed.
class MockNetworkMapService : NetworkMapService {
    data class MockAddress(val id: String): SingleMessageRecipient

    override val timestampingNodes = Collections.synchronizedList(ArrayList<LegallyIdentifiableNode>())
    override val ratesOracleNodes = Collections.synchronizedList(ArrayList<LegallyIdentifiableNode>())
    override val partyNodes = Collections.synchronizedList(ArrayList<LegallyIdentifiableNode>())

    init {
        partyNodes.add(LegallyIdentifiableNode(MockAddress("excalibur:8080"), Party("Excalibur", DummyPublicKey("Excalibur"))))
        partyNodes.add(LegallyIdentifiableNode(MockAddress("another:8080"), Party("ANOther", DummyPublicKey("ANOther"))))
    }
}

/** A latitude/longitude pair. */
data class WorldCoordinate(val latitude: Double, val longitude: Double) {
    init {
        require(latitude in -90..90)
        require(longitude in -180..180)
    }

    /**
     * Convert to screen coordinates using the Mercator projection. You should have a world map image that
     * you know the precise extents of for this function to work.
     *
     * Note that no world map ever has latitude extents of -90 to 90 because at these extremes the mapping tends
     * to infinity. Google Maps, for example, uses a square map image, and square maps yield latitude extents
     * of 85.0511 to -85.0511 = arctan(sinh(π)).
     */
    fun project(screenWidth: Double, screenHeight: Double, topLatitude: Double, bottomLatitude: Double,
                leftLongitude: Double, rightLongitude: Double): Pair<Double, Double> {
        require(latitude in bottomLatitude..topLatitude)
        require(longitude in leftLongitude..rightLongitude)

        fun deg2rad(deg: Double) = deg * Math.PI / 180.0
        val leftLngRad = deg2rad(leftLongitude)
        val rightLngRad = deg2rad(rightLongitude)
        fun longitudeToScreenX(lng: Double) = screenWidth * (deg2rad(lng) - leftLngRad) / (rightLngRad - leftLngRad)
        fun screenYRelative(latDeg: Double) = Math.log(Math.tan(latDeg / 360.0 * Math.PI + Math.PI / 4))
        val topLatRel = screenYRelative(topLatitude)
        val bottomLatRel = screenYRelative(bottomLatitude)
        fun latitudeToScreenY(lat: Double) = screenHeight * (screenYRelative(lat) - topLatRel) / (bottomLatRel - topLatRel)
        return Pair(longitudeToScreenX(longitude), latitudeToScreenY(latitude))
    }
}

/**
 * A labelled [WorldCoordinate], where the label is human meaningful. For example, the name of the nearest city.
 * Labels should not refer to non-landmarks, for example, they should not contain the names of organisations.
 */
data class PhysicalLocation(val coordinate: WorldCoordinate, val description: String)

/**
 * A simple lookup table of city names to their coordinates. Lookups are case insensitive.
 */
object CityDatabase {
    private val cityMap = HashMap<String, PhysicalLocation>()

    init {
        javaClass.getResourceAsStream("cities.txt").bufferedReader().useLines { lines ->
            for (line in lines) {
                if (line.startsWith("#")) continue
                val (name, lng, lat) = line.split('\t')
                cityMap[name.toLowerCase()] = PhysicalLocation(WorldCoordinate(lat.toDouble(), lng.toDouble()), name)
            }
        }
    }

    operator fun get(name: String) = cityMap[name.toLowerCase()]
}