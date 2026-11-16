
package com.example.urbanserve.payment.controller;

import com.example.urbanserve.payment.dto.CreatePaymentRequest;
import com.example.urbanserve.payment.dto.PaymentResponse;
import com.example.urbanserve.payment.entity.PaymentMethod;
import com.example.urbanserve.payment.entity.PaymentStatus;
import com.example.urbanserve.payment.service.PaymentService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class PaymentControllerTest {

    @Mock
    private PaymentService paymentService;

    @InjectMocks
    private PaymentController paymentController;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {

        mockMvc = MockMvcBuilders
                .standaloneSetup(paymentController)
                .build();
    }

    // Rohnit: Test successful payment creation.
    @Test
    void shouldCreatePaymentSuccessfully() throws Exception {

        String request = """
                {
                    "bookingId": 105,
                    "amount": 149900,
                    "paymentMethod": "ONLINE"
                }
                """;

        PaymentResponse response = new PaymentResponse();
        response.setPaymentId(1L);
        response.setBookingId(105L);
        response.setAmount(149900L);
        response.setCurrency("INR");
        response.setPaymentMethod(PaymentMethod.ONLINE);
        response.setPaymentStatus(PaymentStatus.PENDING);

        when(paymentService.createPayment(any(CreatePaymentRequest.class)))
                .thenReturn(response);

        mockMvc.perform(post("/api/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .content(request))

                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.paymentId").value(1))
                .andExpect(jsonPath("$.bookingId").value(105))
                .andExpect(jsonPath("$.amount").value(149900))
                .andExpect(jsonPath("$.currency").value("INR"))
                .andExpect(jsonPath("$.paymentMethod").value("ONLINE"))
                .andExpect(jsonPath("$.paymentStatus").value("PENDING"));

        verify(paymentService, times(1))
                .createPayment(any(CreatePaymentRequest.class));
    }

    // Rohnit: Test rejection of an invalid payment method.
    @Test
    void shouldRejectInvalidPaymentMethod() throws Exception {

        String invalidRequest = """
                {
                    "bookingId": 105,
                    "amount": 149900,
                    "paymentMethod": "INVALID_METHOD"
                }
                """;

        mockMvc.perform(post("/api/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .content(invalidRequest))

                .andExpect(status().isBadRequest());

        verify(paymentService, never())
                .createPayment(any(CreatePaymentRequest.class));
    }
}
