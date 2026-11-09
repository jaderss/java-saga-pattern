package dev.jaderss.saga.account;

import java.math.BigDecimal;

record Account(String id, String ownerName, String currency, BigDecimal balance, AccountStatus status) {}
