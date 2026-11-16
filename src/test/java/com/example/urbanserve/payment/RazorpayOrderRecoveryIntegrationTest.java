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

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:razorpay-order-recovery-tests",
        "spring.jpa.hibernate.ddl-auto=validate",
        "razorpay.key-id=",
        "razorpay.key-secret="
})
class RazorpayOrderRecoveryIntegrationTest {

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
    void retryAttachesTheOriginalOrderAfterALostResponse() {
        long id = createOnlinePayment(411L);
        String receipt = simulateLostCreateResponse(id);

        when(gateway.findOrderByReceipt(471700L, receipt))
                .thenReturn(Optional.of("order_recovered_411"));

        assertEquals("order_recovered_411", orders.prepareTestOrder(id));
        assertEquals("order_recovered_411", orders.prepareTestOrder(id));

        var saved = payments.findById(id).orElseThrow();
        assertEquals("order_recovered_411", saved.getRazorpayOrderId());
        assertEquals(PaymentStatus.PENDING, saved.getPaymentStatus());

        verify(gateway, times(1)).createOrder(471700L, receipt);
        verify(gateway, times(1)).findOrderByReceipt(471700L, receipt);
    }

    @Test
    void missingOrderKeepsTheReceiptAndDoesNotSendAnotherCreateRequest() {
        long id = createOnlinePayment(412L);
        String receipt = simulateLostCreateResponse(id);

        when(gateway.findOrderByReceipt(471700L, receipt)).thenReturn(Optional.empty());

        assertEquals(HttpStatus.CONFLICT, assertThrows(
                ResponseStatusException.class,
                () -> orders.prepareTestOrder(id)).getStatusCode());

        assertEquals(receipt, receiptFor(id));
        assertNull(payments.findById(id).orElseThrow().getRazorpayOrderId());
        verify(gateway, times(1)).createOrder(471700L, receipt);
        verify(gateway, times(1)).findOrderByReceipt(471700L, receipt);
    }

    private String simulateLostCreateResponse(long paymentId) {
        when(gateway.createOrder(eq(471700L), startsWith("us_")))
                .thenThrow(new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY, "Simulated lost response"));

        assertEquals(HttpStatus.BAD_GATEWAY, assertThrows(
                ResponseStatusException.class,
                () -> orders.prepareTestOrder(paymentId)).getStatusCode());

        String receipt = receiptFor(paymentId);
        assertNotNull(receipt);
        assertNull(payments.findById(paymentId).orElseThrow().getRazorpayOrderId());
        return receipt;
    }

    private String receiptFor(long paymentId) {
        return jdbc.queryForObject(
                "select razorpay_receipt from payments where id = ?", String.class, paymentId);
    }

    private long createOnlinePayment(long bookingId) {
        var request = new CreatePaymentRequest();
        request.setBookingId(bookingId);
        request.setAmount(471700L);
        request.setPaymentMethod(PaymentMethod.ONLINE);
        return paymentService.createPayment(request).getPaymentId();
    }
}