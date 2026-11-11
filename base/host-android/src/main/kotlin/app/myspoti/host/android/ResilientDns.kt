package app.myspoti.host.android

import android.util.Log
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Resilient DNS resolver that prevents AdGuard DNS, NextDNS, or strict carrier firewalls
 * from breaking Spotify and YouTube Music streaming by offering a DNS-over-HTTPS (DoH) fallback.
 */
object ResilientDns : Dns {
    private const val TAG = "ResilientDns"

    // In-memory DNS cache with timestamp: Hostname -> (Addresses, TimestampMs)
    private data class CachedRecord(val addresses: List<InetAddress>, val timestamp: Long)
    private val cache = ConcurrentHashMap<String, CachedRecord>()
    private const val CACHE_TTL_MS = 10 * 60 * 1000L // 10 minutes

    // Minimal Bootstrap client strictly for resolving DoH queries (uses direct IP to avoid circular DNS)
    private val dohClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .dns(Dns.SYSTEM)
            .build()
    }

    override fun lookup(hostname: String): List<InetAddress> {
        // 1. Check in-memory cache
        cache[hostname]?.let { record ->
            if (System.currentTimeMillis() - record.timestamp < CACHE_TTL_MS) {
                return record.addresses
            }
        }

        // 2. Try standard System DNS first
        try {
            val systemAddresses = Dns.SYSTEM.lookup(hostname)
            // If the system DNS did not sinkhole the domain (AdGuard DNS returns 0.0.0.0 or 127.0.0.1 for blocked domains)
            val valid = systemAddresses.filterNot { it.isAnyLocalAddress || it.isLoopbackAddress }
            if (valid.isNotEmpty()) {
                cache[hostname] = CachedRecord(valid, System.currentTimeMillis())
                return valid
            }
        } catch (_: UnknownHostException) {
            Log.d(TAG, "System DNS failed for $hostname, attempting DoH fallback...")
        } catch (_: Exception) {
            // Network failure or timeout
        }

        // 3. Fallback to Cloudflare DNS-over-HTTPS (DoH)
        val dohAddresses = resolveDohCloudflare(hostname)
        if (dohAddresses.isNotEmpty()) {
            cache[hostname] = CachedRecord(dohAddresses, System.currentTimeMillis())
            Log.i(TAG, "Resolved $hostname via Cloudflare DoH fallback")
            return dohAddresses
        }

        // 4. Fallback to Google DNS-over-HTTPS
        val googleAddresses = resolveDohGoogle(hostname)
        if (googleAddresses.isNotEmpty()) {
            cache[hostname] = CachedRecord(googleAddresses, System.currentTimeMillis())
            Log.i(TAG, "Resolved $hostname via Google DoH fallback")
            return googleAddresses
        }

        throw UnknownHostException("Unable to resolve host \"$hostname\" via System DNS or DoH fallbacks")
    }

    private fun resolveDohCloudflare(hostname: String): List<InetAddress> {
        return try {
            val request = Request.Builder()
                .url("https://1.1.1.1/dns-query?name=$hostname&type=A")
                .header("Accept", "application/dns-json")
                .build()
            dohClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val body = response.body?.string() ?: return emptyList()
                parseDnsJson(body)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cloudflare DoH lookup failed for $hostname: ${e.message}")
            emptyList()
        }
    }

    private fun resolveDohGoogle(hostname: String): List<InetAddress> {
        return try {
            val request = Request.Builder()
                .url("https://dns.google/resolve?name=$hostname&type=A")
                .header("Accept", "application/json")
                .build()
            dohClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val body = response.body?.string() ?: return emptyList()
                parseDnsJson(body)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Google DoH lookup failed for $hostname: ${e.message}")
            emptyList()
        }
    }

    private fun parseDnsJson(jsonStr: String): List<InetAddress> {
        val addresses = mutableListOf<InetAddress>()
        try {
            val json = JSONObject(jsonStr)
            val answers = json.optJSONArray("Answer") ?: return emptyList()
            for (i in 0 until answers.length()) {
                val ans = answers.optJSONObject(i) ?: continue
                // Type 1 is DNS A record (IPv4)
                if (ans.optInt("type") == 1) {
                    val ip = ans.optString("data")
                    if (ip.isNotBlank()) {
                        runCatching {
                            val addr = InetAddress.getByName(ip)
                            if (!addr.isAnyLocalAddress && !addr.isLoopbackAddress) {
                                addresses.add(addr)
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return addresses
    }
}
