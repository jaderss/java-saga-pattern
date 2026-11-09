package dev.jaderss.saga.risk;

import dev.jaderss.saga.messaging.contract.Participant;
import dev.jaderss.saga.messaging.contract.SagaCommand;
import dev.jaderss.saga.messaging.participant.StepHandler;
import dev.jaderss.saga.messaging.participant.StepResult;
import java.time.Clock;
import java.time.LocalDate;
import org.springframework.stereotype.Component;

@Component
class RiskSteps implements StepHandler {

    private final RiskStore risk;
    private final RiskProperties properties;
    private final Clock clock;

    RiskSteps(RiskStore risk, RiskProperties properties, Clock clock) {
        this.risk = risk;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public Participant participant() {
        return Participant.RISK;
    }

    @Override
    public StepResult execute(SagaCommand command) {
        var terms = command.terms();
        return switch (command.step()) {
            case RESERVE_LIMIT -> {
                var money = terms.amount();
                var reserved = risk.reserve(
                        command.sagaId(),
                        terms.sourceAccountId(),
                        LocalDate.now(clock),
                        money.amount(),
                        properties.dailyLimit());
                yield reserved
                        ? StepResult.APPLIED
                        : StepResult.rejected("daily limit of %s %s exceeded by %s"
                                .formatted(properties.dailyLimit(), money.currency(), terms.sourceAccountId()));
            }
            case CLEAR_TRANSFER -> {
                var hit = risk.watchlistReason(terms.destinationAccountId());
                if (hit.isPresent()) {
                    yield StepResult.rejected("destination %s is on the watchlist: %s"
                            .formatted(terms.destinationAccountId(), hit.get()));
                }
                risk.clear(command.sagaId());
                yield StepResult.APPLIED;
            }
            default -> throw unsupported(command);
        };
    }

    @Override
    public void compensate(SagaCommand command) {
        switch (command.step()) {
            case RESERVE_LIMIT -> risk.release(command.sagaId());
            case CLEAR_TRANSFER -> risk.unclear(command.sagaId());
            default -> throw unsupported(command);
        }
    }

    private static IllegalArgumentException unsupported(SagaCommand command) {
        return new IllegalArgumentException("risk service does not run " + command.step());
    }
}
