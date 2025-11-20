// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TRANSACTION_BODY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TOKEN_NOT_ASSOCIATED_TO_ACCOUNT;
import static com.hedera.node.app.service.token.impl.handlers.BaseTokenHandler.asToken;
import static com.hedera.node.app.spi.fixtures.workflows.ExceptionConditions.responseCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mockStatic;

import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.base.TokenType;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.token.OutputCommitment;
import com.hedera.hapi.node.token.PrivateTokenTransferTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.token.impl.handlers.PrivateTokenTransferHandler;
import com.hedera.node.app.service.token.impl.privacy.PedersenCommitments;
import com.hedera.node.app.service.token.impl.privacy.PrivateCommitmentInfo;
import com.hedera.node.app.service.token.impl.privacy.PrivateCommitmentRegistry;
import com.hedera.node.app.service.token.impl.test.handlers.util.CryptoTokenHandlerTestBase;
import com.hedera.node.app.service.token.records.TokenBaseStreamBuilder;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@MockitoSettings(strictness = Strictness.LENIENT)
class PrivateTokenTransferHandlerTest extends CryptoTokenHandlerTestBase {
    private static final TokenID PRIVATE_TOKEN_ID = asToken(9090);
    private static final Bytes INPUT_COMMITMENT = Bytes.wrap(new byte[] {0x01});
    private static final Bytes OUTPUT_COMMITMENT = Bytes.wrap(new byte[] {0x02});

    @Mock
    private HandleContext handleContext;

    @Mock
    private HandleContext.SavepointStack stack;

    @Mock
    private TokenBaseStreamBuilder recordBuilder;

    @Mock
    private PureChecksContext pureChecksContext;

    private PrivateTokenTransferHandler subject;

    @BeforeEach
    void setUpEach() {
        super.setUp();
        refreshWritableStores();
        givenStoresAndConfig(handleContext);
        subject = new PrivateTokenTransferHandler();
        given(handleContext.savepointStack()).willReturn(stack);
        given(stack.getBaseBuilder(TokenBaseStreamBuilder.class)).willReturn(recordBuilder);
        PrivateCommitmentRegistry.clear();
    }

    @AfterEach
    void cleanRegistry() {
        PrivateCommitmentRegistry.clear();
    }

    @Test
    void handleConsumesInputsAndStoresOutputs() {
        final var privateToken = fungibleToken
                .copyBuilder()
                .tokenId(PRIVATE_TOKEN_ID)
                .tokenType(TokenType.FUNGIBLE_PRIVATE)
                .kycKey((Key) null)
                .freezeKey((Key) null)
                .build();
        writableTokenStore.put(privateToken);
        writableTokenRelStore.put(treasuryFTRelation
                .copyBuilder()
                .tokenId(PRIVATE_TOKEN_ID)
                .accountId(payerId)
                .balance(0L)
                .kycGranted(true)
                .build());
        writableTokenRelStore.put(treasuryFTRelation
                .copyBuilder()
                .tokenId(PRIVATE_TOKEN_ID)
                .accountId(tokenReceiverId)
                .balance(0L)
                .kycGranted(true)
                .build());

        PrivateCommitmentRegistry.put(PrivateCommitmentInfo.external(PRIVATE_TOKEN_ID, payerId, INPUT_COMMITMENT));

        final var txn = transactionWith(
                PRIVATE_TOKEN_ID,
                List.of(INPUT_COMMITMENT),
                List.of(OutputCommitment.newBuilder()
                        .owner(tokenReceiverId)
                        .commitment(OUTPUT_COMMITMENT)
                        .build()));

        given(handleContext.body()).willReturn(txn);
        given(handleContext.payer()).willReturn(payerId);

        try (var pedersen = mockStatic(PedersenCommitments.class)) {
            pedersen.when(() -> PedersenCommitments.sumsMatch(List.of(INPUT_COMMITMENT), List.of(OUTPUT_COMMITMENT)))
                    .thenReturn(true);
            subject.handle(handleContext);
        }

        assertThat(PrivateCommitmentRegistry.get(PRIVATE_TOKEN_ID, INPUT_COMMITMENT))
                .isNull();
        final var storedOutput = PrivateCommitmentRegistry.get(PRIVATE_TOKEN_ID, OUTPUT_COMMITMENT);
        assertThat(storedOutput).isNotNull();
        assertThat(storedOutput.owner()).isEqualTo(tokenReceiverId);
    }

