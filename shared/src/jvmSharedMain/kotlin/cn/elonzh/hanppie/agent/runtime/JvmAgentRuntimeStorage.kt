package cn.elonzh.hanppie.agent.runtime

import androidx.room3.Room
import java.nio.file.Files
import java.nio.file.Path

internal fun openAgentRuntimeStorage(
    databasePath: Path,
    eventDirectory: Path,
): AgentRuntimeStorage {
    Files.createDirectories(requireNotNull(databasePath.parent) { "Agent runtime database needs a parent directory" })
    Files.createDirectories(eventDirectory)
    val database = buildAgentRuntimeDatabase(
        Room.databaseBuilder<AgentRuntimeDatabase>(name = databasePath.toString()),
    )
    return try {
        AgentRuntimeStorage(
            sessions = SessionRepository(
                events = JsonlSessionEventStore(eventDirectory),
                dao = database.sessionProjectionDao(),
                database = database,
            ),
            database = database,
        )
    } catch (error: Exception) {
        database.close()
        throw error
    }
}
