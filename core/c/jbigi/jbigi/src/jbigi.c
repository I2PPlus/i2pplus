#include "jbigi.h"
#include <gmp.h>
#include <limits.h>
#include <stdio.h>
#include <string.h>

/* Cached exception class references - thread-safe init */
static jclass ArithmeticException_class = NULL;
static jclass NullPointerException_class = NULL;
static jclass IllegalArgumentException_class = NULL;
static volatile int jclasses_init = 0;

/* Thread-safe initialization of cached class references */
static void init_jclasses(JNIEnv *env) {
    if (jclasses_init)
        return;

    /* Simple spinlock for first init */
    if (__sync_bool_compare_and_swap(&jclasses_init, 0, 1)) {
        jclass tmp;

        tmp = (*env)->FindClass(env, "java/lang/ArithmeticException");
        if (tmp && !(*env)->ExceptionCheck(env)) {
            ArithmeticException_class = (*env)->NewGlobalRef(env, tmp);
        }

        tmp = (*env)->FindClass(env, "java/lang/NullPointerException");
        if (tmp && !(*env)->ExceptionCheck(env)) {
            NullPointerException_class = (*env)->NewGlobalRef(env, tmp);
        }

        tmp = (*env)->FindClass(env, "java/lang/IllegalArgumentException");
        if (tmp && !(*env)->ExceptionCheck(env)) {
            IllegalArgumentException_class = (*env)->NewGlobalRef(env, tmp);
        }
    }
}

/* Cleanup global refs on JVM unload */
JNIEXPORT void JNICALL JNI_OnUnload(JavaVM *jvm, void *reserved) {
    JNIEnv *env;
    (*jvm)->GetEnv(jvm, (void **)&env, JNI_VERSION_1_6);

    if (ArithmeticException_class) {
        (*env)->DeleteGlobalRef(env, ArithmeticException_class);
    }
    if (NullPointerException_class) {
        (*env)->DeleteGlobalRef(env, NullPointerException_class);
    }
    if (IllegalArgumentException_class) {
        (*env)->DeleteGlobalRef(env, IllegalArgumentException_class);
    }
}

/* Returns 1 on success, 0 on failure */
int convert_j2mp(JNIEnv *env, jbyteArray jvalue, mpz_t *mvalue);
void convert_mp2j(JNIEnv *env, mpz_t mvalue, jbyteArray *jvalue);

/* JNI null/empty array validation - prevents JVM crashes on invalid inputs */
static int check_byte_array(JNIEnv *env, jbyteArray arr, const char *name) {
    if (arr == NULL) {
        /* Thread-safe init, use cached class */
        init_jclasses(env);
        if (NullPointerException_class) {
            (*env)->ThrowNew(env, NullPointerException_class, name);
        }
        return -1;
    }
    jsize len = (*env)->GetArrayLength(env, arr);
    if (len == 0) {
        /* Thread-safe init, use cached class */
        init_jclasses(env);
        if (IllegalArgumentException_class) {
            (*env)->ThrowNew(env, IllegalArgumentException_class, name);
        }
        return -1;
    }
    return 0;
}

/* See README.md for full version history */
#define JBIGI_VERSION 5

/* Native method implementations */

/* Overflow-safe version parsing */
static int parse_version_part(const char *p, int *v) {
    *v = 0;
    while (*p && *p >= '0' && *p <= '9') {
        if (*v > INT_MAX / 10)
            return -1;
        *v = *v * 10 + (*p - '0');
        p++;
    }
    return 0;
}

JNIEXPORT jint JNICALL
Java_net_i2p_util_NativeBigInteger_nativeJbigiVersion(JNIEnv *env, jclass cls) {
    return (jint)JBIGI_VERSION;
}

/*
 * Since version 3, fixed for dynamic builds in version 4
 * Handle multi-digit version numbers (GMP 10+)
 * Overflow-safe version parsing
 */
