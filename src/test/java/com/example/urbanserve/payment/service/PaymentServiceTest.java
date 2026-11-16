
package com.example.urbanserve.payment.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import static org.mockito.ArgumentMatchers.any;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.junit.jupiter.MockitoExtension;

import com.example.urbanserve.payment.dto.CreatePaymentRequest;
import com.example.urbanserve.payment.dto.PaymentResponse;
import com.example.urbanserve.payment.entity.Payment;
import com.example.urbanserve.payment.entity.PaymentMethod;
import com.example.urbanserve.payment.entity.PaymentStatus;
import com.example.urbanserve.payment.repository.PaymentRepository;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock
    private PaymentRepository paymentRepository;

    @InjectMocks
    private PaymentService paymentService;

    // Rohnit: Test successful online payment record creation.
    @Test
    void shouldCreateOnlinePayment() {

        CreatePaymentRequest request = new CreatePaymentRequest();
        request.setBookingId(105L);
        request.setAmount(149900L);
        request.setPaymentMethod(PaymentMethod.ONLINE);

        when(paymentRepository.save(any(Payment.class)))
                .thenAnswer(invocation -> {
                    Payment payment = invocation.getArgument(0);
                    payment.setId(1L);
                    return payment;
                });

        PaymentResponse response = paymentService.createPayment(request);

        assertEquals(1L, response.getPaymentId());
        assertEquals(105L, response.getBookingId());
        assertEquals(149900L, response.getAmount());
        assertEquals("INR", response.getCurrency());
        assertEquals(PaymentMethod.ONLINE, response.getPaymentMethod());
        assertEquals(PaymentStatus.PENDING, response.getPaymentStatus());

        verify(paymentRepository, times(1)).save(any(Payment.class));
    }

    // Rohnit: Test COD cash payment creation.
    @Test
    void shouldCreateCodCashPayment() {

        CreatePaymentRequest request = new CreatePaymentRequest();
        request.setBookingId(106L);
        request.setAmount(79900L);
        request.setPaymentMethod(PaymentMethod.COD_CASH);

        when(paymentRepository.save(any(Payment.class)))
                .thenAnswer(invocation -> {
                    Payment payment = invocation.getArgument(0);
                    payment.setId(2L);
                    return payment;
                });

        PaymentResponse response = paymentService.createPayment(request);

        assertEquals(PaymentMethod.COD_CASH, response.getPaymentMethod());
        assertEquals(PaymentStatus.PENDING, response.getPaymentStatus());

        verify(paymentRepository, times(1)).save(any(Payment.class));
    }

    // Rohnit: Test COD UPI payment creation.
    @Test
    void shouldCreateCodUpiPayment() {

        CreatePaymentRequest request = new CreatePaymentRequest();
        request.setBookingId(107L);
        request.setAmount(59900L);
        request.setPaymentMethod(PaymentMethod.COD_UPI);

        when(paymentRepository.save(any(Payment.class)))
                .thenAnswer(invocation -> {
                    Payment payment = invocation.getArgument(0);
                    payment.setId(3L);
                    return payment;
                });

        PaymentResponse response = paymentService.createPayment(request);

        assertEquals(PaymentMethod.COD_UPI, response.getPaymentMethod());
        assertEquals(PaymentStatus.PENDING, response.getPaymentStatus());

        verify(paymentRepository, times(1)).save(any(Payment.class));
    }

    // Rohnit: Reject payment requests with invalid amounts.
    @Test
    void shouldRejectInvalidPaymentAmount() {

        CreatePaymentRequest request = new CreatePaymentRequest();
        request.setBookingId(108L);
        request.setAmount(-500L);
        request.setPaymentMethod(PaymentMethod.ONLINE);

        assertThrows(
                IllegalArgumentException.class,
                () -> paymentService.createPayment(request)
        );

        verify(paymentRepository, never()).save(any(Payment.class));
    }
}
