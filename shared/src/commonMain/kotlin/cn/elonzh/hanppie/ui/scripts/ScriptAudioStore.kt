package cn.elonzh.hanppie.ui.scripts

import androidx.room3.Dao
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Update
import cn.elonzh.hanppie.robot.lab.LabAudioClip

/**
 * Custom audio attached to one saved script.
 *
 * The audio body travels inside the DSP container, so it belongs to the program rather than to the
 * robot: deleting the script deletes its audio, and an unsaved draft has no audio row at all.
 */
@Entity(
    tableName = "script_audio",
    primaryKeys = ["scriptId", "nativeId"],
    indices = [Index("scriptId")],
    foreignKeys = [ForeignKey(
        entity = StoredScript::class,
        parentColumns = ["id"],
        childColumns = ["scriptId"],
        onDelete = ForeignKey.CASCADE,
    )],
)
internal data class StoredScriptAudio(
    val scriptId: String,
    val nativeId: Int,
    val name: String,
    val durationMillis: Long,
    val packets: ByteArray,
)

@Dao
internal interface ScriptAudioDao {
    @Query("SELECT * FROM script_audio WHERE scriptId = :scriptId ORDER BY nativeId")
    suspend fun forScript(scriptId: String): List<StoredScriptAudio>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(audio: StoredScriptAudio)

    @Update
    suspend fun update(audio: StoredScriptAudio)

    @Query("DELETE FROM script_audio WHERE scriptId = :scriptId AND nativeId = :nativeId")
    suspend fun delete(scriptId: String, nativeId: Int): Int
}

internal fun StoredScriptAudio.toClip(): LabAudioClip = LabAudioClip(nativeId, name, durationMillis, packets)

internal fun LabAudioClip.toStored(scriptId: String): StoredScriptAudio =
    StoredScriptAudio(scriptId, id, name, durationMillis, packets)

internal fun LabAudioClip.renamed(name: String): LabAudioClip = LabAudioClip(id, name, durationMillis, packets)
