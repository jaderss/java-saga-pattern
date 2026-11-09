package dev.jaderss.saga.transfer.domain;

import dev.jaderss.saga.messaging.contract.SagaCommand;
import java.util.List;

public record Transition(TransferSaga saga, List<SagaCommand> commands, List<SagaEvent> events, boolean changesState) {

    public Transition {
        commands = List.copyOf(commands);
        events = List.copyOf(events);
    }
}
