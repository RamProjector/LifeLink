package com.lifelink.app.core.auth

import android.content.Context

enum class UserRole(val title: String, val description: String) {
    REQUESTER("I need blood", "Create a request and find eligible donors nearby."),
    DONOR("I want to donate", "Manage your availability and respond to nearby requests.")
}

class UserRoleStore(context: Context) {
    private val preferences = context.getSharedPreferences("lifelink_user_role", Context.MODE_PRIVATE)

    fun get(): UserRole? = preferences.getString("role", null)?.let { value ->
        runCatching { UserRole.valueOf(value) }.getOrNull()
    }

    fun save(role: UserRole) {
        preferences.edit().putString("role", role.name).apply()
    }

    fun clear() {
        preferences.edit().remove("role").apply()
    }
}
