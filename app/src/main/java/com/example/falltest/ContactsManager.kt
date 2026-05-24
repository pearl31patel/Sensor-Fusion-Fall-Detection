package com.example.falltest

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Contact(val name: String, val email: String)

object ContactsManager {
    private const val PREFS_NAME = "fall_guard_prefs"
    private const val KEY_CONTACTS = "contacts"
    const val KEY_MONITORING = "monitoring"
    private const val KEY_SENDER_EMAIL = "sender_email"
    private const val KEY_SENDER_PASSWORD = "sender_password"

    fun getContacts(context: Context): MutableList<Contact> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_CONTACTS, "[]") ?: "[]"
        return try {
            val array = JSONArray(json)
            MutableList(array.length()) { i ->
                val obj = array.getJSONObject(i)
                // support both "email" and legacy "phone" keys
                val email = if (obj.has("email")) obj.getString("email") else obj.optString("phone", "")
                Contact(obj.getString("name"), email)
            }
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun saveContacts(context: Context, contacts: List<Contact>) {
        val array = JSONArray()
        contacts.forEach { contact ->
            array.put(JSONObject().apply {
                put("name", contact.name)
                put("email", contact.email)
            })
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_CONTACTS, array.toString()).apply()
    }

    fun addContact(context: Context, contact: Contact) {
        val contacts = getContacts(context)
        contacts.add(contact)
        saveContacts(context, contacts)
    }

    fun removeContact(context: Context, index: Int) {
        val contacts = getContacts(context)
        if (index in contacts.indices) {
            contacts.removeAt(index)
            saveContacts(context, contacts)
        }
    }

    fun setMonitoring(context: Context, active: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_MONITORING, active).apply()
    }

    fun isMonitoring(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_MONITORING, false)
    }

    fun getSenderEmail(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_SENDER_EMAIL, "") ?: ""
    }

    fun getSenderPassword(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_SENDER_PASSWORD, "") ?: ""
    }

    fun setSenderCredentials(context: Context, email: String, password: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SENDER_EMAIL, email)
            .putString(KEY_SENDER_PASSWORD, password)
            .apply()
    }
}
