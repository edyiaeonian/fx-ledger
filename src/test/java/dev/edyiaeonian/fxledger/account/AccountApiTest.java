package dev.edyiaeonian.fxledger.account;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import dev.edyiaeonian.fxledger.TestcontainersConfiguration;
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

// The whole application against a real PostgreSQL (Testcontainers). Each test
// creates its own customer, so tests never depend on each other's data.
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AccountApiTest {

    @Autowired
    MockMvcTester mvc;

    MvcTestResult post(String uri, String json) {
        return mvc.post().uri(uri).contentType(MediaType.APPLICATION_JSON).content(json).exchange();
    }

    String createCustomer(String name) throws Exception {
        MvcTestResult result = post("/customers", "{\"name\": \"" + name + "\"}");
        assertThat(result).hasStatus(HttpStatus.CREATED);
        return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    }

    @Nested
    class Customers {

        @Test
        void aCustomerIsCreatedWithAnIdAndALocation() throws Exception {
            MvcTestResult result = post("/customers", "{\"name\": \"Ada Lovelace\"}");

            assertThat(result).hasStatus(HttpStatus.CREATED);
            assertThat(result).bodyJson().extractingPath("$.name").isEqualTo("Ada Lovelace");
            String id = JsonPath.read(result.getResponse().getContentAsString(), "$.id");
            assertThat(UUID.fromString(id)).isNotNull();
            assertThat(result).hasHeader("Location", "/customers/" + id);
        }

        @Test
        void aBlankNameIsAValidationError() throws Exception {
            MvcTestResult result = post("/customers", "{\"name\": \"  \"}");

            assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
            assertThat(result).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
            assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("VALIDATION_FAILED");
            assertThat(result).bodyJson().extractingPath("$.errors[0].field").isEqualTo("name");
        }

        @Test
        void malformedJsonIsReportedAsSuch() throws Exception {
            MvcTestResult result = post("/customers", "{not json");

            assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
            assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("MALFORMED_REQUEST");
        }
    }

    @Nested
    class OpeningAccounts {

        @Test
        void anAccountStartsAtZeroInItsCurrencysFormat() throws Exception {
            String customer = createCustomer("Grace Hopper");

            MvcTestResult eur = post("/customers/" + customer + "/accounts", "{\"currency\": \"EUR\"}");
            assertThat(eur).hasStatus(HttpStatus.CREATED);
            assertThat(eur).bodyJson().extractingPath("$.currency").isEqualTo("EUR");
            assertThat(eur).bodyJson().extractingPath("$.balance").isEqualTo("0.00");

            MvcTestResult jpy = post("/customers/" + customer + "/accounts", "{\"currency\": \"JPY\"}");
            assertThat(jpy).bodyJson().extractingPath("$.balance").isEqualTo("0");
        }

        @Test
        void aSecondAccountInTheSameCurrencyIsAConflict() throws Exception {
            String customer = createCustomer("Alan Turing");
            post("/customers/" + customer + "/accounts", "{\"currency\": \"GBP\"}");

            MvcTestResult again = post("/customers/" + customer + "/accounts", "{\"currency\": \"GBP\"}");

            assertThat(again).hasStatus(HttpStatus.CONFLICT);
            assertThat(again).bodyJson().extractingPath("$.code").isEqualTo("ACCOUNT_ALREADY_EXISTS");
        }

        @Test
        void aCurrencyEcbDoesNotPublishIsRefused() throws Exception {
            String customer = createCustomer("Katherine Johnson");

            for (String currency : new String[] {"XAU", "ABC", "eur", "EURO", ""}) {
                MvcTestResult result =
                        post("/customers/" + customer + "/accounts", "{\"currency\": \"" + currency + "\"}");
                assertThat(result).as(currency).hasStatus(HttpStatus.BAD_REQUEST);
            }
            MvcTestResult gold = post("/customers/" + customer + "/accounts", "{\"currency\": \"XAU\"}");
            assertThat(gold).bodyJson().extractingPath("$.code").isEqualTo("UNSUPPORTED_CURRENCY");
        }

        @Test
        void anUnknownCustomerIsNotFound() throws Exception {
            MvcTestResult result =
                    post("/customers/" + UUID.randomUUID() + "/accounts", "{\"currency\": \"EUR\"}");

            assertThat(result).hasStatus(HttpStatus.NOT_FOUND);
            assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("CUSTOMER_NOT_FOUND");
        }

        @Test
        void aCustomerIdThatIsNotAUuidIsABadRequest() throws Exception {
            MvcTestResult result = post("/customers/42/accounts", "{\"currency\": \"EUR\"}");

            assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
            assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("MALFORMED_REQUEST");
        }
    }

    @Nested
    class ListingAccounts {

        @Test
        void listsEveryAccountOrderedByCurrency() throws Exception {
            String customer = createCustomer("Hedy Lamarr");
            post("/customers/" + customer + "/accounts", "{\"currency\": \"USD\"}");
            post("/customers/" + customer + "/accounts", "{\"currency\": \"EUR\"}");

            MvcTestResult result = mvc.get().uri("/customers/" + customer + "/accounts").exchange();

            assertThat(result).hasStatus(HttpStatus.OK);
            assertThat(result).bodyJson().extractingPath("$[*].currency").asArray().containsExactly("EUR", "USD");
        }

        @Test
        void aNewCustomerHasNoAccounts() throws Exception {
            String customer = createCustomer("Margaret Hamilton");

            MvcTestResult result = mvc.get().uri("/customers/" + customer + "/accounts").exchange();

            assertThat(result).hasStatus(HttpStatus.OK);
            assertThat(result).bodyJson().extractingPath("$").asArray().isEmpty();
        }

        @Test
        void anUnknownCustomerIsNotFound() throws Exception {
            MvcTestResult result = mvc.get().uri("/customers/" + UUID.randomUUID() + "/accounts").exchange();

            assertThat(result).hasStatus(HttpStatus.NOT_FOUND);
        }
    }
}
