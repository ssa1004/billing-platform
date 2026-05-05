package com.example.wallet.adapter.web

import com.example.wallet.adapter.web.auth.Caller
import com.example.wallet.adapter.web.dto.TransactionResponse
import com.example.wallet.adapter.web.dto.WalletResponse
import com.example.wallet.application.port.`in`.WalletQueryUseCase
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/wallet")
@Tag(name = "wallet", description = "지갑 잔액 + 거래 내역")
class WalletController(
    private val walletQuery: WalletQueryUseCase,
) {
    @GetMapping
    @Operation(summary = "내 지갑 (잔액, 사용 가능 등)")
    fun me(@AuthenticationPrincipal jwt: Jwt?): WalletResponse =
        WalletResponse.from(walletQuery.getByOwner(Caller.from(jwt).owner))

    @GetMapping("/transactions")
    @Operation(summary = "최근 거래 내역")
    fun transactions(
        @AuthenticationPrincipal jwt: Jwt?,
        @RequestParam(defaultValue = "20") limit: Int,
    ): List<TransactionResponse> =
        walletQuery.recentTransactions(Caller.from(jwt).owner, limit).map(TransactionResponse::from)
}
