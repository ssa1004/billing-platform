package com.example.billing.application.exception

class WalletNotFoundException(ownerId: String) : RuntimeException("wallet not found for owner: $ownerId")
