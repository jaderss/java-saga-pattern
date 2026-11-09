package dev.jaderss.saga.risk;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
class SeedOnStartup implements ApplicationRunner {

    private final RiskStore risk;

    SeedOnStartup(RiskStore risk) {
        this.risk = risk;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (risk.empty()) {
            risk.reset();
        }
    }
}
