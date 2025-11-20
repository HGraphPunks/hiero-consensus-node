// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.privacy;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * In-memory registry for prototype private token commitments.
 */
public final class PrivateCommitmentRegistry {
    private static final Logger log = LogManager.getLogger(PrivateCommitmentRegistry.class);
    private static final ConcurrentMap<TokenID, ConcurrentMap<Bytes, PrivateCommitmentInfo>> STORE =
            new ConcurrentHashMap<>();

    private PrivateCommitmentRegistry() {
        throw new UnsupportedOperationException("Utility class");
    }

    public static void put(@NonNull final PrivateCommitmentInfo info) {
        requireNonNull(info);
        STORE.computeIfAbsent(info.tokenId(), ignored -> new ConcurrentHashMap<>())
                .put(info.commitment(), info);
        log.info(
                "Stored private commitment {} for token {} owner {}",
                HexFormat.of().formatHex(info.commitment().toByteArray()),
                readable(info.tokenId()),
                readable(info.owner()));
    }

    public static PrivateCommitmentInfo get(@NonNull final TokenID tokenId, @NonNull final Bytes commitment) {
        requireNonNull(tokenId);
        requireNonNull(commitment);
        final Map<Bytes, PrivateCommitmentInfo> commitments = STORE.get(tokenId);
        return commitments == null ? null : commitments.get(commitment);
    }

    public static PrivateCommitmentInfo remove(@NonNull final TokenID tokenId, @NonNull final Bytes commitment) {
        requireNonNull(tokenId);
        requireNonNull(commitment);
        final Map<Bytes, PrivateCommitmentInfo> commitments = STORE.get(tokenId);
        if (commitments == null) {
            return null;
        }
        final PrivateCommitmentInfo removed = commitments.remove(commitment);
        if (commitments.isEmpty()) {
            STORE.remove(tokenId, commitments);
        }
        if (removed != null) {
            log.info(
                    "Removed private commitment {} for token {}",
                    HexFormat.of().formatHex(commitment.toByteArray()),
                    readable(tokenId));
        }
        return removed;
    }

    /** Clears all stored commitments. Intended for test cleanup only. */
    public static void clear() {
        STORE.clear();
    }

    private static String readable(@NonNull final TokenID tokenId) {
        return tokenId.shardNum() + "." + tokenId.realmNum() + "." + tokenId.tokenNum();
    }

    private static String readable(@NonNull final AccountID accountId) {
        return accountId.shardNum() + "." + accountId.realmNum() + "." + accountId.accountNum();
    }
}
