package dev.edyiaeonian.fxledger;

import org.springframework.boot.SpringApplication;

public class TestFxLedgerApplication {

	public static void main(String[] args) {
		SpringApplication.from(FxLedgerApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
