package dev.edyiaeonian.fxledger.deposit;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import dev.edyiaeonian.fxledger.TestcontainersConfiguration;
import dev.edyiaeonian.fxledger.account.AccountService;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class DepositApiTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    AccountService accounts;

    UUID account(String currency) {
        UUID customer = accounts.createCustomer("Deposit test").id();
        return accounts.openAccount(customer, currency).id();
    }

    MvcTestResult deposit(UUID account, String key, String amount, String currency) {
        var request = mvc.post()
                .uri("/accounts/" + account + "/deposits")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\": \"" + amount + "\", \"currency\": \"" + currency + "\"}");
        if (key != null) {
            request = request.header("Idempotency-Key", key);
        }
        return request.exchange();
    }

    String balance(UUID account) {
        return accounts.findAccount(account).orElseThrow().balance().toDecimalString();
    }

    static String key() {
        return UUID.randomUUID().toString();
    }

    @Nested
    class Depositing {

        @Test
        void creditsTheAccount() {
            UUID account = account("EUR");

            MvcTestResult result = deposit(account, key(), "100.50", "EUR");

            assertThat(result).hasStatus(HttpStatus.CREATED);
            assertThat(result).bodyJson().extractingPath("$.amount").isEqualTo("100.50");
            assertThat(result).bodyJson().extractingPath("$.currency").isEqualTo("EUR");
            assertThat(balance(account)).isEqualTo("100.50");
        }

        @Test
        void depositsAddUp() {
            UUID account = account("JPY");

            deposit(account, key(), "1000", "JPY");
            deposit(account, key(), "234", "JPY");

            assertThat(balance(account)).isEqualTo("1234");
        }
    }

    @Nested
    class Idempotency {

        @Test
        void aRetryWithTheSameKeyReturnsTheOriginalAndDepositsOnce() throws Exception {
            UUID account = account("EUR");
            String key = key();

            MvcTestResult first = deposit(account, key, "10.00", "EUR");
            MvcTestResult retry = deposit(account, key, "10.00", "EUR");

            assertThat(retry).hasStatus(HttpStatus.CREATED);
            assertThat(retry).hasHeader("Idempotent-Replayed", "true");
            String firstId = JsonPath.read(first.getResponse().getContentAsString(), "$.id");
            assertThat(retry).bodyJson().extractingPath("$.id").isEqualTo(firstId);
            assertThat(balance(account)).isEqualTo("10.00");
        }

        @Test
        void reusingAKeyForADifferentRequestIsAConflict() {
            UUID account = account("EUR");
            String key = key();
            deposit(account, key, "10.00", "EUR");

            MvcTestResult different = deposit(account, key, "99.00", "EUR");

            assertThat(different).hasStatus(HttpStatus.CONFLICT);
            assertThat(different).bodyJson().extractingPath("$.code").isEqualTo("IDEMPOTENCY_KEY_REUSED");
            assertThat(balance(account)).isEqualTo("10.00");
        }

        @Test
        void aFailedRequestIsNotRememberedSoTheKeyCanBeRetried() {
            UUID account = account("EUR");
            String key = key();

            MvcTestResult failed = deposit(account, key, "10.00", "GBP");
            assertThat(failed).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);

            MvcTestResult fixed = deposit(account, key, "10.00", "EUR");
            assertThat(fixed).hasStatus(HttpStatus.CREATED);
            assertThat(balance(account)).isEqualTo("10.00");
        }

        @Test
        void aKeyBelongsToOneAccountSoAnotherAccountCanUseItToo() {
            // Two customers who happen to choose the same key are not retrying
            // each other's request.
            UUID alice = account("EUR");
            UUID bob = account("EUR");

            MvcTestResult first = deposit(alice, "deposit-1", "10.00", "EUR");
            MvcTestResult second = deposit(bob, "deposit-1", "20.00", "EUR");

            assertThat(first).hasStatus(HttpStatus.CREATED);
            assertThat(second).hasStatus(HttpStatus.CREATED);
            assertThat(second).doesNotContainHeader("Idempotent-Replayed");
            assertThat(balance(alice)).isEqualTo("10.00");
            assertThat(balance(bob)).isEqualTo("20.00");
        }

        @Test
        void theKeyIsRequired() {
            MvcTestResult result = deposit(account("EUR"), null, "10.00", "EUR");

            assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
            assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("IDEMPOTENCY_KEY_REQUIRED");
        }
    }

    @Nested
    class Refusals {

        @Test
        void anAmountThatIsNotPositiveOrNotExactIsInvalid() {
            UUID account = account("EUR");
            for (String amount : new String[] {"0", "0.00", "-5.00", "1.005", "abc", "1e3", ""}) {
                MvcTestResult result = deposit(account, key(), amount, "EUR");
                assertThat(result).as(amount).hasStatus(HttpStatus.BAD_REQUEST);
                assertThat(result).as(amount).bodyJson().extractingPath("$.code").isEqualTo("INVALID_AMOUNT");
            }
            assertThat(balance(account)).isEqualTo("0.00");
        }

        @Test
        void theCurrencyMustBeTheAccounts() {
            MvcTestResult result = deposit(account("EUR"), key(), "10.00", "USD");

            assertThat(result).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("CURRENCY_MISMATCH");
        }

        @Test
        void anUnknownAccountIsNotFound() {
            MvcTestResult result = deposit(UUID.randomUUID(), key(), "10.00", "EUR");

            assertThat(result).hasStatus(HttpStatus.NOT_FOUND);
            assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("ACCOUNT_NOT_FOUND");
        }
    }

    @Nested
    class Statement {

        @Test
        void listsPostingsNewestFirstWithTheRunningBalance() {
            UUID account = account("EUR");
            deposit(account, key(), "10.00", "EUR");
            deposit(account, key(), "5.25", "EUR");

            MvcTestResult result = mvc.get().uri("/accounts/" + account + "/statement").exchange();

            assertThat(result).hasStatus(HttpStatus.OK);
            assertThat(result).bodyJson().extractingPath("$.items[*].amount").asArray().containsExactly("5.25", "10.00");
            assertThat(result).bodyJson().extractingPath("$.items[*].balanceAfter").asArray().containsExactly("15.25", "10.00");
            assertThat(result).bodyJson().extractingPath("$.items[0].type").isEqualTo("DEPOSIT");
        }

        @Test
        void pagesWithACursor() throws Exception {
            UUID account = account("EUR");
            for (int i = 1; i <= 3; i++) {
                deposit(account, key(), i + ".00", "EUR");
            }

            MvcTestResult firstPage = mvc.get().uri("/accounts/" + account + "/statement?limit=2").exchange();
            assertThat(firstPage).bodyJson().extractingPath("$.items[*].amount").asArray().containsExactly("3.00", "2.00");
            String cursor = JsonPath.read(firstPage.getResponse().getContentAsString(), "$.nextCursor");

            MvcTestResult secondPage =
                    mvc.get().uri("/accounts/" + account + "/statement?limit=2&cursor=" + cursor).exchange();
            assertThat(secondPage).bodyJson().extractingPath("$.items[*].amount").asArray().containsExactly("1.00");
            assertThat(secondPage).bodyJson().extractingPath("$.nextCursor").isNull();
        }

        @Test
        void anUnknownAccountIsNotFound() {
            MvcTestResult result = mvc.get().uri("/accounts/" + UUID.randomUUID() + "/statement").exchange();

            assertThat(result).hasStatus(HttpStatus.NOT_FOUND);
        }
    }
}