JNIEXPORT jint JNICALL Java_net_i2p_util_NativeBigInteger_nativeGMPMajorVersion(
    JNIEnv *env, jclass cls) {
    int v;
    parse_version_part(gmp_version, &v);
    return (jint)v;
}

/*
 * Since version 3, fixed for dynamic builds in version 4
 * Handle multi-digit version numbers (GMP 10+)
 * Overflow-safe version parsing
 */
JNIEXPORT jint JNICALL Java_net_i2p_util_NativeBigInteger_nativeGMPMinorVersion(
    JNIEnv *env, jclass cls) {
    int v = 0;
    const char *p = gmp_version;

    /* Skip major */
    while (*p && (*p < '0' || *p > '9'))
        p++;
    while (*p && *p >= '0' && *p <= '9')
        p++;
    while (*p && (*p < '0' || *p > '9'))
        p++;

    parse_version_part(p, &v);
    return (jint)v;
}

/*
 * Since version 3, fixed for dynamic builds in version 4
 * Handle multi-digit version numbers (GMP 10+)
 * Overflow-safe version parsing
 */
JNIEXPORT jint JNICALL Java_net_i2p_util_NativeBigInteger_nativeGMPPatchVersion(
    JNIEnv *env, jclass cls) {
    int v = 0;
    const char *p = gmp_version;

    /* Skip major.minor */
    while (*p && (*p < '0' || *p > '9'))
        p++;
    while (*p && *p >= '0' && *p <= '9')
        p++;
    while (*p && (*p < '0' || *p > '9'))
        p++;
    while (*p && *p >= '0' && *p <= '9')
        p++;
    while (*p && (*p < '0' || *p > '9'))
        p++;

    parse_version_part(p, &v);
    return (jint)v;
}

/*
 * Native method: nativeModPow
 *
 * Class:     net_i2p_util_NativeBigInteger
 * Method:    nativeModPow
 * Signature: ([B[B[B)[B
 *
 * From the javadoc:
 *
 * calculate (base ^ exponent) % modulus.
 * @param jbase big endian twos complement representation of the base
 *             Negative values allowed as of version 3
 * @param jexp big endian twos complement representation of the exponent
 *             Must be greater than or equal to zero.
 *             As of version 3, throws java.lang.ArithmeticException if < 0.
 * @param jmod big endian twos complement representation of the modulus
 *             Must be greater than zero.
 *             As of version 3, throws java.lang.ArithmeticException if <= 0.
 *             Prior to version 3, crashed the JVM if <= 0.
 * @return big endian twos complement representation of (base ^ exponent) %
 * modulus
 * @throws java.lang.ArithmeticException if jmod is <= 0
 */

JNIEXPORT jbyteArray JNICALL Java_net_i2p_util_NativeBigInteger_nativeModPow(
    JNIEnv *env, jclass cls, jbyteArray jbase, jbyteArray jexp,
    jbyteArray jmod) {
    /* Add null/empty checks */
    if (check_byte_array(env, jbase, "base") != 0)
        return NULL;
    if (check_byte_array(env, jexp, "exponent") != 0)
        return NULL;
    if (check_byte_array(env, jmod, "modulus") != 0)
        return NULL;

    /* Initialize cached class references */
    init_jclasses(env);

    /*
     * 1) Convert base, exponent, modulus into the format libgmp understands
     * 2) Call libgmp's modPow.
     * 3) Convert libgmp's result into a big endian twos complement number.
     */

    mpz_t mbase;
    mpz_t mexp;
    mpz_t mmod;
    /* Use separate result variable */
    mpz_t mresult;
    jbyteArray jresult;

    /* Check conversion success */
    if (!convert_j2mp(env, jmod, &mmod))
        return NULL;
    if (mpz_sgn(mmod) <= 0) {
        mpz_clear(mmod);
        if (ArithmeticException_class)
            (*env)->ThrowNew(env, ArithmeticException_class,
                             "Modulus must be positive");
        return NULL;
    }

    /*
     * Disallow negative exponents to avoid divide by zero exception if no
     * inverse exists
     */

    /* Check conversion success */
    if (!convert_j2mp(env, jexp, &mexp)) {
        mpz_clear(mmod);
        return NULL;
    }
    if (mpz_sgn(mexp) < 0) {
        mpz_clears(mmod, mexp, NULL);
        if (ArithmeticException_class)
            (*env)->ThrowNew(env, ArithmeticException_class,
                             "Exponent cannot be negative");
        return NULL;
    }

    /* Check conversion success */
    if (!convert_j2mp(env, jbase, &mbase)) {
        mpz_clears(mmod, mexp, NULL);
        return NULL;
    }

    /*
     * Perform the actual powmod. Use separate result variable.
     */
    mpz_init(mresult);
    mpz_powm(mresult, mbase, mexp, mmod);

    convert_mp2j(env, mresult, &jresult);

    mpz_clears(mbase, mexp, mmod, mresult, NULL);

    return jresult;
}

