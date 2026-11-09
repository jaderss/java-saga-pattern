package dev.jaderss.saga.transfer.api;

import dev.jaderss.saga.messaging.contract.Fault;
import dev.jaderss.saga.messaging.contract.Money;
import dev.jaderss.saga.messaging.contract.TransferTerms;
import dev.jaderss.saga.transfer.domain.TransferOrder;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.List;
import java.util.UUID;

public record TransferRequest(
        @NotBlank String sourceAccountId,
        @NotBlank String destinationAccountId,

        @NotNull @Positive @Digits(integer = 17, fraction = 2)
        BigDecimal amount,

        @NotNull @Pattern(regexp = "[A-Z]{3}") String currency,
        List<Fault> faults,
        Boolean requiresApproval) {

    public TransferRequest {
        faults = faults == null ? List.of() : List.copyOf(faults);
        requiresApproval = Boolean.TRUE.equals(requiresApproval);
    }

    TransferOrder toOrder(UUID id) {
        var money = new Money(amount, Currency.getInstance(currency));
        return new TransferOrder(
                id, new TransferTerms(sourceAccountId, destinationAccountId, money), faults, requiresApproval);
    }
}
