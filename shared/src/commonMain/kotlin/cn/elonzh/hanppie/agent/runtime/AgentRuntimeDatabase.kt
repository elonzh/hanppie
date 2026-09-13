package cn.elonzh.hanppie.agent.runtime

import androidx.room3.ConstructedBy
import androidx.room3.Database
import androidx.room3.RoomDatabase
import androidx.room3.RoomDatabaseConstructor
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers

@Database(
    entities = [
        SessionProjectionEntity::class,
        SessionMessageProjection::class,
        AgentRunProjection::class,
        SessionProjectionCheckpoint::class,
    ],
    version = 1,
    exportSchema = true,
)
@ConstructedBy(AgentRuntimeDatabaseConstructor::class)
internal abstract class AgentRuntimeDatabase : RoomDatabase() {
    abstract fun sessionProjectionDao(): SessionProjectionDao
}

@Suppress("KotlinNoActualForExpect")
internal expect object AgentRuntimeDatabaseConstructor : RoomDatabaseConstructor<AgentRuntimeDatabase> {
    override fun initialize(): AgentRuntimeDatabase
}

internal fun buildAgentRuntimeDatabase(
    builder: RoomDatabase.Builder<AgentRuntimeDatabase>,
): AgentRuntimeDatabase = builder
    .setDriver(BundledSQLiteDriver())
    .setQueryCoroutineContext(Dispatchers.IO)
    .build()

/** Runtime-owned persistence that can be composed without the Hanppie application database. */
internal class AgentRuntimeStorage(
    val sessions: SessionHistory,
    private val database: AgentRuntimeDatabase,
) : AutoCloseable {
    override fun close() = database.close()
}
