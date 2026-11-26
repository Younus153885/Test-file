package com.example

import com.example.data.generator.IdentityGenerator
import com.example.data.engine.ResetExecutionEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentityGeneratorTest {

    @Test
    fun testGenerateNewIdentity() {
        val identity = IdentityGenerator.generateNewIdentity()
        assertNotNull(identity)
        assertEquals(16, identity.androidId.length)
        assertEquals(36, identity.advertisingId.length)
        assertEquals(15, identity.imei.length)
        assertTrue(identity.macAddress.contains(":"))
        assertTrue(identity.ipAddress.contains("."))
        assertTrue(identity.deviceModel.isNotBlank())
    }

    @Test
    fun testInitialStepsCount() {
        val steps = ResetExecutionEngine.createInitialSteps()
        assertTrue(steps.isNotEmpty())
    }
}
