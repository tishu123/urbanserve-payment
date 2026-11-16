package com.example.urbanserve.payment;

import com.example.urbanserve.payment.service.RazorpayOrderGateway;
import com.example.urbanserve.payment.service.RazorpayOrderGateway.FetchedPayment;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "server.address=127.0.0.1",
            "payment.internal-token=urbanserve-webhook-internal-token-fixture",
            "razorpay.webhook-secret=urbanserve-webhook-http-test-fixture",
            "razorpay.key-id=",
            "razorpay.key-secret=",
            "spring.datasource.url=jdbc:h2:mem:razorpay-webhook-http-tests;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.jpa.hibernate.ddl-auto=validate"
        })
class RazorpayWebhookEndpointIntegrationTest {

    private static final String SECRET = "urbanserve-webhook-http-test-fixture";
    private static final long BOOKING_ID = 94001L;
    private static final long AMOUNT = 471700L;
    private static final String ORDER_ID = "order_WebhookHttpTest";
    private static final String PAYMENT_ID = "pay_WebhookHttpTest";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

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
    void signedCaptureAndItsDuplicateReturn204AndRecordOnePayment() throws Exception {
        when(gateway.fetchPayment(PAYMENT_ID)).thenReturn(
                new FetchedPayment(PAYMENT_ID, ORDER_ID, AMOUNT, "INR", "captured", true, 0));

        byte[] body = capturedBody();
        String signature = sign(body);

        assertEquals(204, post(body, signature));
        assertEquals(204, post(body, signature));

        assertEquals("PAID", storedStatus());
        assertEquals(PAYMENT_ID, storedPaymentId());
        verify(gateway, times(1)).fetchPayment(PAYMENT_ID);
    }

    @Test
    void missingSignatureIsRejected() throws Exception {
        assertEquals(401, post(capturedBody(), null));

        assertPending();
        verifyNoInteractions(gateway);
    }

    @Test
    void reformattingTheSignedBodyInvalidatesTheSignature() throws Exception {
        byte[] original = capturedBody();
        byte[] reformatted = new String(original, StandardCharsets.UTF_8)
                .stripTrailing()
                .getBytes(StandardCharsets.UTF_8);

        assertEquals(401, post(reformatted, sign(original)));

        assertPending();
        verifyNoInteractions(gateway);
    }

    @Test
    void authorizedButUncapturedPaymentIsNotAcknowledgedAsProcessed() throws Exception {
        when(gateway.fetchPayment(PAYMENT_ID)).thenReturn(
                new FetchedPayment(PAYMENT_ID, ORDER_ID, AMOUNT, "INR", "authorized", false, 0));

        byte[] body = capturedBody();
        assertEquals(409, post(body, sign(body)));

        assertPending();
        verify(gateway).fetchPayment(PAYMENT_ID);
    }

    @Test
    void oversizedRequestIsRejected() throws Exception {
        byte[] body = new byte[256 * 1024 + 1];

        assertEquals(413, post(body, null));

        assertPending();
        verifyNoInteractions(gateway);
    }

    @Test
    void privatePaymentStatusRouteStillRequiresTheInternalToken() throws Exception {
        var request = HttpRequest.newBuilder()
                .uri(uri("/api/payments/bookings/" + BOOKING_ID + "/status"))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        var response = http.send(request, HttpResponse.BodyHandlers.discarding());

        assertEquals(401, response.statusCode());
    }

    private int post(byte[] body, String signature) throws Exception {
        var request = HttpRequest.newBuilder()
                .uri(uri("/api/webhooks/razorpay"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));

        if (signature != null) {
            request.header("X-Razorpay-Signature", signature);
        }

        return http.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private void assertPending() {
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

    private static String sign(byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(body));
    }

    private static byte[] capturedBody() {
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
                """.formatted(PAYMENT_ID, ORDER_ID, AMOUNT).getBytes(StandardCharsets.UTF_8);
    }
}