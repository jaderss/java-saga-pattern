package dev.jaderss.saga.risk;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/limits")
class LimitController {

    private final RiskStore risk;
    private final RiskProperties properties;
    private final Clock clock;

    LimitController(RiskStore risk, RiskProperties properties, Clock clock) {
        this.risk = risk;
        this.properties = properties;
        this.clock = clock;
    }

    @GetMapping
    List<LimitUsage> today() {
        return risk.usage(LocalDate.now(clock), properties.dailyLimit());
    }

    @PostMapping("/reset")
    List<LimitUsage> reset() {
        risk.reset();
        return today();
    }
}
