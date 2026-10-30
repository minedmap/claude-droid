package dev.minedmap.claudedroid.data
import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity data class SessionEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val claudeSessionId: String? = null, val title: String, val cwd: String, val updatedAt: Long,
  val model: String? = null, val effort: String? = null)

@Entity(foreignKeys = [ForeignKey(entity = SessionEntity::class, parentColumns = ["id"],
  childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE)], indices = [Index("sessionId")])
data class MessageEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val sessionId: Long, val role: String /* user|assistant|tool|tool_result|error */, val text: String, val ts: Long)

@Dao interface ChatDao {
  @Query("SELECT * FROM SessionEntity ORDER BY updatedAt DESC") fun sessions(): Flow<List<SessionEntity>>
  @Query("SELECT * FROM SessionEntity WHERE id=:id") suspend fun session(id: Long): SessionEntity?
  @Query("SELECT * FROM MessageEntity WHERE sessionId=:sid ORDER BY id") fun messages(sid: Long): Flow<List<MessageEntity>>
  @Insert suspend fun insert(s: SessionEntity): Long
  @Update suspend fun update(s: SessionEntity)
  @Delete suspend fun delete(s: SessionEntity)
  @Insert suspend fun insert(m: MessageEntity): Long
}

@Database(entities = [SessionEntity::class, MessageEntity::class], version = 2, exportSchema = false)
abstract class Db : RoomDatabase() {
  abstract fun dao(): ChatDao
  companion object {
    val M1_2 = object : androidx.room.migration.Migration(1, 2) {
      override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE SessionEntity ADD COLUMN model TEXT")
        db.execSQL("ALTER TABLE SessionEntity ADD COLUMN effort TEXT")
      }
    }
    @Volatile private var i: Db? = null
    fun get(ctx: Context): Db = i ?: synchronized(this) {
      i ?: Room.databaseBuilder(ctx.applicationContext, Db::class.java, "chat.db").addMigrations(M1_2).build().also { i = it }
    }
  }
}
