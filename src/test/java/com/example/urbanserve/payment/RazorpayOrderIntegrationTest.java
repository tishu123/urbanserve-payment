package com.example.urbanserve.payment;

import com.example.urbanserve.payment.dto.CreatePaymentRequest;
import com.example.urbanserve.payment.entity.PaymentMethod;
import com.example.urbanserve.payment.entity.PaymentStatus;
import com.example.urbanserve.payment.repository.PaymentRepository;
import com.example.urbanserve.payment.service.PaymentService;
import com.example.urbanserve.payment.service.RazorpayOrderGateway;
import com.example.urbanserve.payment.service.RazorpayOrderService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:razorpay-order-tests",
        "spring.jpa.hibernate.ddl-auto=validate",
        "razorpay.key-id=",
        "razorpay.key-secret="
})
class RazorpayOrderIntegrationTest {

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private PaymentRepository payments;

    @Autowired
    private RazorpayOrderService orders;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private RazorpayOrderGateway gateway;

    @Test
    void savesOrderAndReturnsItOnRetryWithoutCallingRazorpayAgain() {
        long id = createPayment(401L, PaymentMethod.ONLINE);

        when(gateway.createOrder(eq(471700L), startsWith("us_")))
                .thenAnswer(call -> {
                    String receipt = jdbc.queryForObject(
                            "select razorpay_receipt from payments where id = ?",
                            String.class, id);
                    assertEquals(call.getArgument(1, String.class), receipt);
                    return "order_test_401";
                });

        assertEquals("order_test_401", orders.prepareTestOrder(id));
        assertEquals("order_test_401", orders.prepareTestOrder(id));

        var saved = payments.findById(id).orElseThrow();
        assertEquals("order_test_401", saved.getRazorpayOrderId());
        assertEquals(PaymentStatus.PENDING, saved.getPaymentStatus());
        verify(gateway, times(1)).createOrder(eq(471700L), startsWith("us_"));
    }

    @Test
    void uncertainGatewayResultDoesNotStartAnotherOrder() {
        long id = createPayment(402L, PaymentMethod.ONLINE);

        when(gateway.createOrder(eq(471700L), startsWith("us_")))
                .thenThrow(new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY, "Simulated lost response"));

        assertEquals(HttpStatus.BAD_GATEWAY, assertThrows(
                ResponseStatusException.class,
                () -> orders.prepareTestOrder(id)).getStatusCode());

        assertNotNull(jdbc.queryForObject(
                "select razorpay_receipt from payments where id = ?",
                String.class, id));
        assertNull(payments.findById(id).orElseThrow().getRazorpayOrderId());

        assertEquals(HttpStatus.CONFLICT, assertThrows(
                ResponseStatusException.class,
                () -> orders.prepareTestOrder(id)).getStatusCode());

        verify(gateway, times(1)).createOrder(eq(471700L), startsWith("us_"));
    }

    @Test
    void cashPaymentNeverCallsRazorpay() {
        long id = createPayment(403L, PaymentMethod.COD_CASH);

        assertEquals(HttpStatus.CONFLICT, assertThrows(
                ResponseStatusException.class,
                () -> orders.prepareTestOrder(id)).getStatusCode());

        verifyNoInteractions(gateway);
    }

    private long createPayment(long bookingId, PaymentMethod method) {
        var request = new CreatePaymentRequest();
        request.setBookingId(bookingId);
        request.setAmount(471700L);
        request.setPaymentMethod(method);
        return paymentService.createPayment(request).getPaymentId();
    }
}