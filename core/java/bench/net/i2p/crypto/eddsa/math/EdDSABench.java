package net.i2p.crypto.eddsa.math;

import net.i2p.crypto.eddsa.EdDSAEngine;
import net.i2p.crypto.eddsa.EdDSABlinding;
import net.i2p.crypto.eddsa.EdDSAPrivateKey;
import net.i2p.crypto.eddsa.EdDSAPublicKey;
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveSpec;
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable;
import net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.security.MessageDigest;
import java.util.concurrent.TimeUnit;

/**
 * Ed25519 sign/verify and the group-formula operations behind them.
 * Lives in the math package so the benchmarks can reach the package-private
 * formula methods (madd) and precomputation tables directly.
 * <p>
 * For the allocation picture (bytes/op per operation) run with the JMH gc
 * profiler, e.g.:
 * <pre>
 *   ant bench -Dbench.args="EdDSABench -prof gc"
 *   java -jar i2p-benchmarks.jar EdDSABench -prof gc
 * </pre>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 2, timeUnit = TimeUnit.SECONDS)
@Fork(1)
@State(Scope.Benchmark)
public class EdDSABench {

    private EdDSAEngine engine;
    private EdDSAPrivateKey sk;
    private EdDSAPublicKey pk;
    private byte[] msg;
    private byte[] sig;

    private GroupElement B;
    private GroupElement negA;
    private GroupElement acached;
    private byte[] scalar;   // clamped 32-byte scalar
    private byte[] hScalar;  // reduced mod l
    private byte[] sScalar;  // reduced mod l

    @Setup
    public void prepare() throws Exception {
        EdDSANamedCurveSpec spec = EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519);
        byte[] seed = new byte[32];
        seed[0] = 42;
        EdDSAPrivateKeySpec privSpec = new EdDSAPrivateKeySpec(seed, spec);
        sk = new EdDSAPrivateKey(privSpec);
        pk = new EdDSAPublicKey(new net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec(privSpec.getA(), spec));
        msg = "i2p+ eddsa jmh benchmark message".getBytes("UTF-8");

        engine = new EdDSAEngine(MessageDigest.getInstance(spec.getHashAlgorithm()));
        engine.initSign(sk);
        sig = engine.signOneShot(msg);

        B = spec.getB();
        negA = pk.getNegativeA();
        acached = pk.getA().toCached();
        scalar = privSpec.geta();

        byte[] rand64 = new byte[64];
        for (int i = 0; i < 64; i++)
            rand64[i] = (byte) (i * 7 + 3);
        hScalar = EdDSABlinding.reduce(rand64);
        for (int i = 0; i < 64; i++)
            rand64[i] = (byte) (i * 11 + 5);
        sScalar = EdDSABlinding.reduce(rand64);
    }

    @Benchmark
    public byte[] sign() throws Exception {
        engine.initSign(sk);
        return engine.signOneShot(msg);
    }

    @Benchmark
    public boolean verify() throws Exception {
        engine.initVerify(pk);
        return engine.verifyOneShot(msg, sig);
    }

    /**
     * The verify inner loop: h*(-A) + s*B with the joint sliding window.
     */
    @Benchmark
    public GroupElement doubleScalarMultiply() {
        return B.doubleScalarMultiplyVariableTime(negA, hScalar, sScalar);
    }

    /**
     * The sign inner loop: a*B via the radix-16 precomputed table.
     */
    @Benchmark
    public GroupElement scalarMultiply() {
        return B.scalarMultiply(scalar);
    }

    /**
     * One P2 doubling, the operation the verify loop runs 255 times.
     */
    @Benchmark
    public GroupElement dbl() {
        return B.toP2().dbl();
    }

    /**
     * One P3 + PRECOMP mixed addition, the operation the verify loop runs
     * once per nonzero window digit.
     */
    @Benchmark
    public GroupElement madd() {
        return B.madd(B.dblPrecmp[1]);
    }

    /**
     * One P3 + CACHED addition, used by key blinding and point arithmetic.
     */
    @Benchmark
    public GroupElement add() {
        return B.add(acached);
    }

    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder().include(EdDSABench.class.getSimpleName()).build();

        new Runner(opt).run();
    }
}
