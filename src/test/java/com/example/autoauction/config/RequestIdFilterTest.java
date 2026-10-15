package com.example.autoauction.config;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** OBS-01: the request id is in the MDC while the request is served, and only then. */
@DisplayName("OBS-01: RequestIdFilter")
class RequestIdFilterTest {

    private static final String UUID_RE = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    private final RequestIdFilter filter = new RequestIdFilter();

    @Test
    @DisplayName("no header -> a fresh UUID, the same in the MDC and in the response; MDC cleared after")
    void generated_inMdcAndHeader_thenCleared() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest("GET", "/api/x"), response,
                (req, res) -> seen.set(MDC.get(RequestIdFilter.MDC_KEY)));

        assertThat(seen.get()).matches(UUID_RE);
        assertThat(response.getHeader(RequestIdFilter.HEADER)).isEqualTo(seen.get());
        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
    }

    @Test
    @DisplayName("a safe incoming id (from the proxy) is kept")
    void safeIncoming_kept() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/x");
        request.addHeader(RequestIdFilter.HEADER, "nginx-7f3a.42_b");
        AtomicReference<String> seen = new AtomicReference<>();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> seen.set(MDC.get(RequestIdFilter.MDC_KEY)));

        assertThat(seen.get()).isEqualTo("nginx-7f3a.42_b");
        assertThat(response.getHeader(RequestIdFilter.HEADER)).isEqualTo("nginx-7f3a.42_b");
    }

    @Test
    @DisplayName("an unsafe incoming id (CR/LF, spaces, empty, overlong) is replaced, never logged")
    void unsafeIncoming_replaced() {
        for (String bad : new String[] {"abc\r\n2026-10-16 INFO forged line", "a b", "", "x".repeat(65), "id;rm"}) {
            assertThat(RequestIdFilter.resolve(bad)).as(bad).matches(UUID_RE);
        }
        assertThat(RequestIdFilter.resolve("x".repeat(64))).isEqualTo("x".repeat(64));
        assertThat(RequestIdFilter.resolve(null)).matches(UUID_RE);
    }

    @Test
    @DisplayName("the MDC is cleared even when the request fails (pooled threads)")
    void failingRequest_mdcCleared() {
        assertThatThrownBy(() -> filter.doFilter(new MockHttpServletRequest("GET", "/api/x"), new MockHttpServletResponse(),
                (req, res) -> { throw new ServletException("boom"); }))
                .isInstanceOf(ServletException.class);
        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
    }
}
