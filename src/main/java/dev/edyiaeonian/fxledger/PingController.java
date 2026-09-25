package dev.edyiaeonian.fxledger;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** The smallest possible endpoint: proves the web layer is wired up. */
@RestController
class PingController {

    @GetMapping("/ping")
    Map<String, String> ping() {
        return Map.of("status", "ok");
    }
}
