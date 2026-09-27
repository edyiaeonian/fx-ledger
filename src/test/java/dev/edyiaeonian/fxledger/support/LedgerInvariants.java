package dev.edyiaeonian.fxledger.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The properties that must hold for the whole ledger, after any sequence of
 * operations, checked directly in the database rather than through the
 * application's own code.
 */
public final class LedgerInvariants {

    private LedgerInvariants() {}

    public static void assertHold(JdbcClient jdbc) {
        // 1. Money is only moved: every entry sums to zero in each currency.
        List<String> unbalanced = jdbc.sql("""
                SELECT entry_id || ' ' || currency || ' ' || SUM(amount)
                FROM postings
                GROUP BY entry_id, currency
                HAVING SUM(amount) <> 0
                """).query(String.class).list();
        assertThat(unbalanced).as("entries that do not sum to zero").isEmpty();

        // 2. Each cached balance equals the sum of the account's postings.
        List<String> drifted = jdbc.sql("""
                SELECT a.id || ' cached ' || a.balance || ' postings ' || COALESCE(p.total, 0)
                FROM accounts a
                LEFT JOIN (SELECT account_id, SUM(amount) AS total FROM postings GROUP BY account_id) p
                       ON p.account_id = a.id
                WHERE a.balance <> COALESCE(p.total, 0)
                """).query(String.class).list();
        assertThat(drifted).as("balances that differ from their postings").isEmpty();

        // 3. Each account's latest running balance is its cached balance.
        List<String> runningMismatch = jdbc.sql("""
                SELECT a.id || ' cached ' || a.balance || ' last balance_after ' || last.balance_after
                FROM accounts a
                JOIN LATERAL (
                    SELECT balance_after FROM postings WHERE account_id = a.id ORDER BY id DESC LIMIT 1
                ) last ON true
                WHERE a.balance <> last.balance_after
                """).query(String.class).list();
        assertThat(runningMismatch).as("running balances that differ from the cached balance").isEmpty();

        // 4. No customer account is overdrawn.
        List<String> overdrawn = jdbc.sql("""
                SELECT id || ' ' || balance FROM accounts WHERE type = 'CUSTOMER' AND balance < 0
                """).query(String.class).list();
        assertThat(overdrawn).as("overdrawn customer accounts").isEmpty();

        // 5. Across all accounts, every currency nets to zero: follows from 1
        //    and 2, checked anyway because it is the one to explain to anyone.
        List<String> nonZero = jdbc.sql("""
                SELECT currency || ' ' || SUM(balance) FROM accounts GROUP BY currency HAVING SUM(balance) <> 0
                """).query(String.class).list();
        assertThat(nonZero).as("currencies whose balances do not net to zero").isEmpty();
    }
}
