package be.jeedomtv.model.driver

import be.jeedomtv.model.Changes
import be.jeedomtv.model.JeedomConfig
import be.jeedomtv.model.Layout
import be.jeedomtv.model.PingInfo
import be.jeedomtv.model.TileAction
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Pilote HTTP du plugin `jeetvbe` (OkHttp + kotlinx.serialization). */
class JeedomHttpDriver internal constructor(
    private val config: JeedomConfig,
    private val client: OkHttpClient,
    /** null si l'adresse saisie est invalide : chaque appel réseau échouera proprement. */
    private val baseUrl: HttpUrl?,
) : JeedomDriver {

    constructor(config: JeedomConfig) : this(config, sharedClient, buildBaseUrl(config.host))
    override suspend fun ping(): PingInfo = TODO("MVP")
    override suspend fun layout(): Layout = TODO("MVP")
    override suspend fun exec(tile: String, action: TileAction, value: Double?): String? = TODO("MVP")
    override suspend fun changes(since: String?): Changes = TODO("MVP")

    internal companion object {
        private val sharedClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()
        }

        /** `192.168.1.10`, `jeedom.local:8080` ou `http://…` → URL de base de Jeedom. */
        fun buildBaseUrl(host: String): HttpUrl? {
            val trimmed = host.trim().trimEnd('/')
            if (trimmed.isEmpty()) return null
            val withScheme = if ("://" in trimmed) trimmed else "http://$trimmed"
            return "$withScheme/".toHttpUrlOrNull()
        }
    }
}
