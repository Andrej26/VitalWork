package com.biometrix.operator.data.export

/**
 * Persists a completed test to a long-term destination.
 *
 * Today: local export to Documents (see [SessionExportService]).
 * Future: HTTP upload to the BioMetrix web server.
 */
interface SessionUploader {
    suspend fun upload(sessionId: Long): Result<String>
}
