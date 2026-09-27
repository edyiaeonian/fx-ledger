package dev.edyiaeonian.fxledger.account;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "1. Customers and accounts")
class AccountController {

    private final AccountService service;

    AccountController(AccountService service) {
        this.service = service;
    }

    record CreateCustomerRequest(@Schema(example = "Alice") @NotBlank @Size(max = 200) String name) {}

    record CustomerResponse(UUID id, String name, Instant createdAt) {
        static CustomerResponse of(Customer customer) {
            return new CustomerResponse(customer.id(), customer.name(), customer.createdAt());
        }
    }

    // The currency is checked against the supported list by the service, so
    // an unsupported one gets its own error code rather than a generic one.
    record OpenAccountRequest(
            @Schema(description = "ISO 4217 code of a currency the ECB publishes rates for", example = "EUR")
                    @NotNull
                    String currency) {}

    // Amounts are strings ("100.50"), never JSON numbers: many JSON parsers
    // read a number as a binary float.
    record AccountResponse(UUID id, String currency, String balance, Instant createdAt) {
        static AccountResponse of(Account account) {
            return new AccountResponse(
                    account.id(),
                    account.balance().currency().getCurrencyCode(),
                    account.balance().toDecimalString(),
                    account.createdAt());
        }
    }

    @Operation(summary = "Create a customer")
    @PostMapping("/customers")
    ResponseEntity<CustomerResponse> createCustomer(@Valid @RequestBody CreateCustomerRequest request) {
        Customer customer = service.createCustomer(request.name());
        return ResponseEntity.created(URI.create("/customers/" + customer.id()))
                .body(CustomerResponse.of(customer));
    }

    @Operation(
            summary = "Open an account in one currency",
            description = "One account per currency per customer. Errors: UNSUPPORTED_CURRENCY, CUSTOMER_NOT_FOUND, ACCOUNT_ALREADY_EXISTS.")
    @PostMapping("/customers/{customerId}/accounts")
    ResponseEntity<AccountResponse> openAccount(
            @PathVariable UUID customerId, @Valid @RequestBody OpenAccountRequest request) {
        Account account = service.openAccount(customerId, request.currency());
        return ResponseEntity.created(URI.create("/accounts/" + account.id()))
                .body(AccountResponse.of(account));
    }

    @Operation(summary = "List a customer's accounts and balances")
    @GetMapping("/customers/{customerId}/accounts")
    List<AccountResponse> accounts(@PathVariable UUID customerId) {
        return service.accountsOf(customerId).stream().map(AccountResponse::of).toList();
    }
}
