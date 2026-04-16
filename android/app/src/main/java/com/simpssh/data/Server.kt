package com.simpssh.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class InitScript(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    /// Where shell `cd`s and where SFTP opens. Empty = home.
    val workingDir: String = "",
    val content: String,
)

data class Server(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val host: String,
    val port: Int = 22,
    val user: String,
    val password: String,
    val initScripts: List<InitScript> = emptyList(),
)

class ServerRepository(context: Context) {
    private val prefs = context.getSharedPreferences("simpssh_servers", Context.MODE_PRIVATE)

    fun load(): List<Server> {
        val raw = prefs.getString(KEY_LIST, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i -> arr.getJSONObject(i).toServer() }
        }.getOrElse { emptyList() }
    }

    fun save(servers: List<Server>) {
        val arr = JSONArray()
        servers.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(KEY_LIST, arr.toString()).apply()
    }

    fun upsert(server: Server) {
        val list = load().toMutableList()
        val idx = list.indexOfFirst { it.id == server.id }
        if (idx >= 0) list[idx] = server else list += server
        save(list)
    }

    fun delete(id: String) {
        save(load().filterNot { it.id == id })
    }

    private companion object {
        const val KEY_LIST = "servers_v1"
    }
}

private fun JSONObject.toServer(): Server {
    val a = getJSONArray("initScripts")
    val scripts = (0 until a.length()).map { i ->
        val so = a.getJSONObject(i)
        InitScript(
            id = so.optString("id", UUID.randomUUID().toString()),
            name = so.optString("name", "脚本"),
            workingDir = so.optString("workingDir", ""),
            content = so.optString("content", ""),
        )
    }
    return Server(
        id = getString("id"),
        name = getString("name"),
        host = getString("host"),
        port = optInt("port", 22),
        user = getString("user"),
        password = optString("password", ""),
        initScripts = scripts,
    )
}

private fun Server.toJson(): JSONObject = JSONObject().apply {
    put("id", id)
    put("name", name)
    put("host", host)
    put("port", port)
    put("user", user)
    put("password", password)
    val arr = JSONArray()
    initScripts.forEach { s ->
        arr.put(JSONObject().apply {
            put("id", s.id)
            put("name", s.name)
            put("workingDir", s.workingDir)
            put("content", s.content)
        })
    }
    put("initScripts", arr)
}
