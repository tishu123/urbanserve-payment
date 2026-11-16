package com.example.urbanserve.payment.controller;

import com.example.urbanserve.payment.dto.CreatePaymentRequest;
import com.example.urbanserve.payment.dto.PaymentResponse;
import com.example.urbanserve.payment.entity.PaymentMethod;
import com.example.urbanserve.payment.service.PaymentService;
import com.example.urbanserve.payment.service.RazorpayOrderService;
import com.example.urbanserve.payment.service.RazorpayPaymentVerificationService;
import com.example.urbanserve.payment.service.RazorpayPaymentVerificationService.VerifiedPayment;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    private final PaymentService paymentService;
    private final RazorpayOrderService razorpayOrderService;
    private final RazorpayPaymentVerificationService verificationService;

    public PaymentController(
            PaymentService paymentService,
            RazorpayOrderService razorpayOrderService,
            RazorpayPaymentVerificationService verificationService) {
        this.paymentService = paymentService;
        this.razorpayOrderService = razorpayOrderService;
        this.verificationService = verificationService;
    }

    @PostMapping
    public ResponseEntity<PaymentResponse> createPayment(
            @Valid @RequestBody CreatePaymentRequest request) {
        PaymentResponse response = paymentService.createPayment(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/razorpay/order")
    public PaymentResponse prepareRazorpayOrder(
            @Valid @RequestBody CreatePaymentRequest request) {
        if (request.getPaymentMethod() != PaymentMethod.ONLINE) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Razorpay orders require ONLINE payment");
        }

        if (request.getAmount() == null || request.getAmount() < 100) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Online payment must be at least 100 paise");
        }

        razorpayOrderService.requireTestMode();

        PaymentResponse payment = paymentService.createPayment(request);
        String orderId = razorpayOrderService.prepareTestOrder(payment.getPaymentId());
        payment.setRazorpayOrderId(orderId);

        return payment;
    }

    @PostMapping("/razorpay/verify")
    public VerifiedPayment verifyRazorpayPayment(
            @Valid @RequestBody VerifyRazorpayPaymentRequest request) {
        return verificationService.verifyTestPayment(
                request.bookingId(), request.razorpayPaymentId(), request.razorpaySignature());
    }

    public record VerifyRazorpayPaymentRequest(
            @NotNull @Positive Long bookingId,
            @NotBlank @Pattern(regexp = "pay_[A-Za-z0-9]{1,100}") String razorpayPaymentId,
            @NotBlank @Pattern(regexp = "[0-9a-fA-F]{64}") String razorpaySignature) {}
}