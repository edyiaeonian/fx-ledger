package dev.edyiaeonian.fxledger.fx;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import dev.edyiaeonian.fxledger.TestcontainersConfiguration;
import dev.edyiaeonian.fxledger.support.MutableClock;
import dev.edyiaeonian.fxledger.support.MutableClockConfiguration;
import dev.edyiaeonian.fxledger.support.TestRates;
import java.time.Duration;
import java.time.LocalDate;
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
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, MutableClockConfiguration.class})
class QuoteApiTest {

    static final LocalDate TODAY = LocalDate.of(2026, 9, 25);

    @Autowired
    MockMvcTester mvc;

    @Autowired
    MutableClock clock;

    @Autowired
    FxRateService rates;

    @BeforeEach
    void ratesForToday() {
        clock.set(MutableClockConfiguration.START);
        rates.accept(TestRates.on(TODAY));
    }

    MvcTestResult quote(String source, String target, String amount) {
        return mvc.post()
                .uri("/quotes")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sourceCurrency\": \"%s\", \"targetCurrency\": \"%s\", \"sourceAmount\": \"%s\"}"
                        .formatted(source, target, amount))
                .exchange();
    }

    @Nested
    class Quoting {

        @Test
        void reproducesTheDesignDocumentsExample() {
            MvcTestResult result = quote("EUR", "GBP", "100.00");

            assertThat(result).hasStatus(HttpStatus.CREATED);
            assertThat(result).bodyJson().extractingPath("$.sourceAmount").isEqualTo("100.00");
            assertThat(result).bodyJson().extractingPath("$.fee").isEqualTo("0.50");
            assertThat(result).bodyJson().extractingPath("$.rate").isEqualTo("0.8500000000");
            assertThat(result).bodyJson().extractingPath("$.targetAmount").isEqualTo("84.57");
            assertThat(result).bodyJson().extractingPath("$.targetCurrency").isEqualTo("GBP");
            assertThat(result).bodyJson().extractingPath("$.rateDate").isEqualTo("2026-09-25");
            assertThat(result).bodyJson().extractingPath("$.status").isEqualTo("OPEN");
        }

        @Test
        void expiresAfterTheConfiguredTenMinutes() {
            MvcTestResult result = quote("EUR", "GBP", "100.00");

            assertThat(result).bodyJson().extractingPath("$.expiresAt").isEqualTo("2026-09-25T12:10:00Z");
        }

        @Test
        void aCrossRateGoesThroughTheEuro() {
            // GBP -> USD = 1.1367 / 0.85 = 1.3372941176; 99.50 GBP x that = 133.06 USD
            MvcTestResult result = quote("GBP", "USD", "100.00");

            assertThat(result).bodyJson().extractingPath("$.rate").isEqualTo("1.3372941176");
            assertThat(result).bodyJson().extractingPath("$.targetAmount").isEqualTo("133.06");
        }

        @Test
        void aQuoteCanBeReadBack() throws Exception {
            MvcTestResult created = quote("EUR", "JPY", "10.00");
            String id = JsonPath.read(created.getResponse().getContentAsString(), "$.id");

            MvcTestResult read = mvc.get().uri("/quotes/" + id).exchange();

            assertThat(read).hasStatus(HttpStatus.OK);
            assertThat(read).bodyJson().extractingPath("$.targetAmount").isEqualTo("1615");
            assertThat(read).bodyJson().extractingPath("$.expired").isEqualTo(false);
        }
    }

    @Nested
    class Time {

        @Test
        void aQuoteIsExpiredOnceItsTimeHasPassed() throws Exception {
            MvcTestResult created = quote("EUR", "GBP", "100.00");
            String id = JsonPath.read(created.getResponse().getContentAsString(), "$.id");

            clock.advance(Duration.ofMinutes(10));

            MvcTestResult read = mvc.get().uri("/quotes/" + id).exchange();
            assertThat(read).bodyJson().extractingPath("$.expired").isEqualTo(true);
        }

        @Test
        void ratesOlderThanFiveDaysStopNewQuotes() {
            clock.advance(Duration.ofDays(6));

            MvcTestResult result = quote("EUR", "GBP", "100.00");

            assertThat(result).hasStatus(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("RATES_UNAVAILABLE");
        }
    }

    @Nested
    class Refusals {

        @Test
        void anUnsupportedCurrencyIsABadRequest() {
            MvcTestResult result = quote("EUR", "BTC", "100.00");

            assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
            assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("UNSUPPORTED_CURRENCY");
        }

        @Test
        void anAmountThatIsNotPositiveOrNotExactIsInvalid() {
            for (String amount : new String[] {"0", "-1.00", "1.005", "abc"}) {
                MvcTestResult result = quote("EUR", "GBP", amount);
                assertThat(result).as(amount).hasStatus(HttpStatus.BAD_REQUEST);
                assertThat(result).as(amount).bodyJson().extractingPath("$.code").isEqualTo("INVALID_AMOUNT");
            }
        }

        @Test
        void anAmountTheFeeWouldSwallowIsTooSmall() {
            MvcTestResult result = quote("EUR", "GBP", "0.01");

            assertThat(result).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("AMOUNT_TOO_SMALL");
        }

        @Test
        void anUnknownQuoteIsNotFound() {
            MvcTestResult result = mvc.get().uri("/quotes/" + UUID.randomUUID()).exchange();

            assertThat(result).hasStatus(HttpStatus.NOT_FOUND);
            assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("QUOTE_NOT_FOUND");
        }
    }
}
