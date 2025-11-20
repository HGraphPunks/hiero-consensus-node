// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.ACCOUNT_KYC_NOT_GRANTED_FOR_TOKEN;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TRANSACTION_BODY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.NOT_SUPPORTED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TOKEN_NOT_ASSOCIATED_TO_ACCOUNT;
import static com.hedera.hapi.node.base.ResponseCodeEnum.UNAUTHORIZED;
import static com.hedera.node.app.spi.workflows.HandleException.validateFalse;
import static com.hedera.node.app.spi.workflows.HandleException.validateTrue;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.base.TokenType;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.hapi.node.token.PrivateTokenTransferTransactionBody;
import com.hedera.node.app.service.token.impl.WritableTokenRelationStore;
import com.hedera.node.app.service.token.impl.WritableTokenStore;
import com.hedera.node.app.service.token.impl.privacy.PedersenCommitments;
import com.hedera.node.app.service.token.impl.privacy.PrivateCommitmentInfo;
import com.hedera.node.app.service.token.impl.privacy.PrivateCommitmentRegistry;
import com.hedera.node.app.service.token.impl.util.TokenHandlerHelper;
import com.hedera.node.app.service.token.records.TokenBaseStreamBuilder;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.node.app.spi.workflows.TransactionHandler;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Handles {@link com.hedera.hapi.node.base.HederaFunctionality#PRIVATE_TOKEN_TRANSFER} transactions.
 */
@Singleton
public class PrivateTokenTransferHandler extends BaseTokenHandler implements TransactionHandler {
    private static final Logger log = LogManager.getLogger(PrivateTokenTransferHandler.class);

    @Inject
    public PrivateTokenTransferHandler() {}

    @Override
    public void preHandle(@NonNull final PreHandleContext context) throws HandleException {
        requireNonNull(context);
        // No additional keys beyond the payer are required yet.
    }

    @Override
    public void pureChecks(@NonNull final PureChecksContext context) throws HandleException {
        requireNonNull(context);
        final var txn = context.body();
        validateTrue(txn.hasPrivateTokenTransfer(), INVALID_TRANSACTION_BODY);
        final var op = txn.privateTokenTransferOrThrow();
        validateFalse(op.inputs().isEmpty(), INVALID_TRANSACTION_BODY);
        validateFalse(op.outputs().isEmpty(), INVALID_TRANSACTION_BODY);
    }

    @Override
    public void handle(@NonNull final HandleContext context) throws HandleException {
        requireNonNull(context);
        final PrivateTokenTransferTransactionBody op = context.body().privateTokenTransferOrThrow();
        final TokenID tokenId = op.tokenOrThrow();

        final var storeFactory = context.storeFactory();
        final WritableTokenStore tokenStore = storeFactory.writableStore(WritableTokenStore.class);
        final Token token = TokenHandlerHelper.getIfUsable(tokenId, tokenStore);
        validateTrue(token.tokenType() == TokenType.FUNGIBLE_PRIVATE, NOT_SUPPORTED);

        validateFalse(op.inputs().isEmpty(), INVALID_TRANSACTION_BODY);
        validateFalse(op.outputs().isEmpty(), INVALID_TRANSACTION_BODY);

        final AccountID payer = context.payer();
        final var relationStore = storeFactory.writableStore(WritableTokenRelationStore.class);

        final var inputInfos = new ArrayList<PrivateCommitmentInfo>(op.inputs().size());
        for (final Bytes commitmentBytes : op.inputs()) {
            validateFalse(commitmentBytes.length() == 0, INVALID_TRANSACTION_BODY);
            final var info = PrivateCommitmentRegistry.get(tokenId, commitmentBytes);
            validateTrue(info != null, INVALID_TRANSACTION_BODY);
            validateTrue(info.owner().equals(payer), UNAUTHORIZED);
            ensureAssociation(token, info.owner(), relationStore);
            inputInfos.add(info);
        }

        final var outputCommitments = new ArrayList<Bytes>(op.outputs().size());
        for (final var output : op.outputs()) {
            validateTrue(output.hasOwner(), INVALID_TRANSACTION_BODY);
            validateFalse(output.commitment().length() == 0, INVALID_TRANSACTION_BODY);
            ensureAssociation(token, output.ownerOrThrow(), relationStore);
            outputCommitments.add(output.commitment());
        }

        final var inputCommitments =
                inputInfos.stream().map(PrivateCommitmentInfo::commitment).toList();
        final boolean sumsMatch = PedersenCommitments.sumsMatch(inputCommitments, outputCommitments);
        validateTrue(sumsMatch, INVALID_TRANSACTION_BODY);

        // All verification completed, consume the inputs
        for (final var info : inputInfos) {
            final var removed = PrivateCommitmentRegistry.remove(tokenId, info.commitment());
            validateTrue(removed != null, INVALID_TRANSACTION_BODY);
        }

        // Store outputs
        for (final var output : op.outputs()) {
            PrivateCommitmentRegistry.put(
                    PrivateCommitmentInfo.external(tokenId, output.ownerOrThrow(), output.commitment()));
        }

        if (op.zkProof().length() > 0) {
            log.debug(
                    "Received zk proof blob ({} bytes) for token {}",
                    op.zkProof().length(),
                    readable(tokenId));
        }
        log.info(
                "Processed private token transfer for token {} with {} inputs and {} outputs",
                readable(tokenId),
                inputInfos.size(),
                op.outputs().size());

        final var recordBuilder = context.savepointStack().getBaseBuilder(TokenBaseStreamBuilder.class);
        recordBuilder.tokenType(TokenType.FUNGIBLE_PRIVATE);
    }

    private void ensureAssociation(
            @NonNull final Token token,
            @NonNull final AccountID owner,
            @NonNull final WritableTokenRelationStore relationStore) {
        final var relation = TokenHandlerHelper.getIfUsable(owner, token.tokenId(), relationStore);
        validateTrue(relation != null, TOKEN_NOT_ASSOCIATED_TO_ACCOUNT);
        if (token.hasKycKey()) {
            validateTrue(relation.kycGranted(), ACCOUNT_KYC_NOT_GRANTED_FOR_TOKEN);
        }
    }

    private static String readable(@NonNull final TokenID tokenId) {
        return tokenId.shardNum() + "." + tokenId.realmNum() + "." + tokenId.tokenNum();
    }
}
