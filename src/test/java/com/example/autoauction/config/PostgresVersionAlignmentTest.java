package com.example.autoauction.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DB-03: production runs the PostgreSQL that the tests run. docker-compose.yml brought up major
 * version 15 while the application boot check, Hibernate validation and the E2E ran on 16; this
 * test fails as soon as the production image and any test container image drift apart.
 */
@DisplayName("DB-03: production PostgreSQL = tested PostgreSQL")
class PostgresVersionAlignmentTest {

    /** An image reference such as {@code "postgres:16-alpine"} - quoted or after whitespace. */
    private static final Pattern IMAGE = Pattern.compile("[\"\\s]postgres:(\\d+[\\w.-]*)");

    @Test
    void composeAndEveryTestContainerUseTheSameImage() throws IOException {
        List<String> composeImages = images(Files.readString(Path.of("docker-compose.yml"), StandardCharsets.UTF_8));
        assertThat(composeImages).as("exactly one PostgreSQL image in docker-compose.yml").hasSize(1);
        String production = composeImages.get(0);

        Set<String> testImages;
        try (Stream<Path> files = Files.walk(Path.of("src/test/java"))) {
            testImages = files.filter(p -> p.toString().endsWith(".java"))
                    .flatMap(p -> images(read(p)).stream())
                    .collect(Collectors.toSet());
        }
        assertThat(testImages).as("test containers use PostgreSQL").isNotEmpty();
        assertThat(testImages).as("every test container runs the production image " + production)
                .containsOnly(production);
        assertThat(production).startsWith("16");
    }

    private static String read(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<String> images(String text) {
        Matcher m = IMAGE.matcher(text);
        List<String> out = new ArrayList<>();
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }
}
