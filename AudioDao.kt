package ru.chronos.stories.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface AudioDao {
    @Query("SELECT * FROM audio_items ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<AudioItem>>

    @Query("SELECT * FROM audio_items WHERE id = :id")
    suspend fun getById(id: Long): AudioItem?

    @Query("SELECT * FROM audio_items WHERE uri = :uri LIMIT 1")
    suspend fun findByUri(uri: String): AudioItem?

    @Insert
    suspend fun insert(item: AudioItem): Long

    @Update
    suspend fun update(item: AudioItem)

    @Delete
    suspend fun delete(item: AudioItem)

    @Query("SELECT * FROM bookmarks ORDER BY createdAt DESC")
    fun observeBookmarks(): Flow<List<Bookmark>>

    @Query("SELECT * FROM bookmarks WHERE audioId = :audioId ORDER BY positionMs")
    fun observeBookmarksFor(audioId: Long): Flow<List<Bookmark>>

    @Insert
    suspend fun insertBookmark(bookmark: Bookmark): Long

    @Delete
    suspend fun deleteBookmark(bookmark: Bookmark)
}