/*
 * Native method: nativeModPowCT (constant-time)
 * Class:     net_i2p_util_NativeBigInteger
 * Method:    nativeModPowCT
 * Signature: ([B[B[B)[B
 *
 * Constant time version of nativeModPow()
 *
 * From the javadoc:
 *
 * calculate (base ^ exponent) % modulus.
 * @param jbase big endian twos complement representation of the base
 *              Negative values allowed.
 * @param jexp big endian twos complement representation of the exponent
 *             Must be positive.
 * @param jmod big endian twos complement representation of the modulus
 *             Must be positive and odd.
 * @return big endian twos complement representation of (base ^ exponent) %
 * modulus
 * @throws java.lang.ArithmeticException if jmod or jexp is <= 0, or jmod is
 * even.
 * @since version 3
 */

JNIEXPORT jbyteArray JNICALL Java_net_i2p_util_NativeBigInteger_nativeModPowCT(
    JNIEnv *env, jclass cls, jbyteArray jbase, jbyteArray jexp,
    jbyteArray jmod) {
    /* Add null/empty checks */
    if (check_byte_array(env, jbase, "base") != 0)
        return NULL;
    if (check_byte_array(env, jexp, "exponent") != 0)
        return NULL;
    if (check_byte_array(env, jmod, "modulus") != 0)
        return NULL;

    /* Initialize cached class references */
    init_jclasses(env);

    mpz_t mbase;
    mpz_t mexp;
    mpz_t mmod;
    /* Use separate result variable */
    mpz_t mresult;
    jbyteArray jresult;

    /* Check conversion success */
    if (!convert_j2mp(env, jmod, &mmod))
        return NULL;
    if (mpz_sgn(mmod) <= 0) {
        mpz_clear(mmod);
        if (ArithmeticException_class)
            (*env)->ThrowNew(env, ArithmeticException_class,
                             "Modulus must be positive");
        return NULL;
    }
    // Disallow even modulus as specified in the GMP docs
    if (mpz_odd_p(mmod) == 0) {
        mpz_clear(mmod);
        if (ArithmeticException_class)
            (*env)->ThrowNew(env, ArithmeticException_class,
                             "Modulus must be odd");
        return NULL;
    }

    // Disallow negative or zero exponents as specified in the GMP docs

    /* Check conversion success */
    if (!convert_j2mp(env, jexp, &mexp)) {
        mpz_clear(mmod);
        return NULL;
    }
    if (mpz_sgn(mexp) <= 0) {
        mpz_clears(mmod, mexp, NULL);
        if (ArithmeticException_class)
            (*env)->ThrowNew(env, ArithmeticException_class,
                             "Exponent must be positive");
        return NULL;
    }

    /* Check conversion success */
    if (!convert_j2mp(env, jbase, &mbase)) {
        mpz_clears(mmod, mexp, NULL);
        return NULL;
    }

    /* Use separate result variable */
    mpz_init(mresult);
    mpz_powm_sec(mresult, mbase, mexp, mmod);

    convert_mp2j(env, mresult, &jresult);

    mpz_clears(mbase, mexp, mmod, mresult, NULL);

    return jresult;
}

