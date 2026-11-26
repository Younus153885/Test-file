package com.example.data.license

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.example.base.FirestoreEmulatorTestBase
import com.google.firebase.firestore.FirebaseFirestoreException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.robolectric.Shadows.shadowOf

class LicenseRepositoryRuleTest : FirestoreEmulatorTestBase() {

    private lateinit var apiClient: LicenseApiClient

    @Before
    override fun setUp() {
        super.setUp()
        val context = ApplicationProvider.getApplicationContext<Application>()
        apiClient = LicenseApiClient(firestore, auth)
    }

    private fun <T> awaitSuspend(timeoutSec: Long = 15, block: suspend () -> T): T {
        val deferred = CoroutineScope(Dispatchers.IO).async { block() }
        val deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(timeoutSec)
        while (!deferred.isCompleted) {
            if (System.currentTimeMillis() > deadline) {
                deferred.cancel()
                throw TimeoutException("Suspend block timed out after $timeoutSec seconds")
            }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        shadowOf(Looper.getMainLooper()).idle()
        return kotlinx.coroutines.runBlocking { deferred.await() }
    }

    @Test
    fun createAndActivateLicense_succeedsForAdminAndAuthenticatedUser() {
        // 1. Sign in as Super Admin and seed admin record
        val adminUid = signInTestUser(SUPER_ADMIN_EMAIL, "password123")
        seedAdminRoleInEmulator(adminUid, SUPER_ADMIN_EMAIL)

        val createResult = awaitSuspend {
            apiClient.createLicenses(
                keys = listOf("VIP-2026-KEY1"),
                days = 30,
                price = 60.0,
                maxVideoDuration = 0,
                adminToken = adminUid,
                note = "Test Customer"
            )
        }
        assertTrue("Admin license creation should succeed: $createResult", createResult.isSuccess)

        // 2. Sign in as regular user and activate the license key on their device
        signInTestUser("customer@gmail.com", "password123")
        val fetched = awaitSuspend { apiClient.getLicenseByKey("VIP-2026-KEY1") }
        assertNotNull("Authenticated user should read license by key", fetched)
        assertEquals("inactive", fetched?.status)

        val activateRes = awaitSuspend {
            apiClient.activateLicenseOnServer(
                licenseId = "VIP-2026-KEY1",
                deviceId = "DEV-TEST-999",
                days = 30,
                existingExpiresAt = null
            )
        }
        assertTrue("User activation of inactive key should succeed: $activateRes", activateRes.isSuccess)
    }

    @Test
    fun createLicense_asNonAdmin_throwsPermissionDenied() {
        signInTestUser("regularuser@gmail.com", "password123")

        val model = LicenseModel(
            id = "HACK-KEY-001",
            key = "HACK-KEY-001",
            days = 365,
            price = 0.0,
            status = "inactive"
        )
        try {
            awaitTask(firestore.collection("licenses").document("HACK-KEY-001").set(model))
            fail("Expected PERMISSION_DENIED when non-admin creates a license")
        } catch (e: Exception) {
            val cause = (e as? FirebaseFirestoreException) ?: (e.cause as? FirebaseFirestoreException)
            assertNotNull("Expected FirebaseFirestoreException, got $e", cause)
            assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, cause?.code)
        }
    }

    @Test
    fun activateSuspendedLicense_throwsPermissionDenied() {
        val adminUid = signInTestUser(SUPER_ADMIN_EMAIL, "password123")
        seedAdminRoleInEmulator(adminUid, SUPER_ADMIN_EMAIL)

        val createRes = awaitSuspend {
            apiClient.createLicenses(
                keys = listOf("BLOCKED-KEY-01"),
                days = 30,
                price = 60.0
            )
        }
        assertTrue(createRes.isSuccess)

        val suspendRes = awaitSuspend {
            apiClient.updateLicenseStatus("BLOCKED-KEY-01", "suspended", adminUid)
        }
        assertTrue(suspendRes.isSuccess)

        // Now sign in as regular user and attempt to activate or transfer the suspended key
        signInTestUser("blockeduser@gmail.com", "password123")
        try {
            awaitTask(
                firestore.collection("licenses").document("BLOCKED-KEY-01")
                    .update(
                        mapOf(
                            "status" to "active",
                            "deviceId" to "NEW-DEVICE-ID"
                        )
                    )
            )
            fail("Expected PERMISSION_DENIED when user tries to reactivate a suspended license")
        } catch (e: Exception) {
            val cause = (e as? FirebaseFirestoreException) ?: (e.cause as? FirebaseFirestoreException)
            assertNotNull("Expected FirebaseFirestoreException, got $e", cause)
            assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, cause?.code)
        }
    }
}
