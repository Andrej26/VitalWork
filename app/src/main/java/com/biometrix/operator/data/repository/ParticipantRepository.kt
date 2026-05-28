package com.biometrix.operator.data.repository

import com.biometrix.operator.data.db.ParticipantDao
import com.biometrix.operator.data.db.ParticipantEntity
import kotlinx.coroutines.flow.Flow
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ParticipantRepository @Inject constructor(
    private val participantDao: ParticipantDao
) {
    val allParticipants: Flow<List<ParticipantEntity>> = participantDao.getAllParticipants()

    suspend fun getParticipantById(id: Long): ParticipantEntity? =
        participantDao.getParticipantById(id)

    suspend fun getParticipantByCode(code: String): ParticipantEntity? =
        participantDao.getParticipantByCode(code)

    /**
     * Generates the next participant code in `P-NNN` format based on the current row count.
     */
    suspend fun generateNextParticipantCode(): String {
        val nextIndex = participantDao.getParticipantCount() + 1
        return String.format(Locale.US, "P-%03d", nextIndex)
    }

    /**
     * Creates a new participant. The caller is responsible for ensuring the code is unique
     * (call [getParticipantByCode] first if uniqueness needs to be checked before insert).
     */
    suspend fun createParticipant(
        participantCode: String,
        age: Int? = null,
        gender: String? = null
    ): ParticipantEntity {
        val participant = ParticipantEntity(
            participantCode = participantCode,
            age = age,
            gender = gender
        )
        val id = participantDao.insert(participant)
        return participant.copy(id = id)
    }

    suspend fun deleteParticipant(participant: ParticipantEntity) {
        participantDao.delete(participant)
    }
}
