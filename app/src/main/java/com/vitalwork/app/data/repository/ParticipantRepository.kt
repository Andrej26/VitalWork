package com.vitalwork.app.data.repository

import com.vitalwork.app.data.db.ParticipantDao
import com.vitalwork.app.data.db.ParticipantEntity
import com.vitalwork.app.data.prefs.SettingsRepository
import kotlinx.coroutines.flow.Flow
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ParticipantRepository @Inject constructor(
    private val participantDao: ParticipantDao,
    private val settingsRepository: SettingsRepository
) {
    val allParticipants: Flow<List<ParticipantEntity>> = participantDao.getAllParticipants()

    suspend fun getParticipantById(id: Long): ParticipantEntity? =
        participantDao.getParticipantById(id)

    suspend fun getParticipantByCode(code: String): ParticipantEntity? =
        participantDao.getParticipantByCode(code)

    /**
     * Generates the next participant code in `<prefix>-NNN-yyMMdd-HHmmss` format (e.g.
     * `A-001-260620-143022`), mirroring the session-code timestamp. The prefix is the per-device
     * setting and guards against cross-tablet collisions; the timestamp guarantees global uniqueness
     * even after a reinstall or destructive DB wipe (which reset the local count). The `NNN` counter
     * is scoped to the prefix for readability/ordering only — the full string is the unique key.
     */
    suspend fun generateNextParticipantCode(): String {
        val prefix = settingsRepository.getDevicePrefix()
        val nextIndex = participantDao.getParticipantCountByPrefix(prefix) + 1
        val timestampToken = LocalDateTime.now()
            .format(DateTimeFormatter.ofPattern("yyMMdd-HHmmss", Locale.US))
        return String.format(Locale.US, "%s-%03d-%s", prefix, nextIndex, timestampToken)
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
