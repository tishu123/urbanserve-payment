package com.example.urbanserve.payment.controller;

import com.example.urbanserve.payment.service.RazorpayCapturedWebhookService;

import jakarta.servlet.http.HttpServletRequest;

import java.io.IOException;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RazorpayWebhookController {

    private static final int MAX_BODY_BYTES = 256 * 1024;

    private final RazorpayCapturedWebhookService capturedPayments;

    public RazorpayWebhookController(RazorpayCapturedWebhookService capturedPayments) {
        this.capturedPayments = capturedPayments;
    }

    @PostMapping(
            path = "/api/webhooks/razorpay",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> receive(
            HttpServletRequest request,
            @RequestHeader(name = "X-Razorpay-Signature", required = false) String signature)
            throws IOException {
        if (request.getContentLengthLong() > MAX_BODY_BYTES) {
            return ResponseEntity.status(413).build();
        }

        byte[] rawBody = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (rawBody.length > MAX_BODY_BYTES) {
            return ResponseEntity.status(413).build();
        }

        capturedPayments.handle(rawBody, signature);
        return ResponseEntity.noContent().build();
    }
}