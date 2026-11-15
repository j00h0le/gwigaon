package com.coral.gwigaon.trip.db

import com.coral.gwigaon.sync.OwnerLink
import com.coral.gwigaon.sync.OwnerLinkStore
import kotlin.time.Instant

/** [OwnerLinkStore] on Room: column mapping only. */
class RoomOwnerLinkStore(private val db: GwigaonDatabase) : OwnerLinkStore {
    override suspend fun get(): OwnerLink? =
        db.ownerLink().get()?.let { OwnerLink(it.token, Instant.fromEpochMilliseconds(it.createdAt), it.synced) }

    override suspend fun put(link: OwnerLink) =
        db.ownerLink().put(OwnerLinkEntity(token = link.token, createdAt = link.createdAt.toEpochMilliseconds(), synced = link.synced))
}
