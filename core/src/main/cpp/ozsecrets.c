/*
 * ozsecrets.c - JNI accessors for the app-global secret strings.
 *
 * This committed source contains NO real secret. The values come from
 * "secrets_generated.h", which the :core Gradle script writes at BUILD TIME from the
 * gitignored secrets.properties (and which is itself gitignored). When that header is
 * absent (fresh clone) or a value is blank, the macros below fall back to "" so the app
 * simply starts unconfigured - identical to a clean-repo build.
 *
 * SECURITY: this keeps the secrets out of the Java/Kotlin DEX string pool, which is
 * stronger obfuscation than a BuildConfig constant. It is NOT encryption: the compiled
 * .so still ships inside the APK and its string constants remain recoverable with
 * `strings`/disassembly, no root required. It raises the bar; it does not make keys secret.
 */
#include <jni.h>

/* Pull in the build-time-generated values if present (robust to a missing file). */
#if defined(__has_include)
#  if __has_include("secrets_generated.h")
#    include "secrets_generated.h"
#  endif
#endif

/* Fallbacks so the source always compiles even without the generated header. */
#ifndef OZ_SEC_HMAC_ACCESS_KEY
#define OZ_SEC_HMAC_ACCESS_KEY ""
#endif
#ifndef OZ_SEC_HMAC_SECRET_KEY
#define OZ_SEC_HMAC_SECRET_KEY ""
#endif
#ifndef OZ_SEC_PROD_SECRET
#define OZ_SEC_PROD_SECRET ""
#endif
#ifndef OZ_SEC_XCHANGER_SIGN_SECRET
#define OZ_SEC_XCHANGER_SIGN_SECRET ""
#endif
#ifndef OZ_SEC_OVERSEAS_ACCESS_KEY
#define OZ_SEC_OVERSEAS_ACCESS_KEY ""
#endif
#ifndef OZ_SEC_OVERSEAS_SECRET_KEY
#define OZ_SEC_OVERSEAS_SECRET_KEY ""
#endif
#ifndef OZ_SEC_INBOX_AUTH_SECRET
#define OZ_SEC_INBOX_AUTH_SECRET ""
#endif
#ifndef OZ_SEC_VIN_KEY
#define OZ_SEC_VIN_KEY ""
#endif
#ifndef OZ_SEC_VIN_IV
#define OZ_SEC_VIN_IV ""
#endif
/* Per-region signing sets (SEA + EM). Blank -> the Kotlin side falls back to the EU/default set. */
#ifndef OZ_SEC_SEA_HMAC_ACCESS_KEY
#define OZ_SEC_SEA_HMAC_ACCESS_KEY ""
#endif
#ifndef OZ_SEC_SEA_HMAC_SECRET_KEY
#define OZ_SEC_SEA_HMAC_SECRET_KEY ""
#endif
#ifndef OZ_SEC_SEA_PROD_SECRET
#define OZ_SEC_SEA_PROD_SECRET ""
#endif
#ifndef OZ_SEC_EM_HMAC_ACCESS_KEY
#define OZ_SEC_EM_HMAC_ACCESS_KEY ""
#endif
#ifndef OZ_SEC_EM_HMAC_SECRET_KEY
#define OZ_SEC_EM_HMAC_SECRET_KEY ""
#endif
#ifndef OZ_SEC_EM_PROD_SECRET
#define OZ_SEC_EM_PROD_SECRET ""
#endif

/*
 * JNI method names must match com.openzeekr.app.util.NativeSecrets exactly, so the
 * corresponding Kotlin class + native methods must be kept (see proguard-rules.pro).
 * NativeSecrets is a Kotlin `object`, so these are instance methods (jobject thiz).
 */
#define OZ_SECRET_FN(name, value)                                                        \
    JNIEXPORT jstring JNICALL                                                            \
    Java_com_openzeekr_app_util_NativeSecrets_##name(JNIEnv* env, jobject thiz) {        \
        (void) thiz;                                                                     \
        return (*env)->NewStringUTF(env, value);                                         \
    }

OZ_SECRET_FN(nHmacAccessKey,      OZ_SEC_HMAC_ACCESS_KEY)
OZ_SECRET_FN(nHmacSecretKey,      OZ_SEC_HMAC_SECRET_KEY)
OZ_SECRET_FN(nProdSecret,         OZ_SEC_PROD_SECRET)
OZ_SECRET_FN(nXchangerSignSecret, OZ_SEC_XCHANGER_SIGN_SECRET)
OZ_SECRET_FN(nOverseasAccessKey,  OZ_SEC_OVERSEAS_ACCESS_KEY)
OZ_SECRET_FN(nOverseasSecretKey,  OZ_SEC_OVERSEAS_SECRET_KEY)
OZ_SECRET_FN(nInboxAuthSecret,    OZ_SEC_INBOX_AUTH_SECRET)
OZ_SECRET_FN(nVinKey,             OZ_SEC_VIN_KEY)
OZ_SECRET_FN(nVinIv,              OZ_SEC_VIN_IV)
OZ_SECRET_FN(nHmacAccessKeySea,   OZ_SEC_SEA_HMAC_ACCESS_KEY)
OZ_SECRET_FN(nHmacSecretKeySea,   OZ_SEC_SEA_HMAC_SECRET_KEY)
OZ_SECRET_FN(nProdSecretSea,      OZ_SEC_SEA_PROD_SECRET)
OZ_SECRET_FN(nHmacAccessKeyEm,    OZ_SEC_EM_HMAC_ACCESS_KEY)
OZ_SECRET_FN(nHmacSecretKeyEm,    OZ_SEC_EM_HMAC_SECRET_KEY)
OZ_SECRET_FN(nProdSecretEm,       OZ_SEC_EM_PROD_SECRET)
