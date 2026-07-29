package com.amaya.intelligence.data.repository

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpdateRepositoryTest {
    @Test
    fun comparesReleaseVersions() {
        assertTrue(UpdateRepository.isVersionNewer("1.0.1", "1.0.0"))
        assertTrue(UpdateRepository.isVersionNewer("1.0.0", "1.0.0-rc1"))
        assertFalse(UpdateRepository.isVersionNewer("1.0.0", "1.0.0"))
        assertFalse(UpdateRepository.isVersionNewer("1.0.0", "1.0.1"))
        assertFalse(UpdateRepository.isVersionNewer("1.0.0-beta", "1.0.0"))
    }
}
