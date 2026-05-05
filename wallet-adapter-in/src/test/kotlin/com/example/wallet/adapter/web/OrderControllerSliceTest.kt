package com.example.wallet.adapter.web

import com.example.wallet.adapter.web.exception.GlobalExceptionHandler
import com.example.wallet.application.port.`in`.PlaceOrderUseCase
import io.micrometer.tracing.Tracer
import com.example.wallet.domain.order.Order
import com.example.wallet.domain.order.OrderItem
import com.example.wallet.domain.order.OrderStatus
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.http.MediaType
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Currency

/**
 * OrderController 슬라이스 테스트 — MockMvcBuilders.standaloneSetup() 으로
 * Spring 컨텍스트 없이 컨트롤러 + 예외 핸들러만 등록.
 *
 * <p>검증: 라우팅, JSON 직렬화, Idempotency-Key 헤더 필수 검증, Bean Validation, UseCase 호출 / 응답 매핑.</p>
 */
class OrderControllerSliceTest {

    private val placeOrder: PlaceOrderUseCase = mock()
    private lateinit var mockMvc: MockMvc

    private val mapper = ObjectMapper().registerKotlinModule().apply {
        registerModule(com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
    }
    private val krw: Currency = Currency.getInstance("KRW")
    private val fixedClock = Clock.fixed(Instant.parse("2026-05-04T00:00:00Z"), ZoneOffset.UTC)

    @BeforeEach
    fun setUp() {
        val converter = MappingJackson2HttpMessageConverter(mapper)
        mockMvc = MockMvcBuilders
            .standaloneSetup(OrderController(placeOrder))
            .setControllerAdvice(GlobalExceptionHandler(Tracer.NOOP))
            .setMessageConverters(converter)
            .setCustomArgumentResolvers(AuthenticationPrincipalArgumentResolver())
            .build()
    }

    @Test
    fun `POST orders returns 201 with Location header on success`() {
        val savedOrder = Order.place(
            "alice",
            listOf(OrderItem.of("SKU-1", 2, java.math.BigDecimal.valueOf(1000), krw)),
            fixedClock,
        )
        whenever(placeOrder.place(any())).thenReturn(savedOrder)

        val body = mapOf(
            "currency" to "KRW",
            "items" to listOf(mapOf("sku" to "SKU-1", "quantity" to 2, "unitPrice" to 1000))
        )

        mockMvc.perform(
            post("/api/v1/orders")
                .header("Idempotency-Key", "order-key-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body))
        )
            .andExpect(status().isCreated)
            .andExpect(header().exists("Location"))
            .andExpect(jsonPath("$.status").value(OrderStatus.CREATED.name))
            .andExpect(jsonPath("$.totalAmount").value(2000))
            .andExpect(jsonPath("$.currency").value("KRW"))
    }

    @Test
    fun `POST orders without Idempotency-Key returns 400`() {
        val body = mapOf(
            "currency" to "KRW",
            "items" to listOf(mapOf("sku" to "SKU-1", "quantity" to 1, "unitPrice" to 500))
        )

        mockMvc.perform(
            post("/api/v1/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body))
        )
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `POST orders with invalid empty items returns 400`() {
        val body = mapOf(
            "currency" to "KRW",
            "items" to emptyList<Any>()
        )

        mockMvc.perform(
            post("/api/v1/orders")
                .header("Idempotency-Key", "bad-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body))
        )
            .andExpect(status().isBadRequest)
    }
}
