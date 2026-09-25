package dev.edyiaeonian.fxledger;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

// Starts only the web layer, so it needs no database.
@WebMvcTest(PingController.class)
class PingControllerTest {

    @Autowired
    MockMvcTester mvc;

    @Test
    void pingAnswersOk() {
        assertThat(mvc.get().uri("/ping"))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.status")
                .isEqualTo("ok");
    }
}
