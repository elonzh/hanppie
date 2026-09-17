package cn.elonzh.hanppie.ui.scripts

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class ScriptAudioMigrationTest {
    /** The shipped version 1 schema, written by hand because the entity moved on to version 2. */
    private fun createVersionOneDatabase(file: String) {
        BundledSQLiteDriver().open(file).use { connection ->
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS `scripts` (`id` TEXT NOT NULL, `name` TEXT NOT NULL COLLATE NOCASE, " +
                    "`source` TEXT NOT NULL, `createdAtEpochMillis` INTEGER NOT NULL, `updatedAtEpochMillis` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`id`))",
            )
            connection.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_scripts_name` ON `scripts` (`name`)")
            connection.execSQL(
                "INSERT INTO `scripts` (`id`, `name`, `source`, `createdAtEpochMillis`, `updatedAtEpochMillis`) " +
                    "VALUES ('legacy', '巡检', 'def start():\n    pass\n', 1, 2)",
            )
            connection.execSQL("PRAGMA user_version = 1")
        }
    }

    @Test fun upgradeKeepsScriptsAndRemovesAudioWithItsScript() = runBlocking {
        val directory = Files.createTempDirectory("hanppie-script-audio-migration-")
        var database: HanppieDatabase? = null
        try {
            val file = directory.resolve("hanppie.db")
            createVersionOneDatabase(file.toString())
            database = buildHanppieDatabase(Room.databaseBuilder<HanppieDatabase>(file.toString()))
            val repository = RoomScriptRepository(database.scriptDao(), database.scriptAudioDao())

            val script = repository.all().single()
            assertEquals("legacy", script.id)
            assertEquals("巡检", script.name)
            assertTrue(repository.audio(script.id).isEmpty())

            repository.insertAudio(StoredScriptAudio(script.id, 0, "voice", 1_000, byteArrayOf(1, 2, 3)))
            repository.insertAudio(StoredScriptAudio(script.id, 1, "beep", 500, byteArrayOf(4)))
            assertEquals(listOf("voice", "beep"), repository.audio(script.id).map { it.name })

            repository.updateAudio(StoredScriptAudio(script.id, 0, "opening", 1_000, byteArrayOf(1, 2, 3)))
            assertEquals("opening", repository.audio(script.id).first().name)
            assertTrue(repository.deleteAudio(script.id, 1))
            assertTrue(!repository.deleteAudio(script.id, 1))

            repository.delete(script)
            assertTrue(repository.all().isEmpty())
            assertTrue(repository.audio(script.id).isEmpty(), "删除脚本必须同时删除其自定义音频")
        } finally {
            database?.close()
            directory.toFile().deleteRecursively()
        }
    }
}
