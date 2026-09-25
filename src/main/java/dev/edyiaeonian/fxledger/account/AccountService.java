package dev.edyiaeonian.fxledger.account;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
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

    @Transactional(readOnly = true)
    public Optional<Account> findAccount(UUID accountId) {
        return accounts.findById(accountId);
    }

    /** The system account of this type for this currency; every supported currency has one. */
    @Transactional(readOnly = true)
    public UUID systemAccountId(AccountType type, Currency currency) {
        if (type == AccountType.CUSTOMER) {
            throw new IllegalArgumentException("not a system account type: " + type);
        }
        return accounts.systemAccountId(type, currency);
    }

    /**
     * Locks these accounts until the caller's transaction ends, always in
     * ascending id order.
     *
     * <p>A fixed order is what prevents deadlock: if one transfer locked A
     * then B while another locked B then A, each would wait for the other
     * forever. With every caller taking locks in the same order, the second
     * simply waits for the first to finish.
     *
     * @throws DomainException ACCOUNT_NOT_FOUND if any of them does not exist
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<UUID, Account> lockInIdOrder(Collection<UUID> accountIds) {
        Map<UUID, Account> locked = new LinkedHashMap<>();
        for (UUID id : new TreeSet<>(accountIds)) {
            Account account = accounts.lockById(id)
                    .orElseThrow(() -> new DomainException(ErrorCode.ACCOUNT_NOT_FOUND, "no account " + id));
            locked.put(id, account);
        }
        return locked;
    }

    /** Sets a balance; only for the ledger, on an account it has locked. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void updateBalance(UUID accountId, Money balance) {
        accounts.updateBalance(accountId, balance.minorUnits());
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
