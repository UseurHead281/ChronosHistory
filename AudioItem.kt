package ru.chronos.stories.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "audio_items")
data class AudioItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val author: String = "",
    val uri: String,
    val fileName: String,
    val durationMs: Long = 0,
    val positionMs: Long = 0,
    val lastPlayedAt: Long = 0,
    val addedAt: Long = System.currentTimeMillis(),
    val isFavorite: Boolean = false,
    val isCompleted: Boolean = false
)

@Entity(tableName = "bookmarks")
data class Bookmark(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val audioId: Long,
    val positionMs: Long,
    val title: String,
    val color: Long = 0xFFFFB74D,
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis()
)
