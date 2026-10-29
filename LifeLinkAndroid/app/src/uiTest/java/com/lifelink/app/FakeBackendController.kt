package com.lifelink.app

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.time.Instant

/**
 * Local-only fake API for the separately signed uiTest APK.
 * No requests from this build are sent to Render or Supabase.
 */
object FakeBackendController {
    @Volatile
    private var server: MockWebServer? = null
    private val profileLock = Any()
    private var demoRole = "requester"
    private var demoDisplayName = "Demo Requester"
    private var demoCanRequest = true
    private var demoCanDonate = false
    @Volatile
    private var demoDonorProfileExists = false

    @JvmStatic
    @Synchronized
    fun start(context: Context) {
        check(context.applicationContext.packageName == "com.lifelink.app.uitest") {
            "The fake backend is restricted to the isolated UI-test application."
        }
        if (server != null) return
        synchronized(profileLock) {
            demoRole = "requester"
            demoDisplayName = "Demo Requester"
            demoCanRequest = true
            demoCanDonate = false
        }
        demoDonorProfileExists = false
        val fakeServer = MockWebServer()
        fakeServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl?.encodedPath.orEmpty()
                val method = request.method.orEmpty()
                return when {
                    path == "/health" -> json(200, """{"status":"ok","mode":"ui-test-fake-backend"}""")
                    path == "/v1/admin/me" -> json(200, """{"is_admin":false,"subject":"demo-user"}""")
                    path == "/v1/profile" && method == "GET" -> json(200, profileJson())
                    path == "/v1/profile" && method == "PUT" -> updateProfile(request)
                    path == "/v1/donor-map" -> json(200, donorMapJson())
                    path == "/v1/donor-profile" && method == "GET" ->
                        if (demoDonorProfileExists) {
                            json(200, donorProfileJson())
                        } else {
                            json(404, """{"detail":"No donor profile exists in this demo account yet."}""")
                        }
                    path == "/v1/donor-profile" && method == "PUT" -> {
                        demoDonorProfileExists = true
                        json(200, donorProfileJson())
                    }
                    path == "/v1/donor-profile" && method == "DELETE" -> {
                        demoDonorProfileExists = false
                        MockResponse().setResponseCode(204)
                    }
                    path == "/v1/emergency-requests" && method == "GET" -> json(200, "[]")
                    path == "/v1/emergency-requests" && method == "POST" ->
                        json(
                            201,
                            """
                            {
                                "request_id": "demo-request-created",
                                "status": "matching",
                                "notifications_created": 2,
                                "matches_responded": 0,
                                "matches": []
                            }
                            """.trimIndent(),
                        )
                    path.matches(Regex("/v1/emergency-requests/[^/]+")) ->
                        json(200, emergencyStatusJson(requestIdFromPath(path)))
                    path.endsWith("/cancel") -> json(200, requestActionJson(path, "cancelled"))
                    path.endsWith("/fulfill") -> json(200, requestActionJson(path, "fulfilled"))
                    path.endsWith("/contacts") -> json(200, "[]")
                    path == "/v1/conversations" -> json(200, conversationsJson())
                    path.endsWith("/messages") && method == "GET" -> json(200, messagesJson())
                    path.endsWith("/messages") && method == "POST" -> json(201, messageJson())
                    path.endsWith("/conversation") && method == "POST" -> json(200, conversationJson())
                    path.endsWith("/requests") && method == "GET" -> json(200, "[]")
                    path.startsWith("/v1/donor-profile/") -> json(200, donorProfileJson())
                    path == "/v1/push-token" -> MockResponse().setResponseCode(204)
                    path == "/v1/updates" -> json(200, "[]")
                    path == "/v1/donor-profile/presence" -> json(200, donorProfileJson())
                    else -> json(404, """{"detail":"This route is not implemented by the UI-test fake backend."}""")
                }
            }
        }
        fakeServer.start()
        server = fakeServer
    }

    @JvmStatic
    fun baseUrl(): String = "http://127.0.0.1:${checkNotNull(server) { "Fake backend has not started." }.port}/"

    private fun json(status: Int, body: String) = MockResponse()
        .setResponseCode(status)
        .addHeader("Content-Type", "application/json; charset=utf-8")
        .addHeader("X-LifeLink-Backend", "ui-test-fake")
        .setBody(body)

    private fun updateProfile(request: RecordedRequest): MockResponse {
        val body = runCatching { JsonParser.parseString(request.body.readUtf8()).asJsonObject }.getOrNull()
            ?: return json(400, """{"detail":"Invalid demo profile payload."}""")
        synchronized(profileLock) {
            demoRole = body.get("role")?.asString?.takeIf { it == "requester" || it == "donor" } ?: demoRole
            demoDisplayName = body.get("display_name")?.takeUnless { it.isJsonNull }?.asString ?: demoDisplayName
            demoCanRequest = body.get("can_request")?.asBoolean ?: demoCanRequest
            demoCanDonate = body.get("can_donate")?.asBoolean ?: demoCanDonate
            return json(200, profileJson())
        }
    }

    private fun profileJson(): String = synchronized(profileLock) {
        Gson().toJson(
            JsonObject().apply {
                addProperty("user_id", "demo-user")
                addProperty("email", "demo.requester@lifelink.test")
                addProperty("role", demoRole)
                addProperty("display_name", demoDisplayName)
                addProperty("can_request", demoCanRequest)
                addProperty("can_donate", demoCanDonate)
            },
        )
    }

    private fun donorProfileJson() =
        """
        {
            "user_id": "demo-user",
            "donor_id": "demo-user",
            "blood_type": "O+",
            "latitude": 11.244,
            "longitude": 125.003,
            "area": "DEMO AREA — Tacloban",
            "service_radius_km": 15,
            "availability_status": "available",
            "verified": true,
            "notifications_enabled": true,
            "display_name": "Demo Donor",
            "donor_note": "Synthetic profile for UI review only",
            "preferred_contact_method": "in_app",
            "profile_visible": true
        }
        """.trimIndent()

    private fun donorMapJson(): String {
        val now = Instant.now().toString()
        return """
            {
                "generated_at": "$now",
                "approximate_only": true,
                "entries": [
                    {
                        "area_label": "DEMO • Downtown",
                        "latitude": 11.244,
                        "longitude": 125.003,
                        "radius_meters": 500,
                        "blood_type": "O+",
                        "availability": "available",
                        "freshness_at": "$now",
                        "freshness_age_minutes": 1,
                        "is_stale": false
                    },
                    {
                        "area_label": "DEMO • North District",
                        "latitude": 11.255,
                        "longitude": 125.006,
                        "radius_meters": 700,
                        "blood_type": "A+",
                        "availability": "available",
                        "freshness_at": "$now",
                        "freshness_age_minutes": 1,
                        "is_stale": false
                    },
                    {
                        "area_label": "DEMO • East District",
                        "latitude": 11.238,
                        "longitude": 125.018,
                        "radius_meters": 600,
                        "blood_type": "B+",
                        "availability": "available",
                        "freshness_at": "$now",
                        "freshness_age_minutes": 1,
                        "is_stale": false
                    }
                ]
            }
        """.trimIndent()
    }

    private fun requestIdFromPath(path: String): String =
        path.substringAfter("/v1/emergency-requests/").substringBefore("/")

    private fun emergencyStatusJson(requestId: String) =
        """
        {
            "request_id": "$requestId",
            "status": "matching",
            "notifications_created": 2,
            "matches_responded": 1,
            "matches": []
        }
        """.trimIndent()

    private fun requestActionJson(path: String, status: String) =
        """{"request_id":"${requestIdFromPath(path)}","status":"$status"}"""

    private fun conversationsJson() = """[{
        "conversation_id":"demo-conversation-1","request_id":"demo-request-1",
        "donor_id":"demo-donor-1","requester_id":"demo-user",
        "last_message_at":"${Instant.now()}","created_at":"${Instant.now()}"
    }]"""

    private fun conversationJson() = """{
        "conversation_id":"demo-conversation-1","request_id":"demo-request-1",
        "donor_id":"demo-donor-1","requester_id":"demo-user",
        "last_message_at":"${Instant.now()}","created_at":"${Instant.now()}"
    }"""

    private fun messagesJson() = """[{
        "message_id":"demo-message-1","conversation_id":"demo-conversation-1",
        "sender_id":"demo-donor-1","body":"Hi! This is a simulated LifeLink message.",
        "created_at":"${Instant.now()}"
    }]"""

    private fun messageJson() = """{
        "message_id":"demo-message-sent","conversation_id":"demo-conversation-1",
        "sender_id":"demo-user","body":"This message was simulated by the UI-test backend.",
        "created_at":"${Instant.now()}"
    }"""
}
