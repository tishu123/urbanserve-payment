package com.example.urbanserve.payment;

import com.example.urbanserve.payment.service.RazorpayPaymentVerificationService;
import com.example.urbanserve.payment.service.RazorpayPaymentVerificationService.VerifiedPayment;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "server.address=127.0.0.1",
            "payment.internal-token=razorpay-verification-endpoint-test-token-0123456789",
            "spring.datasource.url=jdbc:h2:mem:razorpay-verification-endpoint-tests;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.jpa.hibernate.ddl-auto=validate",
            "razorpay.key-id=",
            "razorpay.key-secret="
        })
class RazorpayVerificationEndpointIntegrationTest {

    private static final String TOKEN =
            "razorpay-verification-endpoint-test-token-0123456789";
    private static final long BOOKING_ID = 91001L;
    private static final String PAYMENT_ID = "pay_VerificationTest";
    private static final String SIGNATURE = "a".repeat(64);

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Value("${local.server.port}")
    private int port;

    @MockitoBean
    private RazorpayPaymentVerificationService verificationService;

    @Test
    void missingOrIncorrectTokenIsRejectedBeforeVerification() throws Exception {
        assertEquals(401, post(null, requestBody(SIGNATURE)).statusCode());
        assertEquals(401, post("wrong-token", requestBody(SIGNATURE)).statusCode());

        verifyNoInteractions(verificationService);
    }

    @Test
    void malformedSignatureIsRejectedBeforeVerification() throws Exception {
        var response = post(TOKEN, requestBody("not-a-signature"));

        assertEquals(400, response.statusCode());
        verifyNoInteractions(verificationService);
    }

    @Test
    void authenticatedRequestReturnsTheVerifiedPayment() throws Exception {
        when(verificationService.verifyTestPayment(BOOKING_ID, PAYMENT_ID, SIGNATURE))
                .thenReturn(new VerifiedPayment(
                        71L, BOOKING_ID, 471700L, "INR", "ONLINE",
                        "PAID", "order_VerificationTest", PAYMENT_ID));

        var response = post(TOKEN, requestBody(SIGNATURE));

        assertEquals(200, response.statusCode());
        var body = new JSONObject(response.body());
        assertEquals(BOOKING_ID, body.getLong("bookingId"));
        assertEquals(471700L, body.getLong("amount"));
        assertEquals("PAID", body.getString("paymentStatus"));
        assertEquals("order_VerificationTest", body.getString("razorpayOrderId"));
        assertEquals(PAYMENT_ID, body.getString("razorpayPaymentId"));
        verify(verificationService).verifyTestPayment(BOOKING_ID, PAYMENT_ID, SIGNATURE);
    }

    @Test
    void paymentNotYetCapturedReturnsConflict() throws Exception {
        when(verificationService.verifyTestPayment(BOOKING_ID, PAYMENT_ID, SIGNATURE))
                .thenThrow(new ResponseStatusException(
                        HttpStatus.CONFLICT, "Razorpay has not confirmed capture"));

        var response = post(TOKEN, requestBody(SIGNATURE));

        assertEquals(409, response.statusCode());
        verify(verificationService).verifyTestPayment(BOOKING_ID, PAYMENT_ID, SIGNATURE);
    }

    private static String requestBody(String signature) throws org.json.JSONException {
        return new JSONObject()
                .put("bookingId", BOOKING_ID)
                .put("razorpayPaymentId", PAYMENT_ID)
                .put("razorpaySignature", signature)
                .toString();
    }

    private HttpResponse<String> post(String token, String body) throws Exception {
        var request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/api/payments/razorpay/verify"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));

        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }

        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}