package com.wzy.replymate.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "generation")
data class GenerationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskType: String,
    val input: String,
    val candidatesJson: String,
    val source: String,
    val relation: String,
    val goal: String,
    val style: String,
    val emojiPolicy: String,
    val adoptedIndex: Int = -1,
    val latencyMs: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
    /** 同参数请求的缓存键；模板兜底为空，不参与命中 */
    @ColumnInfo(defaultValue = "''") val cacheKey: String = "",
    /** 是否进入话术库：只有模型成功生成的才为 true */
    @ColumnInfo(defaultValue = "0") val saved: Boolean = false,
    /** 收藏夹标记 */
    @ColumnInfo(defaultValue = "0") val favorite: Boolean = false
)

@Dao
interface GenerationDao {

    @Insert
    suspend fun insert(entity: GenerationEntity): Long

    @Query("SELECT * FROM generation ORDER BY createdAt DESC LIMIT 200")
    fun recent(): Flow<List<GenerationEntity>>

    @Query("SELECT * FROM generation WHERE relation = :relation ORDER BY createdAt DESC LIMIT 30")
    suspend fun byRelation(relation: String): List<GenerationEntity>

    /** 同参数命中：直接拿最近一次模型生成结果，不再消耗 Token */
    @Query("SELECT * FROM generation WHERE cacheKey = :key AND saved = 1 ORDER BY createdAt DESC LIMIT 1")
    suspend fun byCacheKey(key: String): GenerationEntity?

    /** 话术库：我的生成 */
    @Query("SELECT * FROM generation WHERE saved = 1 ORDER BY createdAt DESC LIMIT 200")
    fun saved(): Flow<List<GenerationEntity>>

    /** 话术库：收藏夹 */
    @Query("SELECT * FROM generation WHERE saved = 1 AND favorite = 1 ORDER BY createdAt DESC LIMIT 200")
    fun favorites(): Flow<List<GenerationEntity>>

    @Query("UPDATE generation SET favorite = :fav WHERE id = :id")
    suspend fun setFavorite(id: Long, fav: Boolean)

    @Query("UPDATE generation SET adoptedIndex = :index WHERE id = :id")
    suspend fun markAdopted(id: Long, index: Int)

    @Query("DELETE FROM generation WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM generation")
    suspend fun clear()

    @Query("SELECT COUNT(*) FROM generation")
    suspend fun count(): Int
}

/**
 * 联系人档案。summaryJson 只存 4 条摘要，不存原话。
 */
@Entity(tableName = "contact_profile")
data class ContactEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val relation: String = "FRIEND",
    val taboo: String = "",
    val tone: String = "",
    val notes: String = "",
    /** {"habit":"","care":"","taboo":"","trick":""} */
    val summaryJson: String = "",
    val autoEvolve: Boolean = true,
    val useCount: Int = 0,
    val evolvedAt: Long = 0,
    val createdAt: Long = System.currentTimeMillis()
)

/** 画像变更记录：一次一条、可追溯、可撤销 */
@Entity(tableName = "profile_change")
data class ProfileChangeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val contactId: Long,
    val field: String,
    val fieldLabel: String,
    val oldValue: String,
    val newValue: String,
    val evidence: String,
    val ts: Long = System.currentTimeMillis(),
    val reverted: Boolean = false
)

@Dao
interface ContactDao {

    @Query("SELECT * FROM contact_profile ORDER BY useCount DESC, createdAt DESC")
    fun all(): Flow<List<ContactEntity>>

    @Query("SELECT * FROM contact_profile WHERE id = :id")
    suspend fun byId(id: Long): ContactEntity?

    @Insert
    suspend fun insert(c: ContactEntity): Long

    @Update
    suspend fun update(c: ContactEntity)

    @Query("DELETE FROM contact_profile WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM profile_change WHERE contactId = :id ORDER BY ts DESC LIMIT 20")
    fun changes(id: Long): Flow<List<ProfileChangeEntity>>

    @Insert
    suspend fun insertChange(c: ProfileChangeEntity): Long

    @Query("UPDATE profile_change SET reverted = 1 WHERE id = :id")
    suspend fun markReverted(id: Long)
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `contact_profile` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`name` TEXT NOT NULL, `relation` TEXT NOT NULL, `taboo` TEXT NOT NULL, " +
                "`tone` TEXT NOT NULL, `notes` TEXT NOT NULL, `summaryJson` TEXT NOT NULL, " +
                "`autoEvolve` INTEGER NOT NULL, `useCount` INTEGER NOT NULL, " +
                "`evolvedAt` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `profile_change` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `contactId` INTEGER NOT NULL, " +
                "`field` TEXT NOT NULL, `fieldLabel` TEXT NOT NULL, `oldValue` TEXT NOT NULL, " +
                "`newValue` TEXT NOT NULL, `evidence` TEXT NOT NULL, `ts` INTEGER NOT NULL, " +
                "`reverted` INTEGER NOT NULL)"
        )
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `generation` ADD COLUMN `cacheKey` TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE `generation` ADD COLUMN `saved` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `generation` ADD COLUMN `favorite` INTEGER NOT NULL DEFAULT 0")
    }
}

/**
 * 悬浮聊天窗的一条记忆。
 * role：user / assistant / adopted（最终填入聊天框的那句）/ system
 * assistant 保留整批 3 条候选，方便下次继续追问。
 */
@kotlinx.serialization.Serializable
data class ChatTurn(
    val role: String,
    val text: String = "",
    val candidates: List<String> = emptyList(),
    val ts: Long = 0L
) {
    companion object {
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"
        const val ROLE_ADOPTED = "adopted"
        const val ROLE_SYSTEM = "system"
    }
}

/**
 * 每个聊天对象一份记忆，按「标题 + 应用包名」绑定：
 * 下次在同一个人的聊天页点悬浮球，就会自动切回这份记忆。
 */
@Entity(tableName = "chat_memory")
data class ChatMemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    @ColumnInfo(defaultValue = "''") val appPkg: String = "",
    @ColumnInfo(defaultValue = "''") val turnsJson: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Dao
interface ChatMemoryDao {

    @Query("SELECT * FROM chat_memory ORDER BY updatedAt DESC")
    suspend fun all(): List<ChatMemoryEntity>

    @Query("SELECT * FROM chat_memory WHERE name = :name AND appPkg = :pkg ORDER BY updatedAt DESC LIMIT 1")
    suspend fun find(name: String, pkg: String): ChatMemoryEntity?

    @Query("SELECT * FROM chat_memory WHERE id = :id")
    suspend fun byId(id: Long): ChatMemoryEntity?

    @Insert
    suspend fun insert(m: ChatMemoryEntity): Long

    @Update
    suspend fun update(m: ChatMemoryEntity)

    @Query("DELETE FROM chat_memory WHERE id = :id")
    suspend fun delete(id: Long)
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 字段默认值必须和实体上的 @ColumnInfo(defaultValue) 完全一致，
        // 否则 Room 迁移后校验 schema 会失败，整次迁移回滚，表建不出来。
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `chat_memory` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`name` TEXT NOT NULL, `appPkg` TEXT NOT NULL DEFAULT '', " +
                "`turnsJson` TEXT NOT NULL DEFAULT '', " +
                "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL)"
        )
    }
}

@Database(
    entities = [
        GenerationEntity::class,
        ContactEntity::class,
        ProfileChangeEntity::class,
        ChatMemoryEntity::class
    ],
    version = 4,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun generationDao(): GenerationDao
    abstract fun contactDao(): ContactDao
    abstract fun chatMemoryDao(): ChatMemoryDao
}
