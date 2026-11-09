package dev.jaderss.saga.account;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/accounts")
class AccountController {

    private final AccountStore accounts;

    AccountController(AccountStore accounts) {
        this.accounts = accounts;
    }

    @GetMapping
    List<Account> all() {
        return accounts.all();
    }

    @PostMapping("/reset")
    List<Account> reset() {
        accounts.reset();
        return accounts.all();
    }
}
