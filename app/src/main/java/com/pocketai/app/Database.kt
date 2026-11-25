package com.pocketai.app

import android.content.Context
import androidx.room.*

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val role: String,
    val text: String,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "memories")
data class MemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fact: String,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "tasks")
data class TaskEntity(
    @PrimaryKey val id: String,
    val instruction: String,
    val objective: String,
    val status: String,
    val currentStep: Int,
    val totalSteps: Int,
    val lastObservation: String,
    val updatedAt: Long = System.currentTimeMillis()
)

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages ORDER BY id ASC")
    suspend fun getAll(): List<MessageEntity>

    @Insert
    suspend fun insert(msg: MessageEntity): Long

    @Query("UPDATE messages SET text = :text WHERE id = :id")
    suspend fun updateText(id: Long, text: String)

    @Query("DELETE FROM messages")
    suspend fun clearAll()
}

@Dao
interface MemoryDao {
    @Query("SELECT * FROM memories ORDER BY id ASC")
    suspend fun getAll(): List<MemoryEntity>

    @Insert
    suspend fun insert(memory: MemoryEntity): Long

    @Query("DELETE FROM memories")
    suspend fun clearAll()
}

@Dao
interface TaskDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(task: TaskEntity)

    @Query("SELECT * FROM tasks ORDER BY updatedAt DESC LIMIT 1")
    suspend fun getLatest(): TaskEntity?
}

@Database(
    entities = [MessageEntity::class, MemoryEntity::class, TaskEntity::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun memoryDao(): MemoryDao
    abstract fun taskDao(): TaskDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun get(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "pocketai.db"
                ).fallbackToDestructiveMigration().build().also { INSTANCE = it }
            }
        }
    }
}
