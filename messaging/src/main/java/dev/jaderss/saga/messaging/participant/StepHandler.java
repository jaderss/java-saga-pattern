package dev.jaderss.saga.messaging.participant;

import dev.jaderss.saga.messaging.contract.Participant;
import dev.jaderss.saga.messaging.contract.SagaCommand;

public interface StepHandler {

    Participant participant();

    StepResult execute(SagaCommand command);

    void compensate(SagaCommand command);
}
