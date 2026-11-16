package com.example.urbanserve.payment.service;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.regex.Pattern;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RazorpayPaymentVerificationService {

    private static final Pattern PAYMENT_ID = Pattern.compile("pay_[A-Za-z0-9]{1,100}");
    private static final Pattern ORDER_ID = Pattern.compile("order_[A-Za-z0-9]{1,100}");
    private static final Pattern SIGNATURE = Pattern.compile("[0-9a-fA-F]{64}");

    private final JdbcTemplate jdbc;
    private final RazorpayOrderGateway gateway;
    private final String keySecret;

    public RazorpayPaymentVerificationService(
            JdbcTemplate jdbc,
            RazorpayOrderGateway gateway,
            @Value("${razorpay.key-secret:}") String keySecret) {
        this.jdbc = jdbc;
        this.gateway = gateway;
        this.keySecret = keySecret;
    }

    @Transactional(propagation = Propagation.NEVER)
    public VerifiedPayment verifyTestPayment(
            long bookingId, String razorpayPaymentId, String signature) {
        if (bookingId <= 0
                || razorpayPaymentId == null
                || !PAYMENT_ID.matcher(razorpayPaymentId).matches()
                || signature == null
                || !SIGNATURE.matcher(signature).matches()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Invalid Razorpay payment details");
        }

        StoredPayment payment = findByBookingId(bookingId);
        if (payment == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment not found");
        }

        if (!"ONLINE".equals(payment.method())
                || !"INR".equals(payment.currency())
                || payment.orderId() == null
                || !ORDER_ID.matcher(payment.orderId()).matches()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "This booking does not have a verifiable online order");
        }

        if (!isValidSignature(payment.orderId(), razorpayPaymentId, signature)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Invalid Razorpay payment signature");
        }

        if (isSamePaidPayment(payment, razorpayPaymentId)) {
            return paidResult(bookingId, payment, razorpayPaymentId);
        }

        if (!"PENDING".equals(payment.status()) || payment.razorpayPaymentId() != null) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "This payment is no longer awaiting verification");
        }

        var fetched = gateway.fetchPayment(razorpayPaymentId);
        if (!razorpayPaymentId.equals(fetched.id())
                || !payment.orderId().equals(fetched.orderId())
                || payment.amountPaise() != fetched.amountPaise()
                || !payment.currency().equals(fetched.currency())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Razorpay payment does not match the saved order");
        }

        if (!"captured".equals(fetched.status()) || !fetched.captured()
                || fetched.amountRefundedPaise() != 0) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Razorpay has not confirmed a fully captured payment");
        }

        int updated;
        try {
            updated = jdbc.update("""
                    update payments
                       set payment_status = 'PAID',
                           razorpay_payment_id = ?,
                           updated_at = current_timestamp
                     where id = ? and booking_id = ?
                       and payment_method = 'ONLINE' and payment_status = 'PENDING'
                       and razorpay_order_id = ? and razorpay_payment_id is null
                       and amount = ? and currency = ?
                    """, razorpayPaymentId, payment.id(), bookingId,
                    payment.orderId(), payment.amountPaise(), payment.currency());
        } catch (DuplicateKeyException e) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "This Razorpay payment is already linked");
        }

        if (updated == 1) {
            return paidResult(bookingId, payment, razorpayPaymentId);
        }

        StoredPayment current = findByBookingId(bookingId);
        if (current != null && current.id() == payment.id()
                && payment.orderId().equals(current.orderId())
                && isSamePaidPayment(current, razorpayPaymentId)) {
            return paidResult(bookingId, current, razorpayPaymentId);
        }

        throw new ResponseStatusException(
                HttpStatus.CONFLICT, "The payment changed while it was being verified");
    }

    private boolean isValidSignature(String savedOrderId, String paymentId, String signature) {
        if (keySecret.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "Razorpay verification is not configured");
        }

        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(
                    keySecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] expected = mac.doFinal(
                    (savedOrderId + "|" + paymentId).getBytes(StandardCharsets.UTF_8));
            byte[] received = HexFormat.of().parseHex(signature);
            return MessageDigest.isEqual(expected, received);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Razorpay signature verification is unavailable", e);
        }
    }

    private StoredPayment findByBookingId(long bookingId) {
        return jdbc.query("""
                select id, amount, currency, payment_method, payment_status,
                       razorpay_order_id, razorpay_payment_id
                  from payments
                 where booking_id = ?
                """, rs -> rs.next()
                ? new StoredPayment(
                        rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(7))
                : null, bookingId);
    }

    private static boolean isSamePaidPayment(StoredPayment payment, String paymentId) {
        return "PAID".equals(payment.status()) && paymentId.equals(payment.razorpayPaymentId());
    }

    private static VerifiedPayment paidResult(
            long bookingId, StoredPayment payment, String razorpayPaymentId) {
        return new VerifiedPayment(
                payment.id(), bookingId, payment.amountPaise(), payment.currency(),
                payment.method(), "PAID", payment.orderId(), razorpayPaymentId);
    }

    private record StoredPayment(
            long id, long amountPaise, String currency, String method, String status,
            String orderId, String razorpayPaymentId) {}

    public record VerifiedPayment(
            long paymentId, long bookingId, long amount, String currency, String paymentMethod,
            String paymentStatus, String razorpayOrderId, String razorpayPaymentId) {}
}