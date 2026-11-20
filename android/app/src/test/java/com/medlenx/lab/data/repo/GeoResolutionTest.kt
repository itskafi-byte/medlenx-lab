package com.medlenx.lab.data.repo

import com.medlenx.lab.data.model.BdDistrict
import com.medlenx.lab.data.model.BdLocations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoResolutionTest {
    private val locations = BdLocations(districts = mapOf(
        "Dhaka" to BdDistrict(upazilas = listOf("Dhamrai", "Savar"), territories = listOf("Dhaka Territory", "Savar Zone")),
        "Narayanganj" to BdDistrict(upazilas = listOf("Sonargaon", "Araihazar"), territories = listOf("Narayanganj Territory")),
    ))

    @Test fun matchesOnlineNamesAndPrefersAssignedTerritory() {
        val result = Geofence.resolveHierarchy("Dhaka", locations, "Dhaka Territory", listOf("Dhaka"), listOf("Savar Upazila"))
        assertEquals("Dhaka", result.district)
        assertEquals("Savar", result.upazila)
        assertEquals("Dhaka Territory", result.territory)
    }

    @Test fun offlineNeverInventsUpazila() {
        val result = Geofence.resolveHierarchy("Dhaka", locations, "Dhaka Territory", emptyList(), emptyList())
        assertEquals("", result.upazila)
        assertEquals("Dhaka Territory", result.territory)
        assertEquals("", Geofence.resolveHierarchy("", locations, "Dhaka Territory", emptyList(), emptyList()).district)
    }

    @Test fun conflictingDistrictDoesNotBorrowOtherLocality() {
        val result = Geofence.resolveHierarchy("Dhaka", locations, "", listOf("Narayanganj"), listOf("Savar"))
        assertEquals("", result.upazila)
        assertTrue(result.warning.isNotBlank())
    }

    @Test fun rejectsPartialWordAndUnassignedTerritory() {
        val result = Geofence.resolveHierarchy("Dhaka", locations, "Narayanganj Territory", emptyList(), listOf("Savarian"))
        assertEquals("", result.upazila)
        assertEquals("", result.territory)
    }
}
