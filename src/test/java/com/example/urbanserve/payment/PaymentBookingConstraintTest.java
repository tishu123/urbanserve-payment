package com.example.urbanserve.payment;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import com.example.urbanserve.payment.entity.Payment;
import com.example.urbanserve.payment.entity.PaymentMethod;
import com.example.urbanserve.payment.entity.PaymentStatus;
import com.example.urbanserve.payment.repository.PaymentRepository;

@SpringBootTest(properties = {
        "spring.datasource.url=${PAYMENT_TEST_JDBC_URL:jdbc:h2:mem:payment-booking-constraint-test}",
        "spring.datasource.username=${PAYMENT_TEST_DB_USER:sa}",
        "spring.datasource.password=${PAYMENT_TEST_DB_PASSWORD:}",
        "spring.jpa.hibernate.ddl-auto=validate"
})
@Transactional
class PaymentBookingConstraintTest {

    @Autowired
    private PaymentRepository payments;

    @Test
    void databaseRejectsASecondPaymentRecordForTheSameBooking() {
        Payment first = payments.saveAndFlush(newPayment(105L));
        assertNotNull(first.getId());

        assertThrows(
                DataIntegrityViolationException.class,
                () -> payments.saveAndFlush(newPayment(105L))
        );
    }

    private Payment newPayment(long bookingId) {
        Payment payment = new Payment();
        payment.setBookingId(bookingId);
        payment.setAmount(471700L);
        payment.setCurrency("INR");
        payment.setPaymentMethod(PaymentMethod.ONLINE);
        payment.setPaymentStatus(PaymentStatus.PENDING);
        return payment;
    }
}