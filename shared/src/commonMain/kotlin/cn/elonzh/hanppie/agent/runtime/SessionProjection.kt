package cn.elonzh.hanppie.agent.runtime

import androidx.room3.Dao
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.PrimaryKey
import androidx.room3.Query

/** Stable runtime-facing Session metadata; it is not a Room entity. */
internal data class AgentSession(
    val id: String,
    val title: String,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val archivedAtEpochMillis: Long?,
    val latestEventId: String?,
    val lastMessagePreview: String,
)

@Entity(tableName = "session_projection")
internal data class SessionProjectionEntity(
    @PrimaryKey val id: String,
    val title: String,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val archivedAtEpochMillis: Long?,
    val latestEventId: String?,
    val lastMessagePreview: String,
)

@Entity(
    tableName = "session_messages",
    indices = [Index(value = ["sessionId", "position"], unique = true)],
)
internal data class SessionMessageProjection(
    @PrimaryKey val eventId: String,
    val sessionId: String,
    val position: Long,
    val role: String,
    val text: String,
    val messageJson: String,
    val createdAtEpochMillis: Long,
)

@Entity(tableName = "agent_runs", indices = [Index("sessionId")])
internal data class AgentRunProjection(
    @PrimaryKey val id: String,
    val sessionId: String,
    val modelProvider: String,
    val modelId: String,
    val startedAtEpochMillis: Long,
    val finishedAtEpochMillis: Long?,
    val outcome: String?,
    val errorType: String?,
)

@Entity(tableName = "session_projection_checkpoints")
internal data class SessionProjectionCheckpoint(
    @PrimaryKey val sessionId: String,
    val lastEventId: String?,
    val eventCount: Long,
)

@Dao
internal interface SessionProjectionDao {
    @Query("SELECT * FROM session_projection WHERE archivedAtEpochMillis IS NULL ORDER BY updatedAtEpochMillis DESC")
    suspend fun active(): List<SessionProjectionEntity>

    @Query("SELECT * FROM session_projection WHERE archivedAtEpochMillis IS NOT NULL ORDER BY archivedAtEpochMillis DESC")
    suspend fun archived(): List<SessionProjectionEntity>

    @Query("SELECT * FROM session_projection WHERE id = :id")
    suspend fun find(id: String): SessionProjectionEntity?

    @Query("SELECT id FROM session_projection")
    suspend fun sessionIds(): List<String>

    @Query("SELECT * FROM session_messages WHERE sessionId = :sessionId ORDER BY position")
    suspend fun messages(sessionId: String): List<SessionMessageProjection>

    @Query("SELECT * FROM session_projection_checkpoints WHERE sessionId = :sessionId")
    suspend fun checkpoint(sessionId: String): SessionProjectionCheckpoint?

    @Query("SELECT * FROM agent_runs WHERE outcome IS NULL ORDER BY startedAtEpochMillis")
    suspend fun unfinishedRuns(): List<AgentRunProjection>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putSession(session: SessionProjectionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putMessage(message: SessionMessageProjection)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putRun(run: AgentRunProjection)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putCheckpoint(checkpoint: SessionProjectionCheckpoint)

    @Query("UPDATE agent_runs SET finishedAtEpochMillis = :finishedAt, outcome = :outcome, errorType = :errorType WHERE id = :runId")
    suspend fun finishRun(runId: String, finishedAt: Long, outcome: String, errorType: String?)

    @Query("UPDATE session_projection SET updatedAtEpochMillis = :updatedAt, latestEventId = :eventId WHERE id = :sessionId")
    suspend fun touchSession(sessionId: String, updatedAt: Long, eventId: String)

    @Query("DELETE FROM session_messages WHERE sessionId = :sessionId")
    suspend fun deleteMessages(sessionId: String)

    @Query("DELETE FROM agent_runs WHERE sessionId = :sessionId")
    suspend fun deleteRuns(sessionId: String)

    @Query("DELETE FROM session_projection_checkpoints WHERE sessionId = :sessionId")
    suspend fun deleteCheckpoint(sessionId: String)

    @Query("DELETE FROM session_projection WHERE id = :sessionId")
    suspend fun deleteSession(sessionId: String)
}

internal fun SessionProjectionEntity.toAgentSession() = AgentSession(
    id = id,
    title = title,
    createdAtEpochMillis = createdAtEpochMillis,
    updatedAtEpochMillis = updatedAtEpochMillis,
    archivedAtEpochMillis = archivedAtEpochMillis,
    latestEventId = latestEventId,
    lastMessagePreview = lastMessagePreview,
)
