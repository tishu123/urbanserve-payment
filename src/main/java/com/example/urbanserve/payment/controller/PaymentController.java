
package com.example.urbanserve.payment.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.urbanserve.payment.dto.CreatePaymentRequest;
import com.example.urbanserve.payment.dto.PaymentResponse;
import com.example.urbanserve.payment.service.PaymentService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    private final PaymentService paymentService;

    // Rohnit: Constructor injection for PaymentService.
    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    // Rohnit: Creates a new payment record.
    @PostMapping
    public ResponseEntity<PaymentResponse> createPayment(
            @Valid @RequestBody CreatePaymentRequest request) {

        PaymentResponse response = paymentService.createPayment(request);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(response);
    }
}
