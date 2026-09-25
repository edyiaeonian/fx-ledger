package dev.edyiaeonian.fxledger.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.edyiaeonian.fxledger.TestcontainersConfiguration;
import dev.edyiaeonian.fxledger.account.AccountService;
import dev.edyiaeonian.fxledger.account.AccountType;
import dev.edyiaeonian.fxledger.common.error.DomainException;
import dev.edyiaeonian.fxledger.common.error.ErrorCode;
import dev.edyiaeonian.fxledger.ledger.LedgerService.PostingRequest;
import dev.edyiaeonian.fxledger.money.Money;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class LedgerServiceTest {

    static final Currency EUR = Currency.getInstance("EUR");
    static final Currency GBP = Currency.getInstance("GBP");

    @Autowired
    LedgerService ledger;

    @Autowired
    AccountService accounts;

    @Autowired
    TransactionTemplate transaction;

    @Autowired
    JdbcClient jdbc;

    UUID customerAccount(String currency) {
        UUID customer = accounts.createCustomer("Ledger test").id();
        return accounts.openAccount(customer, currency).id();
    }

    UUID system(AccountType type, Currency currency) {
        return accounts.systemAccountId(type, currency);
    }

    long balance(UUID account) {
        return accounts.findAccount(account).orElseThrow().balance().minorUnits();
    }

    UUID post(PostingRequest... postings) {
        return transaction.execute(status -> ledger.post(UUID.randomUUID(), EntryType.DEPOSIT, List.of(postings)));
    }

    long postingCount(UUID account) {
        return jdbc.sql("SELECT count(*) FROM postings WHERE account_id = :id")
                .param("id", account)
                .query(Long.class)
                .single();
    }

    static PostingRequest line(UUID account, long minor, Currency currency) {
        return new PostingRequest(account, Money.ofMinor(minor, currency));
    }

    @Test
    void aBalancedEntryMovesMoneyAndRecordsTheRunningBalance() {
        UUID customer = customerAccount("EUR");
        UUID funding = system(AccountType.FUNDING, EUR);
        long fundingBefore = balance(funding);

        post(line(funding, -10000, EUR), line(customer, 10000, EUR));

        assertThat(balance(customer)).isEqualTo(10000);
        assertThat(balance(funding)).isEqualTo(fundingBefore - 10000);
        long balanceAfter = jdbc.sql("SELECT balance_after FROM postings WHERE account_id = :id")
                .param("id", customer)
                .query(Long.class)
                .single();
        assertThat(balanceAfter).isEqualTo(10000);
    }

    @Test
    void anUnbalancedEntryIsRefusedAndNothingIsWritten() {
        UUID customer = customerAccount("EUR");
        UUID funding = system(AccountType.FUNDING, EUR);

        assertThatThrownBy(() -> post(line(funding, -10000, EUR), line(customer, 9999, EUR)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("EUR");

        assertThat(balance(customer)).isZero();
        assertThat(postingCount(customer)).isZero();
    }

    @Test
    void eachCurrencyMustBalanceOnItsOwn() {
        // -100 EUR and +100 GBP sum to zero as numbers, but money was created
        // in one currency and destroyed in another.
        UUID eur = customerAccount("EUR");
        UUID gbp = customerAccount("GBP");
        post(line(system(AccountType.FUNDING, EUR), -100, EUR), line(eur, 100, EUR));

        assertThatThrownBy(() -> post(line(eur, -100, EUR), line(gbp, 100, GBP)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aCrossCurrencyEntryBalancesThroughTheFxPosition() {
        // The design document's example: 100 EUR sent, 0.50 fee, 84.57 GBP received.
        UUID sender = customerAccount("EUR");
        UUID recipient = customerAccount("GBP");
        post(line(system(AccountType.FUNDING, EUR), -10000, EUR), line(sender, 10000, EUR));

        post(
                line(sender, -10000, EUR),
                line(system(AccountType.FEE_REVENUE, EUR), 50, EUR),
                line(system(AccountType.FX_POSITION, EUR), 9950, EUR),
                line(system(AccountType.FX_POSITION, GBP), -8457, GBP),
                line(recipient, 8457, GBP));

        assertThat(balance(sender)).isZero();
        assertThat(balance(recipient)).isEqualTo(8457);
    }

    @Test
    void aCustomerAccountCannotBeOverdrawn() {
        UUID customer = customerAccount("EUR");
        UUID other = customerAccount("EUR");
        post(line(system(AccountType.FUNDING, EUR), -500, EUR), line(customer, 500, EUR));

        assertThatThrownBy(() -> post(line(customer, -501, EUR), line(other, 501, EUR)))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).code())
                .isEqualTo(ErrorCode.INSUFFICIENT_FUNDS);

        assertThat(balance(customer)).isEqualTo(500);
        assertThat(balance(other)).isZero();
    }

    @Test
    void aSystemAccountMayGoNegative() {
        UUID funding = system(AccountType.FUNDING, GBP);
        UUID customer = customerAccount("GBP");

        post(line(funding, -1_000_000, GBP), line(customer, 1_000_000, GBP));

        assertThat(balance(funding)).isNegative();
    }

    @Test
    void aPostingInTheWrongCurrencyForItsAccountIsRefused() {
        UUID eurAccount = customerAccount("EUR");
        UUID funding = system(AccountType.FUNDING, GBP);

        assertThatThrownBy(() -> post(line(funding, -100, GBP), line(eurAccount, 100, GBP)))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).code())
                .isEqualTo(ErrorCode.CURRENCY_MISMATCH);
    }

    @Test
    void anUnknownAccountIsRefused() {
        UUID funding = system(AccountType.FUNDING, EUR);

        assertThatThrownBy(() -> post(line(funding, -100, EUR), line(UUID.randomUUID(), 100, EUR)))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).code())
                .isEqualTo(ErrorCode.ACCOUNT_NOT_FOUND);
    }

    @Test
    void zeroAmountsAndEmptyEntriesAreRefused() {
        UUID customer = customerAccount("EUR");

        assertThatThrownBy(() -> post(line(customer, 0, EUR), line(customer, 0, EUR)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> post())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void postingOutsideATransactionIsAProgrammingError() {
        // The caller owns the transaction, so that its own writes (an
        // idempotency record, say) commit or roll back with the entry.
        UUID customer = customerAccount("EUR");
        UUID funding = system(AccountType.FUNDING, EUR);

        assertThatThrownBy(() -> ledger.post(
                        UUID.randomUUID(),
                        EntryType.DEPOSIT,
                        List.of(line(funding, -1, EUR), line(customer, 1, EUR))))
                .isInstanceOf(IllegalTransactionStateException.class);
    }
}
