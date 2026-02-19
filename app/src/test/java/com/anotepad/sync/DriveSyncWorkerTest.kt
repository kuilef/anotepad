package com.anotepad.sync

import com.anotepad.sync.engine.fixtures.SyncFixtureBuilder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class DriveSyncWorkerTest {

    @Test
    fun worker_retries_onNetworkError() = runTest {
        // Given
        val (runner, builder, _) = runnerWithError(DriveNetworkException(IOException("timeout")))

        // When
        val result = runner.run()

        // Then
        assertEquals(WorkerDecision.Retry, result)
        assertEquals(SyncState.ERROR, builder.store.statuses.last().state)
    }

    @Test
    fun worker_retries_onDrive429() = runTest {
        // Given
        val (runner, _, _) = runnerWithError(DriveApiException(429, """{"error":{"message":"rate","errors":[{"reason":"rateLimitExceeded"}]}}"""))

        // When
        val result = runner.run()

        // Then
        assertEquals(WorkerDecision.Retry, result)
    }

    @Test
    fun worker_retries_onDrive5xx() = runTest {
        // Given
        val (runner, _, _) = runnerWithError(DriveApiException(503, """{"error":{"message":"backend"}}"""))

        // When
        val result = runner.run()

        // Then
        assertEquals(WorkerDecision.Retry, result)
    }

    @Test
    fun worker_recovers_onFirstDrive401_byInvalidatingTokenAndRetrying() = runTest {
        // Given
        val builder = SyncFixtureBuilder()
            .withDriveFolder("drive-root", "Anotepad")
            .withStartPageToken("p1")
        builder.drive.listChangesErrors += DriveApiException(401, """{"error":{"message":"auth"}}""")
        val engine = builder.buildEngine()
        val runner = DriveSyncWorkerRunner(
            engine = engine,
            store = builder.store,
            authGateway = builder.auth
        )

        // When
        val result = runner.run()

        // Then
        assertEquals(WorkerDecision.Success, result)
        assertEquals(1, builder.auth.invalidateCalls)
        assertEquals(0, builder.auth.revokeCalls)
    }

    @Test
    fun worker_fails_onRepeatedDrive401_andRevokesAuth() = runTest {
        // Given
        val (runner, builder, _) = runnerWithError(DriveApiException(401, """{"error":{"message":"auth"}}"""))

        // When
        val result = runner.run()

        // Then
        assertEquals(WorkerDecision.Failure, result)
        assertEquals(1, builder.auth.invalidateCalls)
        assertEquals(1, builder.auth.revokeCalls)
        assertEquals("Sign in required", builder.store.statuses.last().message)
    }

    @Test
    fun worker_fails_onDrive403() = runTest {
        // Given
        val (runner, builder, _) = runnerWithError(DriveApiException(403, """{"error":{"message":"forbidden"}}"""))

        // When
        val result = runner.run()

        // Then
        assertEquals(WorkerDecision.Failure, result)
        assertEquals(1, builder.auth.revokeCalls)
    }

    @Test
    fun worker_retries_onUnexpectedException() = runTest {
        // Given
        val (runner, builder, _) = runnerWithError(IllegalStateException("boom"))

        // When
        val result = runner.run()

        // Then
        assertEquals(WorkerDecision.Retry, result)
        assertTrue(builder.store.statuses.last().message?.contains("Unexpected error") == true)
    }

    @Test
    fun worker_setsReadableErrorStatus_forApiError() = runTest {
        // Given
        val (runner, builder, _) = runnerWithError(
            DriveApiException(400, """{"error":{"message":"Bad request","errors":[{"reason":"invalidArgument"}]}}""")
        )

        // When
        runner.run()

        // Then
        val message = builder.store.statuses.last().message.orEmpty()
        assertTrue(message.contains("Drive error 400"))
        assertTrue(
            message.contains("Bad request") ||
                message.contains("invalidArgument") ||
                message == "Drive error 400"
        )
    }

    @Test
    fun worker_setsReadableErrorStatus_forNetworkError() = runTest {
        // Given
        val (runner, builder, _) = runnerWithError(DriveNetworkException(IOException("No route")))

        // When
        runner.run()

        // Then
        val message = builder.store.statuses.last().message.orEmpty()
        assertTrue(message.contains("Network error"))
    }

    private fun runnerWithError(error: Exception): Triple<DriveSyncWorkerRunner, SyncFixtureBuilder, MutableList<String>> {
        val builder = SyncFixtureBuilder()
            .withDriveFolder("drive-root", "Anotepad")
            .withStartPageToken("p1")
        builder.drive.listChangesError = error
        val engine = builder.buildEngine()
        val logs = mutableListOf<String>()
        val runner = DriveSyncWorkerRunner(
            engine = engine,
            store = builder.store,
            authGateway = builder.auth,
            logger = { logs += it }
        )
        return Triple(runner, builder, logs)
    }
}
