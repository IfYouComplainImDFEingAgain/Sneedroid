package st.kiwifarms.sneedroid.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * A user-defined input macro ("custom emote"). Tapping one drops [insert] into the composer; that
 * text may be a BBCode snippet, an `[img]…[/img]` tag, or anything else.
 *
 * @param label   display text shown in the picker (and used as the icon when [iconUrl] is null)
 * @param insert  the exact text inserted into the message input
 * @param iconUrl optional image URL rendered as the grid icon (Coil disk-caches it locally)
 * @param autoSend when true, tapping the macro sends it immediately *if the input box is empty*
 */
@Serializable
data class CustomMacro(
    val id: String = UUID.randomUUID().toString(),
    val label: String,
    val insert: String,
    val iconUrl: String? = null,
    val autoSend: Boolean = false,
)

/**
 * Disk persistence for [CustomMacro]s as a single JSON file in `filesDir`. Macros are device-wide
 * (not per-account), mirroring how the bundled emote table is shared. Exposed as a [StateFlow] so
 * the composer's picker updates live as macros are added, edited, or removed.
 */
class MacroStore(context: Context) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val file = File(context.filesDir, "custom_macros.json")
    private val writeLock = Any()

    private val _macros = MutableStateFlow(load())
    val macros: StateFlow<List<CustomMacro>> = _macros.asStateFlow()

    private fun load(): List<CustomMacro> {
        if (!file.exists()) return emptyList()
        return runCatching { json.decodeFromString<List<CustomMacro>>(file.readText()) }.getOrDefault(emptyList())
    }

    private fun persist(list: List<CustomMacro>) {
        _macros.value = list
        synchronized(writeLock) { runCatching { file.writeText(json.encodeToString(list)) } }
    }

    /** Add a new macro, or replace the existing one with the same id (upsert). */
    fun save(macro: CustomMacro) {
        val list = _macros.value
        val idx = list.indexOfFirst { it.id == macro.id }
        persist(if (idx >= 0) list.toMutableList().also { it[idx] = macro } else list + macro)
    }

    fun remove(id: String) {
        persist(_macros.value.filterNot { it.id == id })
    }
}
