package dev.jaderss.saga.messaging.participant;

import dev.jaderss.saga.messaging.contract.Participant;
import dev.jaderss.saga.messaging.contract.SagaCommand;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;

class FakeHandler implements StepHandler {

    static final Set<String> REJECTED_ACCOUNTS = Set.of("REJECT-ME");

    private final JdbcClient jdbc;

    FakeHandler(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Participant participant() {
        return Participant.ACCOUNT;
    }

    @Override
    public StepResult execute(SagaCommand command) {
        if (REJECTED_ACCOUNTS.contains(command.terms().sourceAccountId())) {
            return StepResult.rejected("rejected by the fake");
        }
        effect(command, "EXECUTE");
        return StepResult.APPLIED;
    }

    @Override
    public void compensate(SagaCommand command) {
        effect(command, "COMPENSATE");
    }

    private void effect(SagaCommand command, String kind) {
        jdbc.sql("INSERT INTO fake_effect (saga_id, step, kind) VALUES (?, ?, ?)")
                .params(command.sagaId(), command.step().name(), kind)
                .update();
    }
}
