package dev.edyiaeonian.fxledger.fx;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.http.Fault;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Currency;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

// A real HTTP server on a local port, answering as the rate API would -- or
// failing as it might. No Spring: the client is constructed directly.
class FrankfurterRateSourceTest {

    static final String PATH = "/v2/providers/ecb/rates?base=EUR";

    WireMockServer server;
    FrankfurterRateSource source;

    @BeforeEach
    void start() {
        server = new WireMockServer(options().dynamicPort());
        server.start();
        FxProperties properties = new FxProperties(
                URI.create(server.baseUrl()), false, Duration.ofHours(1),
                Duration.ofSeconds(2), Duration.ofMillis(500),
                Duration.ofDays(4), new BigDecimal("0.005"), Duration.ofMinutes(10));
        source = new FrankfurterRateSource(properties);
    }

    @AfterEach
    void stop() {
        server.stop();
    }

    @Test
    void readsEachRateExactlyAsPublished() {
        server.stubFor(get(urlEqualTo(PATH)).willReturn(okJson("""
                [{"date":"2026-09-25","base":"EUR","quote":"GBP","rate":0.85986},
                 {"date":"2026-09-25","base":"EUR","quote":"EUR","rate":1.0},
                 {"date":"2026-09-25","base":"EUR","quote":"IDR","rate":20384.123456789012345678}]
                """)));

        RateSnapshot snapshot = source.fetch();

        assertThat(snapshot.date()).isEqualTo(LocalDate.of(2026, 9, 25));
        assertThat(snapshot.perEuro().get(Currency.getInstance("GBP"))).isEqualTo(new BigDecimal("0.85986"));
        // More digits than a double can hold: they survive only if the JSON
        // number is read straight into a BigDecimal.
        assertThat(snapshot.perEuro().get(Currency.getInstance("IDR")))
                .isEqualTo(new BigDecimal("20384.123456789012345678"));
        // The euro is implicit, not a rate.
        assertThat(snapshot.perEuro()).doesNotContainKey(Currency.getInstance("EUR"));
    }

    @Test
    void anErrorStatusIsAFailure() {
        server.stubFor(get(urlEqualTo(PATH)).willReturn(aResponse().withStatus(500)));

        assertThatThrownBy(source::fetch).isInstanceOf(RateSourceException.class).hasMessageContaining("500");
    }

    @Test
    void aSlowAnswerTimesOut() {
        server.stubFor(get(urlEqualTo(PATH)).willReturn(okJson("[]").withFixedDelay(2_000)));

        assertThatThrownBy(source::fetch).isInstanceOf(RateSourceException.class);
    }

    @Test
    void aDroppedConnectionIsAFailure() {
        server.stubFor(get(urlEqualTo(PATH)).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        assertThatThrownBy(source::fetch).isInstanceOf(RateSourceException.class);
    }

    @Test
    void somethingThatIsNotTheExpectedJsonIsAFailure() {
        server.stubFor(get(urlEqualTo(PATH)).willReturn(okJson("{\"status\":\"maintenance\"}")));

        assertThatThrownBy(source::fetch).isInstanceOf(RateSourceException.class);
    }

    @Test
    void anEmptyAnswerIsAFailure() {
        server.stubFor(get(urlEqualTo(PATH)).willReturn(okJson("[]")));

        assertThatThrownBy(source::fetch).isInstanceOf(RateSourceException.class).hasMessageContaining("no rates");
    }

    @Test
    void ratesForMoreThanOneDayAreRefused() {
        server.stubFor(get(urlEqualTo(PATH)).willReturn(okJson("""
                [{"date":"2026-09-25","base":"EUR","quote":"GBP","rate":0.85},
                 {"date":"2026-09-24","base":"EUR","quote":"USD","rate":1.13}]
                """)));

        assertThatThrownBy(source::fetch).isInstanceOf(RateSourceException.class).hasMessageContaining("date");
    }

    @Test
    void ratesAgainstAnotherBaseAreRefused() {
        server.stubFor(get(urlEqualTo(PATH)).willReturn(okJson("""
                [{"date":"2026-09-25","base":"USD","quote":"GBP","rate":0.75}]
                """)));

        assertThatThrownBy(source::fetch).isInstanceOf(RateSourceException.class).hasMessageContaining("base");
    }
}
