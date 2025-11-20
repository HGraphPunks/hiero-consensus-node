// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.privacy;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.Security;
import java.util.HexFormat;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.crypto.ec.CustomNamedCurves;
import org.bouncycastle.crypto.params.ECDomainParameters;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.math.ec.ECPoint;

/** Utility helpers for creating and validating Pedersen-style commitments. */
public final class PedersenCommitments {
    private static final Logger log = LogManager.getLogger(PedersenCommitments.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ECDomainParameters DOMAIN;
    private static final ECPoint H;

    static {
        Security.addProvider(new BouncyCastleProvider());
        final X9ECParameters params = CustomNamedCurves.getByName("secp256k1");
        DOMAIN = new ECDomainParameters(params.getCurve(), params.getG(), params.getN(), params.getH());
        H = deriveGenerator(params);
    }

    private PedersenCommitments() {
        throw new UnsupportedOperationException("Utility class");
    }

    public static PrivateCommitmentInfo newTreasuryNote(
            @NonNull final TokenID tokenId, @NonNull final AccountID owner, final long value) {
        validateAmount(value);
        final var blindingScalar = randomScalar();
        final var commitmentPoint = commit(valueAsScalar(value), blindingScalar);
        final var commitmentBytes = Bytes.wrap(commitmentPoint.getEncoded(true));
        final var blindingBytes = Bytes.wrap(toFixedLength(blindingScalar));
        log.info(
                "Generated commitment {} for token {} owner {} value {}",
                HexFormat.of().formatHex(commitmentBytes.toByteArray()),
                readable(tokenId),
                readable(owner),
                value);
        return PrivateCommitmentInfo.known(tokenId, owner, commitmentBytes, blindingBytes, value);
    }

    public static boolean sumsMatch(@NonNull final List<Bytes> inputs, @NonNull final List<Bytes> outputs) {
        requireNonNull(inputs);
        requireNonNull(outputs);
        final var left = sumPoints(inputs);
        final var right = sumPoints(outputs);
        return left.equals(right);
    }

    public static ECPoint decode(@NonNull final Bytes commitment) {
        requireNonNull(commitment);
        try {
            return DOMAIN.getCurve().decodePoint(commitment.toByteArray()).normalize();
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid commitment bytes", e);
        }
    }

    private static void validateAmount(final long value) {
        if (value < 0) {
            throw new IllegalArgumentException("commitment values must be non-negative");
        }
    }

    private static BigInteger valueAsScalar(final long value) {
        return BigInteger.valueOf(value);
    }

    private static ECPoint commit(final BigInteger valueScalar, final BigInteger blindingScalar) {
        final var point = DOMAIN.getG().multiply(valueScalar).add(H.multiply(blindingScalar));
        return point.normalize();
    }

    private static BigInteger randomScalar() {
        BigInteger k;
        do {
            k = new BigInteger(DOMAIN.getN().bitLength(), RANDOM).mod(DOMAIN.getN());
        } while (k.equals(BigInteger.ZERO));
        return k;
    }

    private static byte[] toFixedLength(final BigInteger scalar) {
        final var bytes = scalar.toByteArray();
        final int size = 32;
        if (bytes.length == size) {
            return bytes;
        }
        final byte[] result = new byte[size];
        final int copyFrom = Math.max(0, bytes.length - size);
        final int copyLength = Math.min(bytes.length, size);
        System.arraycopy(bytes, copyFrom, result, size - copyLength, copyLength);
        return result;
    }

    private static ECPoint sumPoints(final List<Bytes> commitments) {
        ECPoint sum = DOMAIN.getCurve().getInfinity();
        for (final var commitment : commitments) {
            sum = sum.add(decode(commitment));
        }
        return sum.normalize();
    }

    private static ECPoint deriveGenerator(final X9ECParameters params) {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            final byte[] hash = digest.digest(params.getG().getEncoded(true));
            BigInteger scalar = new BigInteger(1, hash).mod(params.getN());
            if (scalar.equals(BigInteger.ZERO)) {
                scalar = BigInteger.ONE;
            }
            return params.getG().multiply(scalar).normalize();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Unable to derive commitment generator", e);
        }
    }

    private static String readable(@NonNull final TokenID tokenId) {
        return tokenId.shardNum() + "." + tokenId.realmNum() + "." + tokenId.tokenNum();
    }

    private static String readable(@NonNull final AccountID accountId) {
        return accountId.shardNum() + "." + accountId.realmNum() + "." + accountId.accountNum();
    }
}
