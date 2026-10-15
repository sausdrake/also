package com.example.autoauction.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * CFG-01: refuses to start the {@code prod} profile with a configuration that is only acceptable
 * for development - checked once at startup, so a bad deployment fails before it serves a request.
 *
 * <ul>
 *   <li>the {@code dev} profile seeds accounts with well-known passwords and {@code test} switches
 *       to an in-memory database - neither may be active next to {@code prod};</li>
 *   <li>the JWT signing secret must be at least 32 bytes and must not be a value that is already
 *       public (SEC-08: the tutorial example key and the key published in the old public
 *       repository). Only SHA-256 digests of those values are kept here;</li>
 *   <li>CORS origins must be explicit https origins - no wildcard, no localhost;</li>
 *   <li>the public media base URL must be https and not localhost.</li>
 * </ul>
 */
@Component
@Profile("prod")
public class ProductionProfileGuard {

    static final int MIN_JWT_SECRET_BYTES = 32;

    /** SHA-256 of JWT secrets known to be public (SEC-08). Never the values themselves. */
    static final Set<String> COMPROMISED_JWT_SECRET_SHA256 = Set.of(
            "3354cfcf65c7b2e6840ea6f880aede239750cb1d063950c4e8fd9fbf9ec73a32",   // widely published tutorial key
            "1f02db52e8fc42b6eb9a249957133d675184d7931e488170b5c46a680993e400"    // key in the old public README
    );

    public ProductionProfileGuard(Environment environment,
                                  @Value("${jwt.secret}") String jwtSecret,
                                  @Value("${allowed.origins}") String allowedOrigins,
                                  @Value("${media.base-url}") String mediaBaseUrl) {
        List<String> problems = check(Arrays.asList(environment.getActiveProfiles()), jwtSecret, allowedOrigins, mediaBaseUrl);
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Production configuration refused: " + String.join("; ", problems));
        }
    }

    static List<String> check(List<String> activeProfiles, String jwtSecret, String allowedOrigins, String mediaBaseUrl) {
        List<String> problems = new ArrayList<>();
        for (String forbidden : List.of("dev", "test")) {
            if (activeProfiles.contains(forbidden)) {
                problems.add("profile '" + forbidden + "' must not be active together with 'prod'");
            }
        }

        byte[] secret = jwtSecret == null ? new byte[0] : jwtSecret.getBytes(StandardCharsets.UTF_8);
        if (secret.length < MIN_JWT_SECRET_BYTES) {
            problems.add("JWT_SECRET must be at least " + MIN_JWT_SECRET_BYTES + " bytes");
        } else if (COMPROMISED_JWT_SECRET_SHA256.contains(sha256(secret))) {
            problems.add("JWT_SECRET is a publicly known value and must be replaced");
        }

        List<String> origins = allowedOrigins == null ? List.of()
                : Arrays.stream(allowedOrigins.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        if (origins.isEmpty()) {
            problems.add("ALLOWED_ORIGINS must list the production origins");
        }
        for (String origin : origins) {
            String reason = notAPublicHttpsUrl(origin);
            if (reason != null) {
                problems.add("allowed origin '" + origin + "' " + reason);
            }
        }

        String mediaReason = notAPublicHttpsUrl(mediaBaseUrl);
        if (mediaReason != null) {
            problems.add("MEDIA_BASE_URL " + mediaReason);
        }
        return problems;
    }

    private static String notAPublicHttpsUrl(String value) {
        if (value == null || value.isBlank()) {
            return "is missing";
        }
        if (value.contains("*")) {
            return "must not be a wildcard";
        }
        URI uri;
        try {
            uri = URI.create(value.trim());
        } catch (IllegalArgumentException e) {
            return "is not a valid URL";
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            return "must use https";
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (host.isEmpty() || host.equals("localhost") || host.endsWith(".localhost")
                || host.startsWith("127.") || host.equals("0.0.0.0") || host.equals("[::1]") || host.equals("::1")) {
            return "must not point to a local host";
        }
        return null;
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
