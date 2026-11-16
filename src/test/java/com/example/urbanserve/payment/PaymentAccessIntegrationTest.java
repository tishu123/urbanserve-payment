package com.example.urbanserve.payment;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import com.example.urbanserve.payment.repository.PaymentRepository;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "payment.internal-token=" + PaymentAccessIntegrationTest.TEST_TOKEN,
                "spring.datasource.url=jdbc:h2:mem:payment-access-tests",
                "spring.jpa.hibernate.ddl-auto=create-drop",
                "razorpay.key-id=",
                "razorpay.key-secret="
        }
)
class PaymentAccessIntegrationTest {

    static final String TEST_TOKEN = "payment-integration-test-token-0123456789";

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private PaymentRepository payments;

    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    void rejectsRequestWithoutTokenAndDoesNotCreatePayment() throws Exception {
        assertEquals(401, createPayment(301L, null).statusCode());
        assertTrue(payments.findByBookingId(301L).isEmpty());
    }

    @Test
    void rejectsWrongTokenAndDoesNotCreatePayment() throws Exception {
        assertEquals(401, createPayment(302L, "wrong-token").statusCode());
        assertTrue(payments.findByBookingId(302L).isEmpty());
    }

    @Test
    void acceptsValidServiceTokenAndCreatesPayment() throws Exception {
        assertEquals(201, createPayment(303L, TEST_TOKEN).statusCode());

        var records = payments.findByBookingId(303L);
        assertEquals(1, records.size());
        assertEquals(Long.valueOf(471700L), records.get(0).getAmount());
    }

    private HttpResponse<String> createPayment(long bookingId, String token)
            throws Exception {

        String body = """
                {
                  "bookingId": %d,
                  "amount": 471700,
                  "paymentMethod": "COD_CASH"
                }
                """.formatted(bookingId);

        var request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/api/payments"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));

        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }

        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}