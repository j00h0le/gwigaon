package com.coral.gwigaon.sync

import java.util.Base64

/**
 * Where the cloud is, from the build (BuildConfig). Sync runs only with a URL and a publishable key: a secret or
 * service-role key in the app would hand every trip to anyone holding the APK (SPEC boundaries), so such a build
 * stays offline instead.
 */
data class CloudConfig(val supabaseUrl: String, val supabaseKey: String, val contactPageUrl: String) {
    val syncEnabled: Boolean get() = supabaseUrl.isNotBlank() && supabaseKey.isNotBlank() && isPublishable(supabaseKey)

    /** Never prints the key. */
    override fun toString(): String = "CloudConfig(url=$supabaseUrl, syncEnabled=$syncEnabled, contactPage=$contactPageUrl)"

    companion object {
        /** `sb_secret_…` keys and legacy JWT keys with the `service_role` role are refused. */
        fun isPublishable(key: String): Boolean {
            if (key.startsWith("sb_secret_")) return false
            val parts = key.split('.')
            if (parts.size != JWT_PARTS) return true
            val claims = runCatching { String(Base64.getUrlDecoder().decode(parts[1].trimEnd('='))) }.getOrNull()
                ?: return false
            return !claims.replace(" ", "").contains("\"role\":\"service_role\"")
        }

        private const val JWT_PARTS = 3
    }
}
