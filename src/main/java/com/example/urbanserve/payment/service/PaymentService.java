
package com.example.urbanserve.payment.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.urbanserve.payment.dto.CreatePaymentRequest;
import com.example.urbanserve.payment.dto.PaymentResponse;
import com.example.urbanserve.payment.entity.Payment;
import com.example.urbanserve.payment.entity.PaymentStatus;
import com.example.urbanserve.payment.repository.PaymentRepository;

@Service
public class PaymentService {

    private final PaymentRepository paymentRepository;

    // Rohnit: Constructor injection for the payment repository.
    public PaymentService(PaymentRepository paymentRepository) {
        this.paymentRepository = paymentRepository;
    }

    // Rohnit: Creates and saves a new payment record.
    @Transactional
    public PaymentResponse createPayment(CreatePaymentRequest request) {

        // Validate payment amount.
        if (request.getAmount() == null || request.getAmount() <= 0) {
            throw new IllegalArgumentException("Payment amount must be positive");
        }

        // Validate booking ID.
        if (request.getBookingId() == null || request.getBookingId() <= 0) {
            throw new IllegalArgumentException("Booking ID must be positive");
        }

        // Validate payment method.
        if (request.getPaymentMethod() == null) {
            throw new IllegalArgumentException("Payment method is required");
        }

        // Create a new payment entity.
        Payment payment = new Payment();

        payment.setBookingId(request.getBookingId());
        payment.setAmount(request.getAmount());
        payment.setCurrency("INR");
        payment.setPaymentMethod(request.getPaymentMethod());


        // Rohnit: All payment methods initially start as pending.
        payment.setPaymentStatus(PaymentStatus.PENDING);

        // Save payment in the database.
        Payment savedPayment = paymentRepository.save(payment);

        // Convert entity into response DTO.
        return convertToResponse(savedPayment);
    }

    // Rohnit: Converts Payment entity into PaymentResponse.
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
