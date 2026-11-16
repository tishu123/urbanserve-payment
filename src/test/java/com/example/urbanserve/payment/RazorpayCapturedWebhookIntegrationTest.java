package com.example.urbanserve.payment;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.example.urbanserve.payment.service.RazorpayCapturedWebhookService;
import com.example.urbanserve.payment.service.RazorpayOrderGateway;
import com.example.urbanserve.payment.service.RazorpayOrderGateway.FetchedPayment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:razorpay-captured-webhook-tests;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=validate",
        "razorpay.key-id=",
        "razorpay.key-secret=",
        "razorpay.webhook-secret=urbanserve-captured-webhook-test-fixture"
})
class RazorpayCapturedWebhookIntegrationTest {

    private static final String SECRET = "urbanserve-captured-webhook-test-fixture";
    private static final long BOOKING_ID = 93001L;
    private static final long AMOUNT = 471700L;
    private static final String ORDER_ID = "order_WebhookTest";
    private static final String PAYMENT_ID = "pay_WebhookTest";

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private RazorpayCapturedWebhookService service;

    @MockitoBean
    private RazorpayOrderGateway gateway;

    @BeforeEach
    void insertPendingPayment() {
        jdbc.update("delete from payments where booking_id = ?", BOOKING_ID);
        jdbc.update("""
                insert into payments (
                    booking_id, amount, currency, payment_method, payment_status,
                    razorpay_order_id, created_at, updated_at
                ) values (?, ?, 'INR', 'ONLINE', 'PENDING', ?, current_timestamp, current_timestamp)
                """, BOOKING_ID, AMOUNT, ORDER_ID);
    }

    @Test
    void captureIsSavedWithoutBrowserCallbackAndDuplicateIsHarmless() throws Exception {
        when(gateway.fetchPayment(PAYMENT_ID)).thenReturn(
                new FetchedPayment(PAYMENT_ID, ORDER_ID, AMOUNT, "INR", "captured", true, 0));

        byte[] body = capturedEvent(ORDER_ID, AMOUNT);
        deliver(body);

        assertEquals("PAID", status());
        assertEquals(PAYMENT_ID, storedPaymentId());
        var firstUpdate = jdbc.queryForObject(
                "select updated_at from payments where booking_id = ?",
                java.time.LocalDateTime.class, BOOKING_ID);

        deliver(body);

        assertEquals("PAID", status());
        assertEquals(PAYMENT_ID, storedPaymentId());
        assertEquals(firstUpdate, jdbc.queryForObject(
                "select updated_at from payments where booking_id = ?",
                java.time.LocalDateTime.class, BOOKING_ID));
        verify(gateway, times(1)).fetchPayment(PAYMENT_ID);
    }

    @Test
    void badSignatureIsRejectedBeforeCheckingThePayment() {
        var error = assertThrows(ResponseStatusException.class,
                () -> service.handle(capturedEvent(ORDER_ID, AMOUNT), "0".repeat(64)));

        assertEquals(HttpStatus.UNAUTHORIZED, error.getStatusCode());
        assertPending();
        verifyNoInteractions(gateway);
    }

    @Test
    void wrongAmountIsRejected() {
        assertConflict(capturedEvent(ORDER_ID, AMOUNT - 1));
        assertPending();
        verifyNoInteractions(gateway);
    }

    @Test
    void unknownOrderDoesNotChangeTheBookingPayment() {
        assertConflict(capturedEvent("order_Unknown", AMOUNT));
        assertPending();
        verifyNoInteractions(gateway);
    }

    @Test
    void authorizedPaymentStaysPending() {
        when(gateway.fetchPayment(PAYMENT_ID)).thenReturn(
                new FetchedPayment(PAYMENT_ID, ORDER_ID, AMOUNT, "INR", "authorized", false, 0));

        assertConflict(capturedEvent(ORDER_ID, AMOUNT));
        assertPending();
    }

    @Test
    void delayedCaptureDoesNotUndoARefund() throws Exception {
        jdbc.update("""
                update payments set payment_status = 'REFUNDED', razorpay_payment_id = ?
                 where booking_id = ?
                """, PAYMENT_ID, BOOKING_ID);

        deliver(capturedEvent(ORDER_ID, AMOUNT));

        assertEquals("REFUNDED", status());
        assertEquals(PAYMENT_ID, storedPaymentId());
        verifyNoInteractions(gateway);
    }

    @Test
    void failureEventDoesNotOverwriteAnExistingPayment() throws Exception {
        jdbc.update("""
                update payments set payment_status = 'PAID', razorpay_payment_id = ?
                 where booking_id = ?
                """, PAYMENT_ID, BOOKING_ID);

        deliver("{\"event\":\"payment.failed\"}".getBytes(StandardCharsets.UTF_8));

        assertEquals("PAID", status());
        assertEquals(PAYMENT_ID, storedPaymentId());
        verifyNoInteractions(gateway);
    }

    private void deliver(byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        service.handle(body, HexFormat.of().formatHex(mac.doFinal(body)));
    }

    private void assertConflict(byte[] body) {
        var error = assertThrows(ResponseStatusException.class, () -> deliver(body));
        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
    }

    private void assertPending() {
        assertEquals("PENDING", status());
        assertNull(storedPaymentId());
    }

    private String status() {
        return jdbc.queryForObject(
                "select payment_status from payments where booking_id = ?",
                String.class, BOOKING_ID);
    }

    private String storedPaymentId() {
        return jdbc.queryForObject(
                "select razorpay_payment_id from payments where booking_id = ?",
                String.class, BOOKING_ID);
    }

    private static byte[] capturedEvent(String orderId, long amount) {
        return """
                {
                  "event": "payment.captured",
                  "payload": {
                    "payment": {
                      "entity": {
                        "id": "%s",
                        "order_id": "%s",
                        "amount": %d,
                        "currency": "INR",
                        "status": "captured",
                        "captured": true
                      }
                    }
                  }
                }
                """.formatted(PAYMENT_ID, orderId, amount).getBytes(StandardCharsets.UTF_8);
    }
}