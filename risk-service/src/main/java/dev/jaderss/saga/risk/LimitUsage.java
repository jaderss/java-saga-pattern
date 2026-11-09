package dev.jaderss.saga.risk;

import java.math.BigDecimal;
import java.time.LocalDate;

record LimitUsage(
        String accountId,
        LocalDate day,
        BigDecimal dailyLimit,
        BigDecimal reserved,
        BigDecimal used,
        String watchlistReason) {}
