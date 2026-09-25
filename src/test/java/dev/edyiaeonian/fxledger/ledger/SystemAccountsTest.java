package dev.edyiaeonian.fxledger.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import dev.edyiaeonian.fxledger.TestcontainersConfiguration;
import dev.edyiaeonian.fxledger.money.SupportedCurrencies;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

// The migration lists the currencies in SQL and SupportedCurrencies lists them
// in Java; this fails the build if the two ever drift apart.
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SystemAccountsTest {

    @Autowired
    JdbcClient jdbc;

    @Test
    void everySupportedCurrencyHasEachSystemAccountAndNoOtherDoes() {
        for (String type : List.of("FEE_REVENUE", "FX_POSITION", "FUNDING")) {
            List<String> currencies = jdbc.sql("SELECT currency FROM accounts WHERE type = :type ORDER BY currency")
                    .param("type", type)
                    .query(String.class)
                    .list();
            assertThat(currencies).as(type).containsExactlyElementsOf(SupportedCurrencies.codes());
        }
    }
}
