package com.example.urbanserve.payment;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "server.address=127.0.0.1",
            "payment.internal-token=payment-status-endpoint-test-token-0123456789",
            "spring.datasource.url=jdbc:h2:mem:payment-status-endpoint-tests;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.jpa.hibernate.ddl-auto=validate",
            "razorpay.key-id=",
            "razorpay.key-secret="
        })
class PaymentStatusEndpointIntegrationTest {

    private static final String TOKEN = "payment-status-endpoint-test-token-0123456789";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void missingOrWrongTokenIsRejected() throws Exception {
        assertEquals(401, getStatus(92001L, null).statusCode());
        assertEquals(401, getStatus(92001L, "wrong-token").statusCode());
    }

    @Test
    void bookingWithoutPaymentReturnsNotStartedWithoutCreatingARow() throws Exception {
        long bookingId = 92002L;

        var response = getStatus(bookingId, TOKEN);

        assertEquals(200, response.statusCode());
        var body = new JSONObject(response.body());
        assertEquals(bookingId, body.getLong("bookingId"));
        assertEquals("NOT_STARTED", body.getString("paymentStatus"));
        assertTrue(body.isNull("amount"));
        assertEquals(Long.valueOf(0), jdbc.queryForObject(
                "select count(*) from payments where booking_id = ?", Long.class, bookingId));
    }

    @Test
    void pendingOnlinePaymentReturnsPending() throws Exception {
        long bookingId = 92003L;
        insertPayment(bookingId, "PENDING", "order_StatusPending", null);

        var response = getStatus(bookingId, TOKEN);

        assertEquals(200, response.statusCode());
        var body = new JSONObject(response.body());
        assertEquals("PENDING", body.getString("paymentStatus"));
        assertEquals("ONLINE", body.getString("paymentMethod"));
        assertEquals(471700L, body.getLong("amount"));
    }

    @Test
    void paidBookingReturnsSavedStatusWithoutGatewayIdentifiers() throws Exception {
        long bookingId = 92004L;
        insertPayment(bookingId, "PAID", "order_StatusPaid", "pay_StatusPaid");

        var response = getStatus(bookingId, TOKEN);

        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue("Cache-Control").orElse("").contains("no-store"));

        var body = new JSONObject(response.body());
        assertEquals(bookingId, body.getLong("bookingId"));
        assertEquals("PAID", body.getString("paymentStatus"));
        assertEquals("ONLINE", body.getString("paymentMethod"));
        assertEquals("INR", body.getString("currency"));
        assertEquals(471700L, body.getLong("amount"));
        assertFalse(body.has("razorpayOrderId"));
        assertFalse(body.has("razorpayPaymentId"));
        assertFalse(body.has("razorpaySignature"));
    }

    private void insertPayment(long bookingId, String status, String orderId, String paymentId) {
        jdbc.update("""
                insert into payments (
                    booking_id, amount, currency, payment_method, payment_status,
                    razorpay_order_id, razorpay_payment_id, created_at, updated_at
                ) values (?, 471700, 'INR', 'ONLINE', ?, ?, ?, current_timestamp, current_timestamp)
                """, bookingId, status, orderId, paymentId);
    }

    private HttpResponse<String> getStatus(long bookingId, String token) throws Exception {
        var request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port
                        + "/api/payments/bookings/" + bookingId + "/status"))
                .timeout(Duration.ofSeconds(10))
                .GET();

        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }

        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}