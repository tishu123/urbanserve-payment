package com.example.urbanserve.payment.service;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.regex.Pattern;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class RazorpayWebhookSignatureVerifier {

    private static final String ALGORITHM = "HmacSHA256";
    private static final Pattern SIGNATURE = Pattern.compile("[0-9a-fA-F]{64}");

    private final String webhookSecret;

    public RazorpayWebhookSignatureVerifier(
            @Value("${razorpay.webhook-secret:}") String webhookSecret) {
        this.webhookSecret = webhookSecret;
    }

    public void verify(byte[] rawBody, String signature) {
        if (webhookSecret.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "Razorpay webhook is not configured");
        }

        if (signature == null || !SIGNATURE.matcher(signature).matches()) {
            throw invalidSignature();
        }

        if (rawBody == null || rawBody.length == 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Webhook body is required");
        }

        byte[] expected;
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(
                    webhookSecret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            expected = mac.doFinal(rawBody);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Webhook signature verification is unavailable", e);
        }

        byte[] received = HexFormat.of().parseHex(signature);
        if (!MessageDigest.isEqual(expected, received)) {
            throw invalidSignature();
        }
    }

    private static ResponseStatusException invalidSignature() {
        return new ResponseStatusException(
                HttpStatus.UNAUTHORIZED, "Invalid Razorpay webhook signature");
    }
}