package com.example.autoauction.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * OBS-01: in the running application every response - public, rejected by security, or failed -
 * carries X-Request-Id, and the log pattern prints the id.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("OBS-01: X-Request-Id on every response, request id in every log line")
class RequestIdIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private Environment environment;

    private String requestIdOf(String url) throws Exception {
        return mockMvc.perform(get(url)).andReturn().getResponse().getHeader(RequestIdFilter.HEADER);
    }

    @Test
    @DisplayName("public endpoint and a request rejected by security (401) both carry the id")
    void everyResponse_carriesId() throws Exception {
        assertThat(requestIdOf("/api/health")).isNotBlank();
        assertThat(requestIdOf("/api/v1/admin/withdrawals")).as("401 from Spring Security").isNotBlank();
        assertThat(requestIdOf("/api/health")).isNotEqualTo(requestIdOf("/api/health"));
    }

    @Test
    @DisplayName("the proxy's id is echoed")
    void proxyId_echoed() throws Exception {
        String id = mockMvc.perform(get("/api/health").header(RequestIdFilter.HEADER, "proxy-123"))
                .andReturn().getResponse().getHeader(RequestIdFilter.HEADER);
        assertThat(id).isEqualTo("proxy-123");
    }

    @Test
    @DisplayName("a log line written with the request id in the MDC prints it (console), and the file pattern has it")
    void logLine_printsRequestId(CapturedOutput output) {
        MDC.put(RequestIdFilter.MDC_KEY, "obs01-log-probe");
        try {
            LoggerFactory.getLogger(RequestIdIntegrationTest.class).info("OBS-01 probe line");
        } finally {
            MDC.remove(RequestIdFilter.MDC_KEY);
        }
        assertThat(output.getOut().lines().filter(l -> l.contains("OBS-01 probe line")))
                .singleElement().asString().contains("rid=obs01-log-probe");
        assertThat(environment.getProperty("logging.pattern.file")).contains("%X{requestId");
    }
}
