package com.example.base

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.example.R
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.MemoryCacheSettings
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
abstract class FirestoreEmulatorTestBase {
    protected lateinit var firestore: FirebaseFirestore
    protected lateinit var auth: FirebaseAuth
    protected lateinit var databaseId: String
    protected val projectId = "demo-no-project"

    @Before
    open fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        databaseId = context.getString(R.string.firestore_database_id)

        if (FirebaseApp.getApps(context).isEmpty()) {
            val options = FirebaseOptions.Builder()
                .setProjectId(projectId)
                .setApplicationId("1:1234567890:android:abcdef")
                .setApiKey("fake-api-key-for-emulator")
                .build()
            FirebaseApp.initializeApp(context, options)
        }
        firestore = FirebaseFirestore.getInstance(databaseId)
        auth = FirebaseAuth.getInstance()

        try {
            firestore.useEmulator("127.0.0.1", 8085)
            auth.useEmulator("127.0.0.1", 9099)
        } catch (_: IllegalStateException) {
            // Emulators already bound in this singleton
        }

        val settings = FirebaseFirestoreSettings.Builder()
            .setLocalCacheSettings(MemoryCacheSettings.newBuilder().build())
            .build()
        try {
            firestore.firestoreSettings = settings
        } catch (_: IllegalStateException) {}

        clearEmulatorData()
    }

    @After
    open fun tearDown() {
        try {
            auth.signOut()
        } catch (_: Exception) {}
    }

    protected fun <T> awaitTask(task: Task<T>, timeoutSec: Long = 10): T {
        val deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(timeoutSec)
        while (!task.isComplete) {
            if (System.currentTimeMillis() > deadline) {
                throw TimeoutException("Task timed out after $timeoutSec seconds")
            }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        if (task.isSuccessful) {
            return task.result
        } else {
            throw task.exception ?: RuntimeException("Task failed without exception")
        }
    }

    protected fun signInTestUser(email: String = "user@example.com", pass: String = "password123"): String {
        try {
            awaitTask(auth.createUserWithEmailAndPassword(email, pass))
        } catch (_: Exception) {
            awaitTask(auth.signInWithEmailAndPassword(email, pass))
        }
        return auth.currentUser!!.uid
    }

    protected fun seedAdminRoleInEmulator(uid: String, email: String) {
        val url = URL("http://127.0.0.1:8085/v1/projects/$projectId/databases/$databaseId/documents/admins?documentId=$uid")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Authorization", "Bearer owner")
            setRequestProperty("Content-Type", "application/json")
            connectTimeout = 3000
            readTimeout = 3000
        }
        val body = """
            {
              "fields": {
                "email": { "stringValue": "$email" },
                "role": { "stringValue": "super_admin" }
              }
            }
        """.trimIndent()
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        check(conn.responseCode in 200..299) {
            "Failed to seed admin role in emulator (HTTP ${conn.responseCode})"
        }
        conn.disconnect()
    }

    private fun clearEmulatorData() {
        fun deleteUrl(urlString: String) {
            val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
                requestMethod = "DELETE"
                connectTimeout = 2000
                readTimeout = 2000
            }
            val code = conn.responseCode
            conn.disconnect()
            check(code in 200..299) {
                "Failed to clear emulator at $urlString (HTTP $code). Is the emulator running?"
            }
        }
        deleteUrl("http://127.0.0.1:8085/emulator/v1/projects/$projectId/databases/$databaseId/documents")
        deleteUrl("http://127.0.0.1:9099/emulator/v1/projects/$projectId/accounts")
    }
}
