package com.example.urbanserve.payment;

import com.example.urbanserve.payment.service.RazorpayOrderGateway;
import com.example.urbanserve.payment.service.RazorpayPaymentVerificationService;
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
        "spring.datasource.url=jdbc:h2:mem:razorpay-verification-tests;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=validate",
        "razorpay.key-id=",
        "razorpay.key-secret=urbanserve-verification-test-secret"
})
class RazorpayPaymentVerificationIntegrationTest {

    private static final long BOOKING_ID = 91001L;
    private static final long AMOUNT_PAISE = 471700L;
    private static final String ORDER_ID = "order_VerificationTest";
    private static final String PAYMENT_ID = "pay_VerificationTest";
    private static final String SIGNATURE =
            "7c5aabb7d6854cb145e13117a7c688931697712f4a4458bda3b284ceaa31a9df";

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private RazorpayPaymentVerificationService service;

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
                """, BOOKING_ID, AMOUNT_PAISE, ORDER_ID);
    }

    @Test
    void capturedPaymentIsSavedAndTheSameCallbackCanBeRetried() {
        stubPayment(ORDER_ID, AMOUNT_PAISE, "captured", true, 0);

        var first = service.verifyTestPayment(BOOKING_ID, PAYMENT_ID, SIGNATURE);
        var retry = service.verifyTestPayment(BOOKING_ID, PAYMENT_ID, SIGNATURE);

        assertEquals("PAID", first.paymentStatus());
        assertEquals(AMOUNT_PAISE, first.amount());
        assertEquals(PAYMENT_ID, first.razorpayPaymentId());
        assertEquals(first, retry);
        assertEquals("PAID", storedStatus());
        assertEquals(PAYMENT_ID, storedPaymentId());
        verify(gateway, times(1)).fetchPayment(PAYMENT_ID);
    }

    @Test
    void invalidSignatureIsRejectedWithoutCallingRazorpay() {
        var error = assertThrows(ResponseStatusException.class,
                () -> service.verifyTestPayment(BOOKING_ID, PAYMENT_ID, "0".repeat(64)));

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        verifyNoInteractions(gateway);
        assertStillPending();
    }

    @Test
    void authorizedPaymentStaysPendingUntilCaptured() {
        stubPayment(ORDER_ID, AMOUNT_PAISE, "authorized", false, 0);
        assertVerificationConflict();

        stubPayment(ORDER_ID, AMOUNT_PAISE, "captured", true, 0);
        var verified = service.verifyTestPayment(BOOKING_ID, PAYMENT_ID, SIGNATURE);

        assertEquals("PAID", verified.paymentStatus());
    }

    @Test
    void paymentForADifferentOrderIsRejected() {
        stubPayment("order_Different", AMOUNT_PAISE, "captured", true, 0);
        assertVerificationConflict();
    }

    @Test
    void wrongAmountIsRejected() {
        stubPayment(ORDER_ID, AMOUNT_PAISE - 1, "captured", true, 0);
        assertVerificationConflict();
    }

    @Test
    void partiallyRefundedPaymentIsNotRecordedAsFullyPaid() {
        stubPayment(ORDER_ID, AMOUNT_PAISE, "captured", true, 100);
        assertVerificationConflict();
    }

    private void stubPayment(
            String orderId, long amount, String status, boolean captured, long refunded) {
        when(gateway.fetchPayment(PAYMENT_ID)).thenReturn(
                new RazorpayOrderGateway.FetchedPayment(
                        PAYMENT_ID, orderId, amount, "INR", status, captured, refunded));
    }

    private void assertVerificationConflict() {
        var error = assertThrows(ResponseStatusException.class,
                () -> service.verifyTestPayment(BOOKING_ID, PAYMENT_ID, SIGNATURE));
        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        assertStillPending();
    }

    private void assertStillPending() {
        assertEquals("PENDING", storedStatus());
        assertNull(storedPaymentId());
    }

    private String storedStatus() {
        return jdbc.queryForObject(
                "select payment_status from payments where booking_id = ?",
                String.class, BOOKING_ID);
    }

    private String storedPaymentId() {
        return jdbc.queryForObject(
                "select razorpay_payment_id from payments where booking_id = ?",
                String.class, BOOKING_ID);
    }
}