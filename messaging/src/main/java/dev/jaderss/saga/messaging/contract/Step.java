package dev.jaderss.saga.messaging.contract;

import java.util.Optional;

public enum Step {
    RESERVE_LIMIT(Participant.RISK),
    DEBIT_SOURCE(Participant.ACCOUNT),
    APPROVE_TRANSFER(null),
    CREDIT_DESTINATION(Participant.ACCOUNT),
    CLEAR_TRANSFER(Participant.RISK);

    private final Participant participant;

    Step(Participant participant) {
        this.participant = participant;
    }

    public Optional<Participant> participant() {
        return Optional.ofNullable(participant);
    }

    public boolean manual() {
        return participant == null;
    }
}
