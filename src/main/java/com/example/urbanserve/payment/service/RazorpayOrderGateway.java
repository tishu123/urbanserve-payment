package com.example.urbanserve.payment.service;

import com.razorpay.Order;
import com.razorpay.RazorpayClient;
import com.razorpay.RazorpayException;
import org.json.JSONException;
import org.json.JSONObject;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

@Component
public class RazorpayOrderGateway {

    private final ObjectProvider<RazorpayClient> clients;
    private final String keyId;

    public RazorpayOrderGateway(
            ObjectProvider<RazorpayClient> clients,
            @Value("${razorpay.key-id:}") String keyId) {
        this.clients = clients;
        this.keyId = keyId;
    }

    public void requireTestMode() {
        testClient();
    }

    public String createOrder(long amountPaise, String receipt) {
        RazorpayClient client = testClient();

        JSONObject request = new JSONObject()
                .put("amount", amountPaise)
                .put("currency", "INR")
                .put("receipt", receipt)
                .put("partial_payment", false);

        try {
            return verifiedOrderId(client.orders.create(request), amountPaise, receipt);
        } catch (RazorpayException | JSONException | ClassCastException e) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY, "Razorpay order could not be confirmed");
        }
    }

    public Optional<String> findOrderByReceipt(long amountPaise, String receipt) {
        RazorpayClient client = testClient();
        JSONObject query = new JSONObject().put("receipt", receipt).put("count", 2);

        try {
            Order match = null;

            for (Order candidate : client.orders.fetchAll(query)) {
                Object returnedReceipt = candidate.get("receipt");
                if (!receipt.equals(returnedReceipt)) {
                    continue;
                }

                if (match != null) {
                    throw new ResponseStatusException(
                            HttpStatus.BAD_GATEWAY, "Razorpay returned multiple matching orders");
                }

                match = candidate;
            }

            return match == null
                    ? Optional.empty()
                    : Optional.of(verifiedOrderId(match, amountPaise, receipt));
        } catch (RazorpayException | JSONException | ClassCastException e) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY, "Razorpay order lookup could not be confirmed");
        }
    }

    private static String verifiedOrderId(Order order, long amountPaise, String receipt) {
        String orderId = order.get("id");
        String returnedReceipt = order.get("receipt");
        String currency = order.get("currency");
        Number amount = order.get("amount");

        if (orderId == null || !orderId.startsWith("order_") || orderId.length() <= 6
                || !receipt.equals(returnedReceipt) || !"INR".equals(currency)
                || amount == null || amount.longValue() != amountPaise) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY, "Razorpay returned unexpected order details");
        }

        return orderId;
    }

    private RazorpayClient testClient() {
        RazorpayClient client = keyId.startsWith("rzp_test_") ? clients.getIfAvailable() : null;

        if (client == null) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "Razorpay Test Mode is not configured");
        }

        return client;
    }
}