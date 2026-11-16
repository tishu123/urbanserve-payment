package com.example.urbanserve.payment.config;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Configuration(proxyBeanMethods = false)
public class PaymentAccessConfig {

    @Bean
    FilterRegistrationBean<Filter> paymentAccessFilter(
            @Value("${payment.internal-token:}") String token) {

        boolean configured = !token.isBlank() && token.length() >= 32;
        byte[] expected = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);

        Filter filter = (request, response, chain) -> {
            HttpServletRequest httpRequest = (HttpServletRequest) request;
            HttpServletResponse httpResponse = (HttpServletResponse) response;

            if (!configured) {
                httpResponse.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                return;
            }

            String authorization = httpRequest.getHeader("Authorization");

            if (authorization == null || !MessageDigest.isEqual(
                    expected, authorization.getBytes(StandardCharsets.UTF_8))) {
                httpResponse.setHeader("WWW-Authenticate", "Bearer realm=\"urbanserve-payment\"");
                httpResponse.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                return;
            }

            chain.doFilter(request, response);
        };

        FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns("/api/payments", "/api/payments/*");
        return registration;
    }
}