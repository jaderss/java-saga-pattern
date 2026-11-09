package dev.jaderss.saga.transfer.domain;

import java.util.UUID;

public class NotAwaitingApprovalException extends RuntimeException {

    public NotAwaitingApprovalException(UUID id) {
        super("transfer %s is not waiting for approval".formatted(id));
    }
}
