package dev.jaderss.saga.transfer.domain;

import dev.jaderss.saga.messaging.contract.Money;
import dev.jaderss.saga.messaging.contract.Outcome;
import dev.jaderss.saga.messaging.contract.SagaCommand;
import dev.jaderss.saga.messaging.contract.TransferTerms;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class SagaDriver {

    final List<SagaCommand> sent = new ArrayList<>();
    final List<SagaEvent> events = new ArrayList<>();
    TransferSaga saga;

    SagaDriver() {
        this(false);
    }

    SagaDriver(boolean requiresApproval) {
        var terms = new TransferTerms("ACC-1", "ACC-2", Money.of("10.00", "USD"));
        record(TransferSaga.start(new TransferOrder(UUID.randomUUID(), terms, List.of(), requiresApproval)));
    }

    SagaDriver decide(boolean approved) {
        return record(saga.onDecision(approved));
    }

    SagaDriver done() {
        return reply(Outcome.DONE, null);
    }

    SagaDriver reject(String reason) {
        return reply(Outcome.REJECTED, reason);
    }

    SagaDriver timeout(int maxAttempts) {
        return record(saga.onTimeout(maxAttempts));
    }

    SagaDriver reply(Outcome outcome, String reason) {
        var command = sent.getLast();
        return record(saga.onReply(command.reply(outcome, reason, false)));
    }

    SagaDriver record(Transition transition) {
        saga = transition.saga();
        sent.addAll(transition.commands());
        events.addAll(transition.events());
        return this;
    }

    String trail() {
        return String.join(
                " ",
                sent.stream()
                        .map(command ->
                                command.action().name().charAt(0) + ":" + command.step() + "#" + command.attempt())
                        .toList());
    }
}
