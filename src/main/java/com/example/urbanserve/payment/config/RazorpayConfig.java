
package com.example.urbanserve.payment.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.razorpay.RazorpayClient;
import com.razorpay.RazorpayException;

@Configuration
public class RazorpayConfig {

    // Rohnit: Initialize Razorpay only when both credentials are configured.
    @Bean
    @ConditionalOnExpression(
        "'${razorpay.key-id:}' != '' && '${razorpay.key-secret:}' != ''"
    )
    public RazorpayClient razorpayClient(
            @Value("${razorpay.key-id}") String keyId,
            @Value("${razorpay.key-secret}") String keySecret)
            throws RazorpayException {

        return new RazorpayClient(keyId, keySecret);
    }
}
