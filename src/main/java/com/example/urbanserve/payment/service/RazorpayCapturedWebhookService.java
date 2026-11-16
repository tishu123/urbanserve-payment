package com.example.urbanserve.payment.service;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

import org.json.JSONException;
import org.json.JSONObject;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RazorpayCapturedWebhookService {

    private static final Pattern ORDER_ID = Pattern.compile("order_[A-Za-z0-9]{1,100}");
    private static final Pattern PAYMENT_ID = Pattern.compile("pay_[A-Za-z0-9]{1,100}");

    private final RazorpayWebhookSignatureVerifier signatures;
    private final RazorpayOrderGateway gateway;
    private final JdbcTemplate jdbc;

    public RazorpayCapturedWebhookService(
            RazorpayWebhookSignatureVerifier signatures,
            RazorpayOrderGateway gateway,
            JdbcTemplate jdbc) {
        this.signatures = signatures;
        this.gateway = gateway;
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.NEVER)
    public void handle(byte[] rawBody, String signature) {
        signatures.verify(rawBody, signature);

        CapturedEvent event = parseCapturedEvent(rawBody);
        if (event == null) {
            return;
        }

        SavedPayment saved = findByOrderId(event.orderId());
        if (saved == null) {
            throw conflict("Captured payment has no saved order to match");
        }

        if (!"ONLINE".equals(saved.method())
                || saved.amount() != event.amount()
                || !saved.currency().equals(event.currency())) {
            throw conflict("Captured payment does not match the saved order");
        }

        if (alreadyRecorded(saved, event.paymentId())) {
            return;
        }

        if (!"PENDING".equals(saved.status()) || saved.paymentId() != null) {
            throw conflict("The saved payment is not awaiting capture");
        }

        // A webhook is an event snapshot. Confirm the current payment at Razorpay.
        var current = gateway.fetchPayment(event.paymentId());
        if (current == null
                || !event.paymentId().equals(current.id())
                || !event.orderId().equals(current.orderId())
                || event.amount() != current.amountPaise()
                || !event.currency().equals(current.currency())
                || !"captured".equals(current.status())
                || !current.captured()
                || current.amountRefundedPaise() != 0) {
            throw conflict("Razorpay has not confirmed a matching captured payment");
        }

        int updated;
        try {
            updated = jdbc.update("""
                    update payments
                       set payment_status = 'PAID',
                           razorpay_payment_id = ?,
                           updated_at = current_timestamp
                     where razorpay_order_id = ?
                       and payment_method = 'ONLINE'
                       and payment_status = 'PENDING'
                       and razorpay_payment_id is null
                       and amount = ?
                       and currency = ?
                    """, event.paymentId(), event.orderId(), event.amount(), event.currency());
        } catch (DuplicateKeyException e) {
            throw conflict("This Razorpay payment is already linked");
        }

        if (updated == 1) {
            return;
        }

        // The checkout callback or a concurrent webhook may have won the race.
        SavedPayment latest = findByOrderId(event.orderId());
        if (!alreadyRecorded(latest, event.paymentId())) {
            throw conflict("The saved payment changed during verification");
        }
    }

    private static CapturedEvent parseCapturedEvent(byte[] rawBody) {
        try {
            JSONObject body = new JSONObject(new String(rawBody, StandardCharsets.UTF_8));
            Object eventType = body.opt("event");
            if (!(eventType instanceof String type) || type.isBlank()) {
                throw invalidPayload();
            }
            if (!"payment.captured".equals(type)) {
                return null;
            }

            JSONObject payment = body.getJSONObject("payload")
                    .getJSONObject("payment")
                    .getJSONObject("entity");

            Object orderIdValue = payment.opt("order_id");
            Object paymentIdValue = payment.opt("id");
            Object amountValue = payment.opt("amount");

            if (!(orderIdValue instanceof String orderId)
                    || !ORDER_ID.matcher(orderId).matches()
                    || !(paymentIdValue instanceof String paymentId)
                    || !PAYMENT_ID.matcher(paymentId).matches()
                    || !(amountValue instanceof Integer || amountValue instanceof Long)
                    || ((Number) amountValue).longValue() <= 0
                    || !"INR".equals(payment.opt("currency"))
                    || !"captured".equals(payment.opt("status"))
                    || !Boolean.TRUE.equals(payment.opt("captured"))) {
                throw invalidPayload();
            }

            return new CapturedEvent(
                    orderId, paymentId, ((Number) amountValue).longValue(), "INR");
        } catch (JSONException e) {
            throw invalidPayload();
        }
    }

    private SavedPayment findByOrderId(String orderId) {
        return jdbc.query("""
                select amount, currency, payment_method, payment_status, razorpay_payment_id
                  from payments
                 where razorpay_order_id = ?
                """, rs -> rs.next()
                ? new SavedPayment(
                        rs.getLong(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5))
                : null, orderId);
    }

    private static boolean alreadyRecorded(SavedPayment payment, String paymentId) {
        return payment != null
                && paymentId.equals(payment.paymentId())
                && ("PAID".equals(payment.status()) || "REFUNDED".equals(payment.status()));
    }

    private static ResponseStatusException invalidPayload() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid Razorpay webhook payload");
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    private record CapturedEvent(String orderId, String paymentId, long amount, String currency) {}

    private record SavedPayment(long amount, String currency, String method, String status,
                                String paymentId) {}
}