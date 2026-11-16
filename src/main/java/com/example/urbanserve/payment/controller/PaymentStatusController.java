package com.example.urbanserve.payment.controller;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/payments/bookings")
public class PaymentStatusController {

    private final JdbcTemplate jdbc;

    public PaymentStatusController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/{bookingId}/status")
    public ResponseEntity<BookingPaymentStatus> getStatus(@PathVariable long bookingId) {
        if (bookingId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid booking ID");
        }

        BookingPaymentStatus status = jdbc.query("""
                select amount, currency, payment_method, payment_status
                  from payments
                 where booking_id = ?
                """, rs -> {
            if (!rs.next()) {
                return new BookingPaymentStatus(bookingId, null, null, null, "NOT_STARTED");
            }

            return new BookingPaymentStatus(
                    bookingId,
                    rs.getLong("amount"),
                    rs.getString("currency"),
                    rs.getString("payment_method"),
                    rs.getString("payment_status"));
        }, bookingId);

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(status);
    }

    public record BookingPaymentStatus(
            long bookingId,
            Long amount,
            String currency,
            String paymentMethod,
            String paymentStatus) {}
}