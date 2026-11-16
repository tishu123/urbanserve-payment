
package com.example.urbanserve.payment.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.example.urbanserve.payment.entity.Payment;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, Long> {

    // Rohnit: Find all payment records associated with a booking.
    List<Payment> findByBookingId(Long bookingId);

    // Rohnit: Find the latest payment attempt for a booking.
    Optional<Payment> findFirstByBookingIdOrderByCreatedAtDesc(Long bookingId);

    // Rohnit: Find payment using Razorpay order ID.
    Optional<Payment> findByRazorpayOrderId(String razorpayOrderId);

    // Rohnit: Find payment using Razorpay payment ID.
    Optional<Payment> findByRazorpayPaymentId(String razorpayPaymentId);

}
