package dev.edyiaeonian.fxledger.account;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Currency;
import java.util.List;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.edyiaeonian.fxledger.common.error.DomainException;
import dev.edyiaeonian.fxledger.common.error.ErrorCode;
import dev.edyiaeonian.fxledger.money.Money;
import dev.edyiaeonian.fxledger.money.SupportedCurrencies;

@Service
public class AccountService {

    private final CustomerRepository customers;
    private final AccountRepository accounts;
    private final Clock clock;

    AccountService(CustomerRepository customers, AccountRepository accounts, Clock clock) {
        this.customers = customers;
        this.accounts = accounts;
        this.clock = clock;
    }

    @Transactional
    public Customer createCustomer(String name) {
        Customer customer = new Customer(UUID.randomUUID(), name.strip(), now());
        customers.insert(customer);
        return customer;
    }

    @Transactional
    public Account openAccount(UUID customerId, String currencyCode) {
        Currency currency = SupportedCurrencies.require(currencyCode);
        requireCustomer(customerId);
        Account account = new Account(
                UUID.randomUUID(), customerId, AccountType.CUSTOMER, Money.ofMinor(0, currency), now());
        try {
            accounts.insert(account);
        } catch (DuplicateKeyException exists) {
            // The unique index decides, not a lookup beforehand: two requests
            // opening the same account at once would both pass a lookup.
            throw new DomainException(
                    ErrorCode.ACCOUNT_ALREADY_EXISTS,
                    "customer " + customerId + " already has a " + currencyCode + " account");
        }
        return account;
    }

    @Transactional(readOnly = true)
    public List<Account> accountsOf(UUID customerId) {
        requireCustomer(customerId);
        return accounts.findByCustomer(customerId);
    }

    private void requireCustomer(UUID customerId) {
        if (customers.findById(customerId).isEmpty()) {
            throw new DomainException(ErrorCode.CUSTOMER_NOT_FOUND, "no customer " + customerId);
        }
    }

    // PostgreSQL stores microseconds; truncating here means what is returned
    // now is exactly what a later read returns.
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
