package st.kiwifarms.sneedroid.data

/**
 * The built-in SneedChat rooms. The server doesn't send room names over the protocol, so
 * the well-known ones are mapped here by id. [defaults] are always shown in the drawer (in
 * this order); any other room a user joins by id is added alongside them and rendered as
 * "Room #<id>" via [name].
 */
object Rooms {
    data class Room(val id: Int, val name: String)

    val defaults: List<Room> = listOf(
        Room(1, "General"),
        Room(20, "Lolcows"),
        Room(18, "Beauty Parlor"),
        Room(16, "Fishtank"),
        Room(8, "Gunt"),
        Room(15, "Keno Kasino"),
        Room(19, "Sports!!"),
    )

    val defaultIds: List<Int> = defaults.map { it.id }

    private val byId: Map<Int, String> = defaults.associate { it.id to it.name }

    /** Display name for a room id, falling back to "Room #<id>" for unknown rooms. */
    fun name(id: Int): String = byId[id] ?: "Room #$id"
}
