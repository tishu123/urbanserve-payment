package com.example.urbanserve.payment;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.example.urbanserve.payment.dto.CreatePaymentRequest;
import com.example.urbanserve.payment.dto.PaymentResponse;
import com.example.urbanserve.payment.entity.PaymentMethod;
import com.example.urbanserve.payment.repository.PaymentRepository;
import com.example.urbanserve.payment.service.PaymentService;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:payment-retry-test",
        "spring.jpa.hibernate.ddl-auto=validate"
})
class PaymentRetryIntegrationTest {

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private PaymentRepository payments;

    @Test
    void matchingRetryReturnsTheOriginalPayment() {
        PaymentResponse first = paymentService.createPayment(
                request(201L, 471700L, PaymentMethod.ONLINE));
        PaymentResponse retry = paymentService.createPayment(
                request(201L, 471700L, PaymentMethod.ONLINE));

        assertNotNull(first.getPaymentId());
        assertEquals(first.getPaymentId(), retry.getPaymentId());
        assertEquals(first.getPaymentStatus(), retry.getPaymentStatus());
        assertEquals(1, payments.findByBookingId(201L).size());
    }

    @Test
    void differentAmountOrMethodReturnsConflictWithoutChangingThePayment() {
        paymentService.createPayment(request(202L, 471700L, PaymentMethod.ONLINE));

        ResponseStatusException differentAmount = assertThrows(
                ResponseStatusException.class,
                () -> paymentService.createPayment(
                        request(202L, 471800L, PaymentMethod.ONLINE))
        );

        ResponseStatusException differentMethod = assertThrows(
                ResponseStatusException.class,
                () -> paymentService.createPayment(
                        request(202L, 471700L, PaymentMethod.COD_CASH))
        );

        assertEquals(HttpStatus.CONFLICT, differentAmount.getStatusCode());
        assertEquals(HttpStatus.CONFLICT, differentMethod.getStatusCode());

        var saved = payments.findByBookingId(202L);
        assertEquals(1, saved.size());
        assertEquals(471700L, saved.get(0).getAmount());
        assertEquals(PaymentMethod.ONLINE, saved.get(0).getPaymentMethod());
    }

    @Test
    void concurrentMatchingRequestsReturnTheSamePayment() throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);

        try {
            var first = executor.submit(() -> createWhenReleased(ready, start));
            var second = executor.submit(() -> createWhenReleased(ready, start));

            if (!ready.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Both requests did not become ready");
            }
            start.countDown();

            Long firstId = first.get(10, TimeUnit.SECONDS).getPaymentId();
            Long secondId = second.get(10, TimeUnit.SECONDS).getPaymentId();

            assertNotNull(firstId);
            assertEquals(firstId, secondId);
            assertEquals(1, payments.findByBookingId(203L).size());
        } finally {
            executor.shutdownNow();
        }
    }

    private PaymentResponse createWhenReleased(CountDownLatch ready, CountDownLatch start)
            throws InterruptedException {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new AssertionError("Concurrent test did not start");
        }
        return paymentService.createPayment(request(203L, 471700L, PaymentMethod.ONLINE));
    }

    private CreatePaymentRequest request(long bookingId, long amount, PaymentMethod method) {
        CreatePaymentRequest request = new CreatePaymentRequest();
        request.setBookingId(bookingId);
        request.setAmount(amount);
        request.setPaymentMethod(method);
        return request;
    }
}