package com.animus.aireplyassistant.context

class SessionMemoryStore(private val ttlMs: Long) {
    private data class Entry(val value: String, val expiresAt: Long)
    private val map = HashMap<String, Entry>()

    fun get(key: String): String? {
        val now = System.currentTimeMillis()
        val e = map[key] ?: return null
        if (now > e.expiresAt) {
            map.remove(key)
            return null
        }
        return e.value
    }

    fun put(key: String, value: String) {
        val now = System.currentTimeMillis()
        map[key] = Entry(value, now + ttlMs)
    }
}

