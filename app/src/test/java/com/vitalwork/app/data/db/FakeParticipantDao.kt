package com.vitalwork.app.data.db

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

class FakeParticipantDao : ParticipantDao {

    val participants = mutableListOf<ParticipantEntity>()
    private var nextId = 1L

    override fun getAllParticipants(): Flow<List<ParticipantEntity>> = flowOf(participants.toList())

    override suspend fun getParticipantById(id: Long): ParticipantEntity? =
        participants.find { it.id == id }

    override suspend fun getParticipantByCode(code: String): ParticipantEntity? =
        participants.find { it.participantCode == code }

    override suspend fun getParticipantCount(): Int = participants.size

    override suspend fun getParticipantCountByPrefix(prefix: String): Int =
        participants.count { it.participantCode.startsWith("$prefix-") }

    override suspend fun insert(participant: ParticipantEntity): Long {
        if (participants.any { it.participantCode == participant.participantCode }) {
            throw IllegalStateException("duplicate participantCode")
        }
        val id = nextId++
        participants.add(participant.copy(id = id))
        return id
    }

    override suspend fun update(participant: ParticipantEntity) {
        val index = participants.indexOfFirst { it.id == participant.id }
        if (index >= 0) participants[index] = participant
    }

    override suspend fun delete(participant: ParticipantEntity) {
        participants.removeAll { it.id == participant.id }
    }
}
