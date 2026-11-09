package dev.jaderss.saga.account;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
class SeedOnStartup implements ApplicationRunner {

    private final AccountStore accounts;

    SeedOnStartup(AccountStore accounts) {
        this.accounts = accounts;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (accounts.empty()) {
            accounts.reset();
        }
    }
}
