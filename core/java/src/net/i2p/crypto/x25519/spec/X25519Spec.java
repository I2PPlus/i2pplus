/**
 * Parameter specification for X25519 elliptic curve Diffie-Hellman.
 * 
 * This class provides the algorithm parameter specification for X25519,
 * an elliptic curve Diffie-Hellman key exchange function using Curve25519.
 * X25519 is designed for high-performance, secure key exchange operations
 * and is widely used in modern cryptographic protocols for establishing
 * shared secrets between parties.
 */
package net.i2p.crypto.x25519.spec;

import java.security.spec.AlgorithmParameterSpec;

public class X25519Spec implements AlgorithmParameterSpec {

    public static final X25519Spec X25519_SPEC = new X25519Spec();

}
