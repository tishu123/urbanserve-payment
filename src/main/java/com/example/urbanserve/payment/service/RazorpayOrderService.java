package com.example.urbanserve.payment.service;

import com.example.urbanserve.payment.entity.PaymentMethod;
import com.example.urbanserve.payment.entity.PaymentStatus;
import com.example.urbanserve.payment.repository.PaymentRepository;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@Service
public class RazorpayOrderService {

    private final PaymentRepository payments;
    private final JdbcTemplate jdbc;
    private final RazorpayOrderGateway gateway;

    public RazorpayOrderService(
            PaymentRepository payments, JdbcTemplate jdbc, RazorpayOrderGateway gateway) {
        this.payments = payments;
        this.jdbc = jdbc;
        this.gateway = gateway;
    }
    public void requireTestMode() {
        gateway.requireTestMode();
    }

    // Save the receipt before sending any create request to Razorpay.
    @Transactional(propagation = Propagation.NEVER)
    public String prepareTestOrder(long paymentId) {
        var payment = payments.findById(paymentId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Payment not found"));

        if (payment.getPaymentMethod() != PaymentMethod.ONLINE
                || payment.getPaymentStatus() != PaymentStatus.PENDING) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Only pending online payments can create an order");
        }

        if (payment.getRazorpayOrderId() != null) {
            return payment.getRazorpayOrderId();
        }

        if (payment.getAmount() < 100 || !"INR".equals(payment.getCurrency())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Online payment must be at least 100 paise in INR");
        }

        gateway.requireTestMode();
        String receipt = "us_" + UUID.randomUUID();

        int claimed = jdbc.update("""
                update payments
                   set razorpay_receipt = ?, updated_at = current_timestamp
                 where id = ? and payment_method = 'ONLINE' and payment_status = 'PENDING'
                   and razorpay_receipt is null and razorpay_order_id is null
                """, receipt, paymentId);

        if (claimed == 0) {
            return recoverOrder(paymentId, payment.getAmount());
        }

        String orderId = gateway.createOrder(payment.getAmount(), receipt);
        return saveOrderId(paymentId, receipt, orderId);
    }

    private String recoverOrder(long paymentId, long amountPaise) {
        OrderReference reference = loadOrderReference(paymentId);

        if (reference == null || reference.receipt() == null) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Payment is no longer ready for order creation");
        }

        if (reference.orderId() != null) {
            return reference.orderId();
        }

        String orderId = gateway.findOrderByReceipt(amountPaise, reference.receipt())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "The original order is not visible yet. No new order was created"));

        return saveOrderId(paymentId, reference.receipt(), orderId);
    }

    private String saveOrderId(long paymentId, String receipt, String orderId) {
        int saved = jdbc.update("""
                update payments
                   set razorpay_order_id = ?, updated_at = current_timestamp
                 where id = ? and razorpay_receipt = ?
                   and payment_method = 'ONLINE' and payment_status = 'PENDING'
                   and razorpay_order_id is null
                """, orderId, paymentId, receipt);

        if (saved == 1) {
            return orderId;
        }

        // A concurrent request may already have attached this exact order.
        OrderReference current = loadOrderReference(paymentId);
        if (current != null && receipt.equals(current.receipt())
                && orderId.equals(current.orderId())) {
            return orderId;
        }

        throw new ResponseStatusException(
                HttpStatus.CONFLICT, "Razorpay order needs reconciliation");
    }

    private OrderReference loadOrderReference(long paymentId) {
        return jdbc.query("""
                select razorpay_receipt, razorpay_order_id from payments
                 where id = ? and payment_method = 'ONLINE' and payment_status = 'PENDING'
                """, rs -> rs.next() ? new OrderReference(rs.getString(1), rs.getString(2)) : null,
                paymentId);
    }

    private record OrderReference(String receipt, String orderId) {}
}