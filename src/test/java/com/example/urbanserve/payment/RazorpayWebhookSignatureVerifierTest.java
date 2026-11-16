package com.example.urbanserve.payment;

import com.example.urbanserve.payment.service.RazorpayWebhookSignatureVerifier;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RazorpayWebhookSignatureVerifierTest {

    private static final String TEST_SECRET = "urbanserve-local-webhook-test-fixture";
    private static final byte[] BODY =
            "{\"event\": \"payment.captured\"}\n".getBytes(StandardCharsets.UTF_8);
    private static final String VALID_SIGNATURE =
            "8a13e05db06efee17d3c23ecb46ca626482b4b37718561645728b99ad379c14e";

    private final RazorpayWebhookSignatureVerifier verifier =
            new RazorpayWebhookSignatureVerifier(TEST_SECRET);

    @Test
    void acceptsTheValidSignatureForTheExactBody() {
        assertDoesNotThrow(() -> verifier.verify(BODY, VALID_SIGNATURE));
    }

    @Test
    void rejectsTheSameJsonIfItsRawBytesChanged() {
        byte[] reformatted =
                "{\"event\":\"payment.captured\"}".getBytes(StandardCharsets.UTF_8);

        var error = assertThrows(ResponseStatusException.class,
                () -> verifier.verify(reformatted, VALID_SIGNATURE));

        assertEquals(HttpStatus.UNAUTHORIZED, error.getStatusCode());
    }

    @Test
    void rejectsMissingOrMalformedSignatures() {
        for (String signature : new String[] {null, "", "not-a-signature", "z".repeat(64)}) {
            var error = assertThrows(ResponseStatusException.class,
                    () -> verifier.verify(BODY, signature));

            assertEquals(HttpStatus.UNAUTHORIZED, error.getStatusCode());
        }
    }

    @Test
    void rejectsASignatureMadeWithADifferentSecret() {
        var otherVerifier = new RazorpayWebhookSignatureVerifier("different-test-secret");

        var error = assertThrows(ResponseStatusException.class,
                () -> otherVerifier.verify(BODY, VALID_SIGNATURE));

        assertEquals(HttpStatus.UNAUTHORIZED, error.getStatusCode());
    }

    @Test
    void rejectsAnEmptyBody() {
        var error = assertThrows(ResponseStatusException.class,
                () -> verifier.verify(new byte[0], VALID_SIGNATURE));

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
    }

    @Test
    void refusesVerificationWhenTheWebhookSecretIsMissing() {
        var unconfigured = new RazorpayWebhookSignatureVerifier("");

        var error = assertThrows(ResponseStatusException.class,
                () -> unconfigured.verify(BODY, VALID_SIGNATURE));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, error.getStatusCode());
    }
}