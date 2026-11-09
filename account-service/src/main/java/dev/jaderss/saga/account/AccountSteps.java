package dev.jaderss.saga.account;

import dev.jaderss.saga.messaging.contract.Money;
import dev.jaderss.saga.messaging.contract.Participant;
import dev.jaderss.saga.messaging.contract.SagaCommand;
import dev.jaderss.saga.messaging.participant.StepHandler;
import dev.jaderss.saga.messaging.participant.StepResult;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;

@Component
class AccountSteps implements StepHandler {

    private final AccountStore accounts;

    AccountSteps(AccountStore accounts) {
        this.accounts = accounts;
    }

    @Override
    public Participant participant() {
        return Participant.ACCOUNT;
    }

    @Override
    public StepResult execute(SagaCommand command) {
        var money = command.terms().amount();
        return switch (command.step()) {
            case DEBIT_SOURCE -> {
                var id = command.terms().sourceAccountId();
                yield accounts.debit(id, money)
                        ? applied(command, id, EntryKind.DEBIT, money.amount().negate())
                        : StepResult.rejected(whyNot(id, money));
            }
            case CREDIT_DESTINATION -> {
                var id = command.terms().destinationAccountId();
                yield accounts.credit(id, money)
                        ? applied(command, id, EntryKind.CREDIT, money.amount())
                        : StepResult.rejected(whyNot(id, money));
            }
            default -> throw unsupported(command);
        };
    }

    @Override
    public void compensate(SagaCommand command) {
        var amount = command.terms().amount().amount();
        switch (command.step()) {
            case DEBIT_SOURCE -> correct(command, command.terms().sourceAccountId(), EntryKind.REFUND, amount);
            case CREDIT_DESTINATION ->
                correct(command, command.terms().destinationAccountId(), EntryKind.REVERSAL, amount.negate());
            default -> throw unsupported(command);
        }
    }

    private StepResult applied(SagaCommand command, String accountId, EntryKind kind, BigDecimal signed) {
        accounts.entry(accountId, command.sagaId(), kind, signed);
        return StepResult.APPLIED;
    }

    private void correct(SagaCommand command, String accountId, EntryKind kind, BigDecimal signed) {
        accounts.correct(accountId, signed);
        accounts.entry(accountId, command.sagaId(), kind, signed);
    }

    private String whyNot(String accountId, Money money) {
        return accounts.find(accountId)
                .map(account -> {
                    if (account.status() != AccountStatus.ACTIVE) {
                        return "account %s is %s"
                                .formatted(accountId, account.status().name().toLowerCase());
                    }
                    if (!account.currency().equals(money.currency().getCurrencyCode())) {
                        return "account %s holds %s, not %s".formatted(accountId, account.currency(), money.currency());
                    }
                    return "insufficient funds in %s for %s".formatted(accountId, money);
                })
                .orElse("account %s does not exist".formatted(accountId));
    }

    private static IllegalArgumentException unsupported(SagaCommand command) {
        return new IllegalArgumentException("account service does not run " + command.step());
    }
}
