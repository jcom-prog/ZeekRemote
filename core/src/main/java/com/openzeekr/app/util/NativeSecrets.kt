package com.openzeekr.app.util


/**
 * App-global secret accessors backed by a small JNI native library (libozsecrets.so).
 *
 * The secret VALUES are injected AT BUILD TIME from the gitignored `secrets.properties` into a
 * gitignored C header (`core/src/main/cpp/secrets_generated.h`) that the committed C source
 * (`ozsecrets.c`) #includes - so no real secret is ever committed to source, and none is a
 * Kotlin/Java `BuildConfig` string constant sitting in the DEX string pool.
 *
 * SECURITY NOTE (honest): this is stronger OBFUSCATION than a DEX string constant, NOT
 * encryption and NOT a root-only store. The `.so` still ships inside the APK (world-readable,
 * no root needed) and its embedded string constants remain recoverable with `strings` /
 * disassembly. This raises the bar for a casual unpacker; it does not make the keys secret.
 *
 * Resilience: on a host JVM (unit tests) or if the library cannot be loaded, every accessor
 * returns "" - identical to an unbaked (clean-repo) build - instead of throwing
 * UnsatisfiedLinkError. The public RSA/password key stays in BuildConfig (it is a public key,
 * nothing is gained by hiding it), so it is intentionally NOT here.
 */
object NativeSecrets {


    @Volatile private var available: Boolean = false


    init {
        available = runCatching { System.loadLibrary("ozsecrets") }.isSuccess
    }


    /** True when the native lib loaded (i.e. on a real device build). */
    val isAvailable: Boolean get() = available


    private inline fun guarded(block: () -> String): String =
        if (available) runCatching(block).getOrDefault("") else ""


    fun hmacAccessKey(): String = guarded { nHmacAccessKey() }
    fun hmacSecretKey(): String = guarded { nHmacSecretKey() }
    fun prodSecret(): String = guarded { nProdSecret() }
    fun xchangerSignSecret(): String = guarded { nXchangerSignSecret() }
    fun overseasAccessKey(): String = guarded { nOverseasAccessKey() }
    fun overseasSecretKey(): String = guarded { nOverseasSecretKey() }
    fun inboxAuthSecret(): String = guarded { nInboxAuthSecret() }
    fun vinKey(): String = guarded { nVinKey() }
    fun vinIv(): String = guarded { nVinIv() }


    // ---- Per-region signing secrets (EU / EM / SEA). Only these 3 differ by region; everything
    //      else is shared. A region whose set isn't baked falls back to the EU/default set - so
    //      LA/ME (EM) work as today until EM is extracted, and a clean/unbaked build returns ""
    //      everywhere (as before). Callers pass Region.extractorRegion ("EU"/"EM"/"SEA"). ----
    fun hmacAccessKey(region: String): String =
        regionValue(region, { nHmacAccessKeySea() }, { nHmacAccessKeyEm() }).ifBlank { hmacAccessKey() }
    fun hmacSecretKey(region: String): String =
        regionValue(region, { nHmacSecretKeySea() }, { nHmacSecretKeyEm() }).ifBlank { hmacSecretKey() }
    fun prodSecret(region: String): String =
        regionValue(region, { nProdSecretSea() }, { nProdSecretEm() }).ifBlank { prodSecret() }


    private inline fun regionValue(region: String, sea: () -> String, em: () -> String): String =
        when (region.uppercase()) { "SEA" -> guarded(sea); "EM" -> guarded(em); else -> "" }


    // ---- JNI bindings (implemented in ozsecrets.c). Names must not be renamed/stripped;
    //      see the -keep rule for com.openzeekr.app.util.NativeSecrets in proguard-rules.pro. ----
    private external fun nHmacAccessKey(): String
    private external fun nHmacSecretKey(): String
    private external fun nProdSecret(): String
    private external fun nXchangerSignSecret(): String
    private external fun nOverseasAccessKey(): String
    private external fun nOverseasSecretKey(): String
    private external fun nInboxAuthSecret(): String
    private external fun nVinKey(): String
    private external fun nVinIv(): String
    private external fun nHmacAccessKeySea(): String
    private external fun nHmacSecretKeySea(): String
    private external fun nProdSecretSea(): String
    private external fun nHmacAccessKeyEm(): String
    private external fun nHmacSecretKeyEm(): String
    private external fun nProdSecretEm(): String
}