/*
 * Native method: nativeModInverse
 *
 * Class:     net_i2p_util_NativeBigInteger
 * Method:    nativeModInverse
 * Signature: ([B[B)[B
 *
 * From the javadoc:
 *
 * calculate (base ^ -1) % modulus.
 * @param jbase big endian twos complement representation of the base
 *             Negative values allowed
 * @param jmod big endian twos complement representation of the modulus
 *             Zero or Negative values will throw a
 * java.lang.ArithmeticException
 * @return big endian twos complement representation of (base ^ exponent) %
 * modulus
 * @throws java.lang.ArithmeticException if jbase and jmod are not coprime or
 * jmod is <= 0
 * @since version 3
 */

JNIEXPORT jbyteArray JNICALL
Java_net_i2p_util_NativeBigInteger_nativeModInverse(JNIEnv *env, jclass cls,
                                                    jbyteArray jbase,
                                                    jbyteArray jmod) {
    /* Add null/empty checks */
    if (check_byte_array(env, jbase, "base") != 0)
        return NULL;
    if (check_byte_array(env, jmod, "modulus") != 0)
        return NULL;

    /* Initialize cached class references */
    init_jclasses(env);

    mpz_t mbase;
    mpz_t mexp;
    mpz_t mmod;
    /* Use separate result variable */
    mpz_t mresult;
    jbyteArray jresult;

    /* Check conversion success */
    if (!convert_j2mp(env, jmod, &mmod))
        return NULL;

    if (mpz_sgn(mmod) <= 0) {
        mpz_clear(mmod);
        if (ArithmeticException_class)
            (*env)->ThrowNew(env, ArithmeticException_class,
                             "Modulus must be positive");
        return NULL;
    }

    /* Check conversion success */
    if (!convert_j2mp(env, jbase, &mbase)) {
        mpz_clear(mmod);
        return NULL;
    }
    mpz_init_set_si(mexp, -1);

    /* Use mpz_invert for modular inverse.
     * Returns 0 if no inverse exists (not coprime), avoiding the GCD check.
     * This is faster than the separate GCD + powm approach.
     */
    mpz_init(mresult);
    if (mpz_invert(mresult, mbase, mmod) == 0) {
        mpz_clears(mbase, mexp, mmod, mresult, NULL);
        if (ArithmeticException_class)
            (*env)->ThrowNew(env, ArithmeticException_class,
                             "Not coprime in nativeModInverse()");
        return NULL;
    }

    convert_mp2j(env, mresult, &jresult);

    mpz_clears(mbase, mexp, mmod, mresult, NULL);

    return jresult;
}

/* Conversion methods */

/*
 * Convert Java byte array to GMP integer
 *
 * We use GMP's mpz_import() and mpz_export() functions to convert
 * from/to BigInteger.toByteArray() representation.
 *
 * Initializes the GMP value with enough preallocated size, and converts the
 * Java value into the GMP value. The value that mvalue points to should be
 * uninitialized.
 *
 * Returns: 1 on success, 0 on failure
 *  Silent conversion failures now properly propagate
 */

