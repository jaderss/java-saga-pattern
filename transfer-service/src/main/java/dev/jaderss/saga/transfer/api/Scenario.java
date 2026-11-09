package dev.jaderss.saga.transfer.api;

import dev.jaderss.saga.transfer.domain.SagaStatus;

public record Scenario(String id, String title, String proves, TransferRequest request, SagaStatus expected) {}
