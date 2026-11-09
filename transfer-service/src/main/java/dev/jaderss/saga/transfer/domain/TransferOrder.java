package dev.jaderss.saga.transfer.domain;

import dev.jaderss.saga.messaging.contract.Fault;
import dev.jaderss.saga.messaging.contract.TransferTerms;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record TransferOrder(UUID id, TransferTerms terms, List<Fault> faults, boolean requiresApproval) {

    public TransferOrder {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(terms, "terms");
        faults = faults == null ? List.of() : List.copyOf(faults);
    }

    public TransferOrder(UUID id, TransferTerms terms, List<Fault> faults) {
        this(id, terms, faults, false);
    }

    public boolean sameRequestAs(TransferOrder other) {
        return terms.equals(other.terms) && faults.equals(other.faults) && requiresApproval == other.requiresApproval;
    }
}
