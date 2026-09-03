@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.dreamdisplays.core.protocol.common.packets

import com.dreamdisplays.core.protocol.common.UuidSerializer
import com.dreamdisplays.core.protocol.common.ZERO_UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber
import java.util.UUID

/**
 * Atomically changes the display media and its matching subtitle track. This is used by catalog
 * episode picks so the server never briefly persists a new video with the previous episode's VTT.
 */
@Serializable
data class SetMediaWithSubtitle(
    @ProtoNumber(1) val id: @Serializable(UuidSerializer::class) UUID = ZERO_UUID,
    @ProtoNumber(2) val url: String = "",
    @ProtoNumber(3) val lang: String = "",
    @ProtoNumber(4) val subtitleUrl: String = "",
) : DreamPacket
