package com.lifelink.app.data

import com.lifelink.app.data.remote.LifeLinkApi
import com.lifelink.app.data.repository.DonorProfileRepositoryImpl
import com.lifelink.app.domain.BloodType
import com.lifelink.app.domain.DonorAvailability
import com.lifelink.app.domain.DonorProfileMe
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/**
 * Verifies the separate donor-profile flow talks to the right endpoints and
 * maps the server's verified/available gating back into the domain model.
 */
class DonorProfileRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var repository: DonorProfileRepositoryImpl

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val api = Retrofit.Builder().baseUrl(server.url("/"))
            .addConverterFactory(GsonConverterFactory.create()).build().create(LifeLinkApi::class.java)
        repository = DonorProfileRepositoryImpl(api)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun a_missing_profile_returns_null_instead_of_failing() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        assertNull(repository.load())
        assertEquals("/v1/donor-profile", server.takeRequest().path)
    }

    @Test
    fun saving_posts_to_the_donor_profile_endpoint_and_maps_the_response() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"user_id":"alice","donor_id":"alice","blood_type":"O+","latitude":11.24,"longitude":125.0,""" +
                    """"area":"Tacloban","service_radius_km":15.0,"availability_status":"available",""" +
                    """"last_donation_date":null,"verified":true,"notifications_enabled":true,""" +
                    """"display_name":"Alice","donor_note":"","preferred_contact_method":"in_app","profile_visible":true}""",
            ),
        )
        val saved = repository.save(
            DonorProfileMe(bloodType = BloodType.O_POS, latitude = 11.24, longitude = 125.0, area = "Tacloban"),
        )
        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("/v1/donor-profile", request.path)
        assertEquals(BloodType.O_POS, saved.bloodType)
        assertTrue(saved.verified)
        assertTrue(saved.isMatchable)
    }

    @Test
    fun toggling_availability_patches_the_availability_endpoint() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"user_id":"alice","donor_id":"alice","blood_type":"O+","latitude":11.24,"longitude":125.0,""" +
                    """"area":"","service_radius_km":15.0,"availability_status":"paused",""" +
                    """"last_donation_date":null,"verified":true,"notifications_enabled":true,""" +
                    """"display_name":"Alice","donor_note":"","preferred_contact_method":"in_app","profile_visible":true}""",
            ),
        )
        val updated = repository.setAvailability(DonorAvailability.PAUSED)
        val request = server.takeRequest()
        assertEquals("PATCH", request.method)
        assertEquals("/v1/donor-profile/availability", request.path)
        assertEquals(DonorAvailability.PAUSED, updated.availability)
        assertFalse(updated.isMatchable)
    }

    @Test
    fun opting_out_deletes_the_profile() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))
        repository.optOut()
        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("/v1/donor-profile", request.path)
    }
}
