// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.privacy;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Simple value object describing a single private commitment (a "note").
 */
public record PrivateCommitmentInfo(
        @NonNull TokenID tokenId,
        @NonNull AccountID owner,
        @NonNull Bytes commitment,
        @NonNull Bytes blinding,
        long value) {
    public static final long VALUE_UNKNOWN = -1L;

    public PrivateCommitmentInfo {
        requireNonNull(tokenId, "tokenId must not be null");
        requireNonNull(owner, "owner must not be null");
        requireNonNull(commitment, "commitment must not be null");
        requireNonNull(blinding, "blinding must not be null");
    }

    public boolean valueKnown() {
        return value >= 0;
    }

    public static PrivateCommitmentInfo known(
            @NonNull TokenID tokenId,
            @NonNull AccountID owner,
            @NonNull Bytes commitment,
            @NonNull Bytes blinding,
            long value) {
        return new PrivateCommitmentInfo(tokenId, owner, commitment, blinding, value);
    }

    public static PrivateCommitmentInfo external(
            @NonNull TokenID tokenId, @NonNull AccountID owner, @NonNull Bytes commitment) {
        return new PrivateCommitmentInfo(tokenId, owner, commitment, Bytes.EMPTY, VALUE_UNKNOWN);
    }
}