int convert_j2mp(JNIEnv *env, jbyteArray jvalue, mpz_t *mvalue) {
    jsize size;
    jbyte *jbuffer;
    mpz_t mask;

    size = (*env)->GetArrayLength(env, jvalue);
    /* Check for OOM from GetByteArrayElements */
    jbuffer = (*env)->GetByteArrayElements(env, jvalue, NULL);
    if (jbuffer == NULL) {
        mpz_init(*mvalue);
        /* Throw OutOfMemoryError on failure */
        jclass exc = (*env)->FindClass(env, "java/lang/OutOfMemoryError");
        if (exc)
            (*env)->ThrowNew(env, exc, "GetByteArrayElements failed");
        return 0;
    }

    mpz_init2(*mvalue, sizeof(jbyte) * 8 * size); // preallocate the size

    /*
     * mpz_import() converts Java big-endian byte array to GMP integer
     * order=1: most significant byte first, endian=1: most significant first
     */
    mpz_import(*mvalue, size, 1, sizeof(jbyte), 1, 0, (void *)jbuffer);
    if (jbuffer[0] < 0) {
        // Ones complement, making a negative number
        mpz_com(*mvalue, *mvalue);
        // Construct the mask needed to get rid of the new high bit
        mpz_init_set_ui(mask, 1);
        mpz_mul_2exp(mask, mask, size * 8);
        mpz_sub_ui(mask, mask, 1);
        // Mask off the high bits, making a positive number (the magnitude, off
        // by one)
        mpz_and(*mvalue, *mvalue, mask);
        // Release the mask
        mpz_clear(mask);
        // Add one to get the correct magnitude
        mpz_add_ui(*mvalue, *mvalue, 1);
        // Invert to a negative number
        mpz_neg(*mvalue, *mvalue);
    }
    (*env)->ReleaseByteArrayElements(env, jvalue, jbuffer, JNI_ABORT);
    return 1; // success
}

/*
 * Convert GMP integer to Java byte array
 * Pads the resulting jbyte array with 0, so the twos complement value is always
 * positive.
 *
 * Since version 3, negative values are correctly converted.
 *  Work on a copy to avoid mutating input
 */

void convert_mp2j(JNIEnv *env, mpz_t mvalue, jbyteArray *jvalue) {
    size_t size;
    jbyte *buffer;
    jboolean copy;
    int neg;
    /* Work on a copy to avoid mutating input */
    mpz_t temp;
    mpz_init_set(temp, mvalue);

    copy = JNI_FALSE;

    neg = mpz_sgn(temp) < 0;
    if (neg) {
        /* Modify temp copy, not original */
        mpz_add_ui(temp, temp, 1);
    }

    /* sizeinbase() + 7 => Ceil division */
    size = (mpz_sizeinbase(temp, 2) + 7) / 8 + sizeof(jbyte);
    /* Check for NULL from NewByteArray */
    *jvalue = (*env)->NewByteArray(env, size);
    if (*jvalue == NULL) {
        mpz_clear(temp);
        /* Throw OutOfMemoryError instead of returning NULL */
        jclass exc = (*env)->FindClass(env, "java/lang/OutOfMemoryError");
        if (exc)
            (*env)->ThrowNew(env, exc, "NewByteArray failed");
        return;
    }

    /* Check for OOM from GetByteArrayElements */
    buffer = (*env)->GetByteArrayElements(env, *jvalue, &copy);
    if (buffer == NULL) {
        mpz_clear(temp);
        /* Throw OutOfMemoryError instead of returning */
        jclass exc = (*env)->FindClass(env, "java/lang/OutOfMemoryError");
        if (exc)
            (*env)->ThrowNew(env, exc, "GetByteArrayElements failed");
        return;
    }
    buffer[0] = 0x00; // Sign byte padding - ensures positive representation

    mpz_export((void *)&buffer[1], NULL, 1, sizeof(jbyte), 1, 0, temp);

    if (neg) {
        /* Invert all bytes except the sign byte (buffer[0]) */
        for (size_t i = 1; i < size; i++) {
            buffer[i] = ~buffer[i];
        }
    }

    (*env)->ReleaseByteArrayElements(env, *jvalue, buffer,
                                     0); // Mode=0 commits changes
    /* Clear temp copy */
    mpz_clear(temp);
}
