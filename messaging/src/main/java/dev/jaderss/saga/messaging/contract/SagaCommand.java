package dev.jaderss.saga.messaging.contract;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record SagaCommand(UUID sagaId, Step step, Action action, int attempt, TransferTerms terms, List<Fault> faults) {

    public SagaCommand {
        Objects.requireNonNull(sagaId, "sagaId");
        Objects.requireNonNull(step, "step");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(terms, "terms");
        faults = faults == null ? List.of() : List.copyOf(faults);
    }

    public boolean injects(FaultKind kind) {
        return faults.stream().anyMatch(fault -> fault.kind() == kind && fault.hits(step, action, attempt));
    }

    public SagaReply reply(Outcome outcome, String reason, boolean duplicate) {
        return new SagaReply(sagaId, step, action, attempt, outcome, reason, duplicate);
    }
}
