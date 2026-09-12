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
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
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

@Database(entities = [StoredScript::class], version = 1, exportSchema = true)
@ConstructedBy(HanppieDatabaseConstructor::class)
internal abstract class HanppieDatabase : RoomDatabase() {
    abstract fun scriptDao(): ScriptDao
}

@Suppress("KotlinNoActualForExpect")
internal expect object HanppieDatabaseConstructor : RoomDatabaseConstructor<HanppieDatabase> {
    override fun initialize(): HanppieDatabase
}

internal fun buildHanppieDatabase(builder: RoomDatabase.Builder<HanppieDatabase>): HanppieDatabase =
    builder
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()

internal interface ScriptRepository {
    suspend fun all(): List<StoredScript>
    suspend fun insert(script: StoredScript)
    suspend fun update(script: StoredScript)
    suspend fun delete(script: StoredScript)
}

internal class RoomScriptRepository(private val dao: ScriptDao) : ScriptRepository {
    override suspend fun all(): List<StoredScript> = dao.getAll()
    override suspend fun insert(script: StoredScript) = dao.insert(script)
    override suspend fun update(script: StoredScript) = dao.update(script)
    override suspend fun delete(script: StoredScript) = dao.delete(script)
}
