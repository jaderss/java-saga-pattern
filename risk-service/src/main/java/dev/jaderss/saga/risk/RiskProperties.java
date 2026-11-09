package dev.jaderss.saga.risk;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("risk")
record RiskProperties(@NotNull @Positive BigDecimal dailyLimit) {}