    @Test
    void handleRejectsWhenSumsDoNotBalance() {
        final var privateToken = fungibleToken
                .copyBuilder()
                .tokenId(PRIVATE_TOKEN_ID)
                .tokenType(TokenType.FUNGIBLE_PRIVATE)
                .kycKey((Key) null)
                .build();
        writableTokenStore.put(privateToken);
        writableTokenRelStore.put(treasuryFTRelation
                .copyBuilder()
                .tokenId(PRIVATE_TOKEN_ID)
                .accountId(payerId)
                .kycGranted(true)
                .balance(0L)
                .build());
        writableTokenRelStore.put(treasuryFTRelation
                .copyBuilder()
                .tokenId(PRIVATE_TOKEN_ID)
                .accountId(tokenReceiverId)
                .kycGranted(true)
                .balance(0L)
                .build());

        PrivateCommitmentRegistry.put(PrivateCommitmentInfo.external(PRIVATE_TOKEN_ID, payerId, INPUT_COMMITMENT));

        final var txn = transactionWith(
                PRIVATE_TOKEN_ID,
                List.of(INPUT_COMMITMENT),
                List.of(OutputCommitment.newBuilder()
                        .owner(tokenReceiverId)
                        .commitment(OUTPUT_COMMITMENT)
                        .build()));

        given(handleContext.body()).willReturn(txn);
        given(handleContext.payer()).willReturn(payerId);

        try (var pedersen = mockStatic(PedersenCommitments.class)) {
            pedersen.when(() -> PedersenCommitments.sumsMatch(List.of(INPUT_COMMITMENT), List.of(OUTPUT_COMMITMENT)))
                    .thenReturn(false);

            assertThatThrownBy(() -> subject.handle(handleContext))
                    .isInstanceOf(HandleException.class)
                    .has(responseCode(INVALID_TRANSACTION_BODY));
        }
    }

    @Test
    void pureChecksRequireInputsAndOutputs() {
        final var txn = TransactionBody.newBuilder()
                .privateTokenTransfer(PrivateTokenTransferTransactionBody.newBuilder()
                        .token(PRIVATE_TOKEN_ID)
                        .build())
                .build();
        given(pureChecksContext.body()).willReturn(txn);

        assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(INVALID_TRANSACTION_BODY));
    }

    @Test
    void handleFailsWhenOwnerMissingAssociation() {
        final var privateToken = fungibleToken
                .copyBuilder()
                .tokenId(PRIVATE_TOKEN_ID)
                .tokenType(TokenType.FUNGIBLE_PRIVATE)
                .kycKey((Key) null)
                .build();
        writableTokenStore.put(privateToken);
        writableTokenRelStore.put(treasuryFTRelation
                .copyBuilder()
                .tokenId(PRIVATE_TOKEN_ID)
                .accountId(payerId)
                .kycGranted(true)
                .balance(0L)
                .build());

        PrivateCommitmentRegistry.put(PrivateCommitmentInfo.external(PRIVATE_TOKEN_ID, payerId, INPUT_COMMITMENT));

        final var txn = transactionWith(
                PRIVATE_TOKEN_ID,
                List.of(INPUT_COMMITMENT),
                List.of(OutputCommitment.newBuilder()
                        .owner(tokenReceiverId)
                        .commitment(OUTPUT_COMMITMENT)
                        .build()));

        given(handleContext.body()).willReturn(txn);
        given(handleContext.payer()).willReturn(payerId);

        try (var pedersen = mockStatic(PedersenCommitments.class)) {
            pedersen.when(() -> PedersenCommitments.sumsMatch(List.of(INPUT_COMMITMENT), List.of(OUTPUT_COMMITMENT)))
                    .thenReturn(true);

            assertThatThrownBy(() -> subject.handle(handleContext))
                    .isInstanceOf(HandleException.class)
                    .has(responseCode(TOKEN_NOT_ASSOCIATED_TO_ACCOUNT));
        }
    }

    private TransactionBody transactionWith(
            final TokenID tokenId, final List<Bytes> inputs, final List<OutputCommitment> outputs) {
        return TransactionBody.newBuilder()
                .transactionID(TransactionID.newBuilder().accountID(payerId))
                .privateTokenTransfer(PrivateTokenTransferTransactionBody.newBuilder()
                        .token(tokenId)
                        .inputs(inputs)
                        .outputs(outputs)
                        .build())
                .build();
    }
}
