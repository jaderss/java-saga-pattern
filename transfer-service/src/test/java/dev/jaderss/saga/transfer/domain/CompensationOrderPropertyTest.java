package dev.jaderss.saga.transfer.domain;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jaderss.saga.messaging.contract.Action;
import dev.jaderss.saga.messaging.contract.SagaCommand;
import dev.jaderss.saga.messaging.contract.Step;
import java.util.List;
import java.util.stream.IntStream;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;

class CompensationOrderPropertyTest {

    @Property
    void a_rejection_undoes_exactly_the_applied_steps_in_reverse(@ForAll @IntRange(min = 0, max = 3) int failing) {
        var driver = new SagaDriver();
        IntStream.range(0, failing).forEach(_ -> driver.done());
        driver.reject("no");
        IntStream.range(0, failing).forEach(_ -> driver.done());

        assertThat(compensated(driver)).isEqualTo(reversed(failing - 1));
        assertThat(driver.saga.status()).isEqualTo(failing == 0 ? SagaStatus.REJECTED : SagaStatus.COMPENSATED);
    }

    @Property
    void an_unknown_outcome_undoes_that_step_as_well(
            @ForAll @IntRange(min = 0, max = 3) int failing, @ForAll @IntRange(min = 1, max = 5) int maxAttempts) {
        var driver = new SagaDriver();
        IntStream.range(0, failing).forEach(_ -> driver.done());
        IntStream.range(0, maxAttempts).forEach(_ -> driver.timeout(maxAttempts));
        IntStream.rangeClosed(0, failing).forEach(_ -> driver.done());

        assertThat(compensated(driver)).isEqualTo(reversed(failing));
        assertThat(driver.saga.status()).isEqualTo(SagaStatus.COMPENSATED);
    }

    private static List<Step> compensated(SagaDriver driver) {
        return driver.sent.stream()
                .filter(command -> command.action() == Action.COMPENSATE)
                .map(SagaCommand::step)
                .toList();
    }

    private static List<Step> reversed(int from) {
        return IntStream.iterate(from, i -> i >= 0, i -> i - 1)
                .mapToObj(new SagaDriver().saga.plan()::get)
                .toList();
    }
}
