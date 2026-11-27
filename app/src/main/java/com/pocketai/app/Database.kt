package com.pocketai.app

import android.content.Context
import androidx.room.*

@Entity(tableName = "messages")
data class MessageEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val role: String, val text: String, val timestamp: Long = System.currentTimeMillis())

@Entity(tableName = "memories")
data class MemoryEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val fact: String, val timestamp: Long = System.currentTimeMillis())

@Entity(tableName = "tasks")
data class TaskEntity(@PrimaryKey val id: String, val instruction: String, val objective: String, val status: String, val currentStep: Int, val totalSteps: Int, val lastObservation: String, val updatedAt: Long = System.currentTimeMillis())

@Entity(tableName = "usage_events")
data class UsageEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val source: String,
    val command: String = "",
    val durationMs: Long = 0L,
    val success: Boolean = true,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "knowledge_chunks")
data class KnowledgeChunkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val docName: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "plugins")
data class PluginEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val trigger: String,
    val actionType: String,
    val payload: String,
    val enabled: Boolean = true
)

@Dao interface MessageDao {
    @Query("SELECT * FROM messages ORDER BY id ASC") suspend fun getAll(): List<MessageEntity>
    @Insert suspend fun insert(msg: MessageEntity): Long
    @Query("UPDATE messages SET text = :text WHERE id = :id") suspend fun updateText(id: Long, text: String)
    @Query("DELETE FROM messages") suspend fun clearAll()
}

@Dao interface MemoryDao {
    @Query("SELECT * FROM memories ORDER BY id ASC") suspend fun getAll(): List<MemoryEntity>
    @Insert suspend fun insert(memory: MemoryEntity): Long
    @Query("DELETE FROM memories") suspend fun clearAll()
}

@Dao interface TaskDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(task: TaskEntity)
    @Query("SELECT * FROM tasks ORDER BY updatedAt DESC LIMIT 1") suspend fun getLatest(): TaskEntity?
}

@Dao interface UsageDao {
    @Insert suspend fun insert(e: UsageEventEntity)
    @Query("SELECT * FROM usage_events ORDER BY timestamp DESC LIMIT 500") suspend fun getRecent(): List<UsageEventEntity>
    @Query("DELETE FROM usage_events") suspend fun clearAll()
}

@Dao interface KnowledgeDao {
    @Insert suspend fun insertAll(chunks: List<KnowledgeChunkEntity>)
    @Query("SELECT * FROM knowledge_chunks") suspend fun getAll(): List<KnowledgeChunkEntity>
    @Query("DELETE FROM knowledge_chunks WHERE docName = :doc") suspend fun deleteDoc(doc: String)
    @Query("DELETE FROM knowledge_chunks") suspend fun clearAll()
}

@Dao interface PluginDao {
    @Query("SELECT * FROM plugins ORDER BY id ASC") suspend fun getAll(): List<PluginEntity>
    @Insert suspend fun insert(p: PluginEntity): Long
    @Update suspend fun update(p: PluginEntity)
    @Delete suspend fun delete(p: PluginEntity)
}

@Database(
    entities = [MessageEntity::class, MemoryEntity::class, TaskEntity::class, UsageEventEntity::class, KnowledgeChunkEntity::class, PluginEntity::class],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun memoryDao(): MemoryDao
    abstract fun taskDao(): TaskDao
    abstract fun usageDao(): UsageDao
    abstract fun knowledgeDao(): KnowledgeDao
    abstract fun pluginDao(): PluginDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null
        fun get(context: Context): AppDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "pocketai.db")
                .fallbackToDestructiveMigration().build().also { INSTANCE = it }
        }
    }
}
