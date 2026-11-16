package com.example.urbanserve.payment.service;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.example.urbanserve.payment.dto.CreatePaymentRequest;
import com.example.urbanserve.payment.dto.PaymentResponse;
import com.example.urbanserve.payment.entity.Payment;
import com.example.urbanserve.payment.entity.PaymentStatus;
import com.example.urbanserve.payment.repository.PaymentRepository;

@Service
public class PaymentService {

    private final PaymentRepository paymentRepository;

    public PaymentService(PaymentRepository paymentRepository) {
        this.paymentRepository = paymentRepository;
    }

    // The repository save owns its transaction, so a failed insert rolls back
    // before we look up the payment created by a concurrent request.
    @Transactional(propagation = Propagation.NEVER)
    public PaymentResponse createPayment(CreatePaymentRequest request) {
        if (request.getAmount() == null || request.getAmount() <= 0) {
            throw new IllegalArgumentException("Payment amount must be positive");
        }

        if (request.getBookingId() == null || request.getBookingId() <= 0) {
            throw new IllegalArgumentException("Booking ID must be positive");
        }

        if (request.getPaymentMethod() == null) {
            throw new IllegalArgumentException("Payment method is required");
        }

        Long bookingId = request.getBookingId();
        var existing = paymentRepository.findFirstByBookingIdOrderByCreatedAtDesc(bookingId);

        if (existing.isPresent()) {
            return reuseMatchingPayment(existing.get(), request);
        }

        Payment payment = new Payment();
        payment.setBookingId(bookingId);
        payment.setAmount(request.getAmount());
        payment.setCurrency("INR");
        payment.setPaymentMethod(request.getPaymentMethod());
        payment.setPaymentStatus(PaymentStatus.PENDING);

        try {
            return convertToResponse(paymentRepository.save(payment));
        } catch (DataIntegrityViolationException insertFailure) {
            // Another request may have inserted the payment after our first lookup.
            Payment winner = paymentRepository
                    .findFirstByBookingIdOrderByCreatedAtDesc(bookingId)
                    .orElseThrow(() -> insertFailure);
            return reuseMatchingPayment(winner, request);
        }
    }

    private PaymentResponse reuseMatchingPayment(Payment existing,
                                                  CreatePaymentRequest request) {
        if (!existing.getAmount().equals(request.getAmount())
                || existing.getPaymentMethod() != request.getPaymentMethod()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "This booking already has a payment with a different amount or method"
            );
        }

        return convertToResponse(existing);
    }

    private PaymentResponse convertToResponse(Payment payment) {
        PaymentResponse response = new PaymentResponse();
        response.setPaymentId(payment.getId());
        response.setBookingId(payment.getBookingId());
        response.setAmount(payment.getAmount());
        response.setCurrency(payment.getCurrency());
        response.setPaymentMethod(payment.getPaymentMethod());
        response.setPaymentStatus(payment.getPaymentStatus());
        response.setRazorpayOrderId(payment.getRazorpayOrderId());
        response.setCreatedAt(payment.getCreatedAt());
        return response;
    }
}