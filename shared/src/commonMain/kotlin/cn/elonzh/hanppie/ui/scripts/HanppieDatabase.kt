package cn.elonzh.hanppie.ui.scripts

import androidx.room3.ConstructedBy
import androidx.room3.Dao
import androidx.room3.Database
import androidx.room3.Delete
import androidx.room3.Insert
import androidx.room3.Query
import androidx.room3.RoomDatabase
import androidx.room3.RoomDatabaseConstructor
import androidx.room3.Update
import androidx.room3.migration.Migration
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.Dispatchers

@Dao
internal interface ScriptDao {
    @Query("SELECT * FROM scripts ORDER BY updatedAtEpochMillis DESC")
    suspend fun getAll(): List<StoredScript>

    @Insert
    suspend fun insert(script: StoredScript)

    @Update
    suspend fun update(script: StoredScript)

    @Delete
    suspend fun delete(script: StoredScript)
}

@Database(
    entities = [StoredScript::class, StoredScriptAudio::class],
    version = 2,
    exportSchema = true,
)
@ConstructedBy(HanppieDatabaseConstructor::class)
internal abstract class HanppieDatabase : RoomDatabase() {
    abstract fun scriptDao(): ScriptDao
    abstract fun scriptAudioDao(): ScriptAudioDao
}

@Suppress("KotlinNoActualForExpect")
internal expect object HanppieDatabaseConstructor : RoomDatabaseConstructor<HanppieDatabase> {
    override fun initialize(): HanppieDatabase
}

/**
 * Adds script custom audio to an existing script library.
 *
 * The audio table is additive: every existing script row survives the upgrade and simply carries no
 * audio until the user imports one.
 */
internal val SCRIPT_AUDIO_MIGRATION = object : Migration(1, 2) {
    override suspend fun migrate(connection: androidx.sqlite.SQLiteConnection) {
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `script_audio` (`scriptId` TEXT NOT NULL, `nativeId` INTEGER NOT NULL, " +
                "`name` TEXT NOT NULL, `durationMillis` INTEGER NOT NULL, `packets` BLOB NOT NULL, " +
                "PRIMARY KEY(`scriptId`, `nativeId`), " +
                "FOREIGN KEY(`scriptId`) REFERENCES `scripts`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_script_audio_scriptId` ON `script_audio` (`scriptId`)")
    }
}

internal fun buildHanppieDatabase(builder: RoomDatabase.Builder<HanppieDatabase>): HanppieDatabase =
    builder
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .addMigrations(SCRIPT_AUDIO_MIGRATION)
        .build()

internal interface ScriptRepository {
    suspend fun all(): List<StoredScript>
    suspend fun insert(script: StoredScript)
    suspend fun update(script: StoredScript)
    suspend fun delete(script: StoredScript)
    suspend fun audio(scriptId: String): List<StoredScriptAudio>
    suspend fun insertAudio(audio: StoredScriptAudio)
    suspend fun updateAudio(audio: StoredScriptAudio)
    suspend fun deleteAudio(scriptId: String, nativeId: Int): Boolean
}

internal class RoomScriptRepository(
    private val dao: ScriptDao,
    private val audioDao: ScriptAudioDao,
) : ScriptRepository {
    override suspend fun all(): List<StoredScript> = dao.getAll()
    override suspend fun insert(script: StoredScript) = dao.insert(script)
    override suspend fun update(script: StoredScript) = dao.update(script)
    override suspend fun delete(script: StoredScript) = dao.delete(script)
    override suspend fun audio(scriptId: String): List<StoredScriptAudio> = audioDao.forScript(scriptId)
    override suspend fun insertAudio(audio: StoredScriptAudio) = audioDao.insert(audio)
    override suspend fun updateAudio(audio: StoredScriptAudio) = audioDao.update(audio)
    override suspend fun deleteAudio(scriptId: String, nativeId: Int): Boolean =
        audioDao.delete(scriptId, nativeId) > 0
}
