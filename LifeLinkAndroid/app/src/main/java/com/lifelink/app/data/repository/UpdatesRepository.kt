package com.lifelink.app.data.repository

import com.lifelink.app.data.local.UpdateDao
import com.lifelink.app.data.local.UpdateEntity
import com.lifelink.app.domain.UpdateItem
import com.lifelink.app.domain.UpdateType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class UpdatesRepository(private val dao: UpdateDao) {
    fun observe(): Flow<List<UpdateItem>> = dao.observeAll().map { updates -> updates.map(UpdateEntity::toDomain) }

    suspend fun record(
        id: String,
        type: UpdateType,
        title: String,
        body: String,
        requestId: String? = null,
        actionKey: String? = null,
        createdAtEpochMillis: Long = System.currentTimeMillis()
    ) {
        val existing = dao.findById(id)
        dao.upsert(UpdateEntity(id, type.name, title, body, createdAtEpochMillis, requestId, actionKey, existing?.isRead ?: false))
    }

    suspend fun markRead(id: String) = dao.markRead(id)

    suspend fun markAllRead() = dao.markAllRead()
}

private fun UpdateEntity.toDomain() = UpdateItem(
    id = id,
    type = runCatching { UpdateType.valueOf(type) }.getOrDefault(UpdateType.SYSTEM),
    title = title,
    body = body,
    createdAtEpochMillis = createdAtEpochMillis,
    requestId = requestId,
    actionKey = actionKey,
    isRead = isRead
)
