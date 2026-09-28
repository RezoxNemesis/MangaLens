package com.mangalens.engine
import androidx.room.*
import kotlinx.coroutines.flow.Flow
@Entity(tableName="orez_messages")
data class OrezMessageEntity(@PrimaryKey(autoGenerate=true)val id:Long=0,val role:String,val text:String,val createdAt:Long=System.currentTimeMillis())
@Dao interface OrezMessageDao{@Query("SELECT * FROM orez_messages ORDER BY createdAt ASC LIMIT 200")fun observe():Flow<List<OrezMessageEntity>>;@Insert suspend fun insert(message:OrezMessageEntity);@Query("DELETE FROM orez_messages")suspend fun clear()}
@Database(entities=[OrezMessageEntity::class],version=1,exportSchema=false)
abstract class OrezDatabase:RoomDatabase(){abstract fun messages():OrezMessageDao}
