package net.wastu.artha.data.providers

enum class AccountStatus {
    UNKNOWN,
    PARTIAL,
    CONNECTED,
    NEEDS_LOGIN,
    APP_NOT_INSTALLED,
    RATE_LIMITED,
    ERROR,
}
