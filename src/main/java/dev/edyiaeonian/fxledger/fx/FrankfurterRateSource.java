package dev.edyiaeonian.fxledger.fx;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.time.LocalDate;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * European Central Bank reference rates, through the Frankfurter API
 * ({@code GET /v2/providers/ecb/rates?base=EUR}), which needs no key.
 *
 * <p>The answer is a JSON array with one object per currency. Each rate is a
 * JSON number, bound to a BigDecimal field so that Jackson reads its digits
 * directly and never through a double.
 */
@Component
class FrankfurterRateSource implements RateSource {

    record Row(LocalDate date, String base, String quote, BigDecimal rate) {}

    private static final ParameterizedTypeReference<List<Row>> ROWS = new ParameterizedTypeReference<>() {};

    private final RestClient http;

    FrankfurterRateSource(FxProperties properties) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(properties.readTimeout());
        this.http = RestClient.builder().baseUrl(properties.baseUrl().toString()).requestFactory(factory).build();
    }

    @Override
    public RateSnapshot fetch() {
        List<Row> rows;
        try {
            rows = http.get().uri("/v2/providers/ecb/rates?base=EUR").retrieve().body(ROWS);
        } catch (RestClientException failure) {
            // An error status, a timeout, a dropped connection or unreadable
            // JSON: all mean the same to the caller -- no rates this time.
            throw new RateSourceException("fetching ECB rates failed: " + failure.getMessage(), failure);
        }
        return toSnapshot(rows);
    }

    private static RateSnapshot toSnapshot(List<Row> rows) {
        if (rows == null || rows.isEmpty()) {
            throw new RateSourceException("the rate source returned no rates");
        }
        Set<String> bases = rows.stream().map(Row::base).collect(Collectors.toSet());
        if (!bases.equals(Set.of("EUR"))) {
            throw new RateSourceException("expected rates against base EUR, got " + bases);
        }
        Set<LocalDate> dates = rows.stream().map(Row::date).collect(Collectors.toSet());
        if (dates.size() != 1 || dates.contains(null)) {
            throw new RateSourceException("expected rates for one date, got " + dates);
        }
        Map<Currency, BigDecimal> perEuro = new HashMap<>();
        for (Row row : rows) {
            if ("EUR".equals(row.quote())) {
                continue; // the base against itself, always 1
            }
            if (row.quote() == null || row.rate() == null) {
                throw new RateSourceException("incomplete rate row: " + row);
            }
            try {
                perEuro.put(Currency.getInstance(row.quote()), row.rate());
            } catch (IllegalArgumentException unknown) {
                throw new RateSourceException("unknown currency in rates: " + row.quote(), unknown);
            }
        }
        return new RateSnapshot(dates.iterator().next(), perEuro);
    }
}
