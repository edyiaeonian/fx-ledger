package dev.edyiaeonian.fxledger.common;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The header of the API documentation served at /swagger-ui.html. */
@Configuration
class OpenApiConfiguration {

    @Bean
    OpenAPI openApi() {
        return new OpenAPI().info(new Info()
                .title("fx-ledger")
                .version("1.0")
                .description("""
                        A multi-currency ledger with locked FX quotes and idempotent transfers.

                        **Try it in order:** create two customers, open an account for each
                        (say EUR and GBP), deposit into the EUR one, request a quote from EUR
                        to GBP, then carry it out with a transfer. The statement shows every
                        posting with its running balance.

                        Amounts are strings (`"100.50"`), never JSON numbers. Deposits and
                        transfers need an `Idempotency-Key` header, unique per account:
                        repeating a request with the same key returns the original result
                        instead of moving money again. Errors are RFC 9457 problem details
                        with a stable `code`.
                        """)
                .license(new License().name("MIT")));
    }
}
