package dev.edyiaeonian.fxledger.transfer;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import dev.edyiaeonian.fxledger.TestcontainersConfiguration;
import dev.edyiaeonian.fxledger.account.AccountService;
import dev.edyiaeonian.fxledger.account.AccountType;
import dev.edyiaeonian.fxledger.deposit.DepositService;
import dev.edyiaeonian.fxledger.fx.FxRateService;
import dev.edyiaeonian.fxledger.money.Money;
import dev.edyiaeonian.fxledger.money.SupportedCurrencies;
import dev.edyiaeonian.fxledger.support.LedgerInvariants;
import dev.edyiaeonian.fxledger.support.MutableClock;
import dev.edyiaeonian.fxledger.support.MutableClockConfiguration;
import dev.edyiaeonian.fxledger.support.TestRates;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, MutableClockConfiguration.class})
class TransferApiTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    MutableClock clock;

    @Autowired
    FxRateService rates;

    @Autowired
    AccountService accounts;

    @Autowired
    DepositService deposits;

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void ratesForToday() {
        clock.set(MutableClockConfiguration.START);
        rates.accept(TestRates.on(LocalDate.of(2026, 9, 25)));
    }

    UUID account(String currency, String balance) {
        UUID customer = accounts.createCustomer("Transfer test").id();
        UUID account = accounts.openAccount(customer, currency).id();
        if (!balance.equals("0")) {
            deposits.deposit(UUID.randomUUID().toString(), account,
                    Money.parse(balance, SupportedCurrencies.require(currency)));
        }
        return account;
    }

    String balance(UUID account) {
        return accounts.findAccount(account).orElseThrow().balance().toDecimalString();
    }

    String quote(String source, String target, String amount) throws Exception {
        MvcTestResult result = mvc.post()
                .uri("/quotes")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sourceCurrency\": \"%s\", \"targetCurrency\": \"%s\", \"sourceAmount\": \"%s\"}"
                        .formatted(source, target, amount))
                .exchange();
        assertThat(result).hasStatus(HttpStatus.CREATED);
        return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    }

    MvcTestResult transfer(String key, String quoteId, UUID from, UUID to) {
        var request = mvc.post()
                .uri("/transfers")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"quoteId\": \"%s\", \"sourceAccountId\": \"%s\", \"targetAccountId\": \"%s\"}"
                        .formatted(quoteId, from, to));
        if (key != null) {
            request = request.header("Idempotency-Key", key);
        }
        return request.exchange();
    }

    static String key() {
        return UUID.randomUUID().toString();
    }

    @Nested
    class Transferring {

        @Test
        void reproducesTheDesignDocumentsExampleEndToEnd() throws Exception {
            UUID sender = account("EUR", "100.00");
            UUID recipient = account("GBP", "0");
            String quote = quote("EUR", "GBP", "100.00");

            MvcTestResult result = transfer(key(), quote, sender, recipient);

            assertThat(result).hasStatus(HttpStatus.CREATED);
            assertThat(result).bodyJson().extractingPath("$.sourceAmount").isEqualTo("100.00");
            assertThat(result).bodyJson().extractingPath("$.fee").isEqualTo("0.50");
            assertThat(result).bodyJson().extractingPath("$.targetAmount").isEqualTo("84.57");
            assertThat(result).bodyJson().extractingPath("$.status").isEqualTo("COMPLETED");
            assertThat(balance(sender)).isEqualTo("0.00");
            assertThat(balance(recipient)).isEqualTo("84.57");

            // The five postings of the design document's table, exactly.
            String entry = JsonPath.read(result.getResponse().getContentAsString(), "$.entryId");
            List<Map<String, Object>> postings = jdbc.sql("""
                    SELECT p.account_id, a.type, p.currency, p.amount
                    FROM postings p JOIN accounts a ON a.id = p.account_id
                    WHERE p.entry_id = :entry
                    ORDER BY p.id
                    """).param("entry", UUID.fromString(entry)).query().listOfRows();
            assertThat(postings).extracting(row -> row.get("type") + " " + row.get("currency") + " " + row.get("amount"))
                    .containsExactly(
                            "CUSTOMER EUR -10000",
                            "FEE_REVENUE EUR 50",
                            "FX_POSITION EUR 9950",
                            "FX_POSITION GBP -8457",
                            "CUSTOMER GBP 8457");
            assertThat(postings.getFirst().get("account_id")).isEqualTo(sender);
            assertThat(postings.getLast().get("account_id")).isEqualTo(recipient);
            LedgerInvariants.assertHold(jdbc);
        }

        @Test
        void aSameCurrencyTransferNeedsNoFxPosition() throws Exception {
            UUID sender = account("EUR", "100.00");
            UUID recipient = account("EUR", "0");

            MvcTestResult result = transfer(key(), quote("EUR", "EUR", "100.00"), sender, recipient);

            assertThat(result).hasStatus(HttpStatus.CREATED);
            assertThat(balance(recipient)).isEqualTo("99.50");
            String entry = JsonPath.read(result.getResponse().getContentAsString(), "$.entryId");
            long fxLines = jdbc.sql("""
                    SELECT count(*) FROM postings p JOIN accounts a ON a.id = p.account_id
                    WHERE p.entry_id = :entry AND a.type = 'FX_POSITION'
                    """).param("entry", UUID.fromString(entry)).query(Long.class).single();
            assertThat(fxLines).isZero();
        }

        @Test
        void aTransferCanBeReadBack() throws Exception {
            UUID sender = account("EUR", "10.00");
            UUID recipient = account("JPY", "0");
            MvcTestResult created = transfer(key(), quote("EUR", "JPY", "10.00"), sender, recipient);
            String id = JsonPath.read(created.getResponse().getContentAsString(), "$.id");

            MvcTestResult read = mvc.get().uri("/transfers/" + id).exchange();

            assertThat(read).hasStatus(HttpStatus.OK);
            assertThat(read).bodyJson().extractingPath("$.targetAmount").isEqualTo("1615");
            assertThat(read).bodyJson().extractingPath("$.targetCurrency").isEqualTo("JPY");
        }

        @Test
        void aQuoteBecomesUsed() throws Exception {
            UUID sender = account("EUR", "100.00");
            String quote = quote("EUR", "GBP", "10.00");
            transfer(key(), quote, sender, account("GBP", "0"));

            MvcTestResult read = mvc.get().uri("/quotes/" + quote).exchange();

            assertThat(read).bodyJson().extractingPath("$.status").isEqualTo("USED");
        }
    }

    @Nested
    class Idempotency {

        @Test
        void aRetryReturnsTheOriginalAndMovesMoneyOnce() throws Exception {
            UUID sender = account("EUR", "100.00");
            UUID recipient = account("GBP", "0");
            String quote = quote("EUR", "GBP", "40.00");
            String key = key();

            MvcTestResult first = transfer(key, quote, sender, recipient);
            MvcTestResult retry = transfer(key, quote, sender, recipient);

            assertThat(retry).hasStatus(HttpStatus.CREATED);
            assertThat(retry).hasHeader("Idempotent-Replayed", "true");
            String firstId = JsonPath.read(first.getResponse().getContentAsString(), "$.id");
            assertThat(retry).bodyJson().extractingPath("$.id").isEqualTo(firstId);
            assertThat(balance(sender)).isEqualTo("60.00");
        }

        @Test
        void reusingAKeyForADifferentTransferIsAConflict() throws Exception {
            UUID sender = account("EUR", "100.00");
            UUID recipient = account("GBP", "0");
            String key = key();
            transfer(key, quote("EUR", "GBP", "10.00"), sender, recipient);

            MvcTestResult different = transfer(key, quote("EUR", "GBP", "20.00"), sender, recipient);

            assertThat(different).hasStatus(HttpStatus.CONFLICT);
            assertThat(different).bodyJson().extractingPath("$.code").isEqualTo("IDEMPOTENCY_KEY_REUSED");
        }

        @Test
        void aFailedTransferIsNotRememberedAndLeavesTheQuoteOpen() throws Exception {
            UUID sender = account("EUR", "5.00");
            UUID recipient = account("GBP", "0");
            String quote = quote("EUR", "GBP", "10.00");
            String key = key();

            MvcTestResult failed = transfer(key, quote, sender, recipient);
            assertThat(failed).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(failed).bodyJson().extractingPath("$.code").isEqualTo("INSUFFICIENT_FUNDS");

            deposits.deposit(key(), sender, Money.parse("5.00", SupportedCurrencies.require("EUR")));
            MvcTestResult retried = transfer(key, quote, sender, recipient);

            assertThat(retried).hasStatus(HttpStatus.CREATED);
            assertThat(balance(sender)).isEqualTo("0.00");
        }

        @Test
        void theKeyIsRequired() throws Exception {
            MvcTestResult result = transfer(null, quote("EUR", "GBP", "1.00"), account("EUR", "1.00"), account("GBP", "0"));

            assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
            assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("IDEMPOTENCY_KEY_REQUIRED");
        }
    }

    @Nested
    class Quotes {

        @Test
        void aQuoteCanBeUsedOnlyOnce() throws Exception {
            UUID sender = account("EUR", "100.00");
            UUID recipient = account("GBP", "0");
            String quote = quote("EUR", "GBP", "10.00");
            transfer(key(), quote, sender, recipient);

            MvcTestResult again = transfer(key(), quote, sender, recipient);

            assertThat(again).hasStatus(HttpStatus.CONFLICT);
            assertThat(again).bodyJson().extractingPath("$.code").isEqualTo("QUOTE_ALREADY_USED");
            assertThat(balance(sender)).isEqualTo("90.00");
        }

        @Test
        void anExpiredQuoteIsRefusedAndNothingMoves() throws Exception {
            UUID sender = account("EUR", "100.00");
            String quote = quote("EUR", "GBP", "10.00");
            clock.advance(Duration.ofMinutes(10));

            MvcTestResult result = transfer(key(), quote, sender, account("GBP", "0"));

            assertThat(result).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("QUOTE_EXPIRED");
            assertThat(balance(sender)).isEqualTo("100.00");
        }

        @Test
        void theAccountsMustBeInTheQuotesCurrencies() throws Exception {
            String quote = quote("EUR", "GBP", "10.00");

            MvcTestResult wrongSource = transfer(key(), quote, account("USD", "100.00"), account("GBP", "0"));
            MvcTestResult wrongTarget = transfer(key(), quote, account("EUR", "100.00"), account("USD", "0"));

            assertThat(wrongSource).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(wrongSource).bodyJson().extractingPath("$.code").isEqualTo("CURRENCY_MISMATCH");
            assertThat(wrongTarget).bodyJson().extractingPath("$.code").isEqualTo("CURRENCY_MISMATCH");
        }

        @Test
        void anUnknownQuoteIsNotFound() {
            MvcTestResult result = transfer(key(), UUID.randomUUID().toString(), account("EUR", "1.00"), account("GBP", "0"));

            assertThat(result).hasStatus(HttpStatus.NOT_FOUND);
            assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("QUOTE_NOT_FOUND");
        }
    }

    @Nested
    class Accounts {

        @Test
        void anUnknownAccountIsNotFound() throws Exception {
            MvcTestResult result = transfer(key(), quote("EUR", "GBP", "1.00"), account("EUR", "1.00"), UUID.randomUUID());

            assertThat(result).hasStatus(HttpStatus.NOT_FOUND);
            assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("ACCOUNT_NOT_FOUND");
        }

        @Test
        void aSystemAccountCannotBeATarget() throws Exception {
            UUID fees = accounts.systemAccountId(AccountType.FEE_REVENUE, SupportedCurrencies.require("GBP"));

            MvcTestResult result = transfer(key(), quote("EUR", "GBP", "1.00"), account("EUR", "1.00"), fees);

            assertThat(result).hasStatus(HttpStatus.NOT_FOUND);
        }

        @Test
        void anAccountCannotPayItself() throws Exception {
            UUID account = account("EUR", "10.00");

            MvcTestResult result = transfer(key(), quote("EUR", "EUR", "1.00"), account, account);

            assertThat(result).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("SAME_ACCOUNT");
        }

        @Test
        void anUnknownTransferIsNotFound() {
            MvcTestResult result = mvc.get().uri("/transfers/" + UUID.randomUUID()).exchange();

            assertThat(result).hasStatus(HttpStatus.NOT_FOUND);
            assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("TRANSFER_NOT_FOUND");
        }
    }
}
