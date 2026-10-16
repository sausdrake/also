package com.example.autoauction;

import com.example.autoauction.support.AbstractPostgresIntegrationTest;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BASE-02: the whole application context starts on a real PostgreSQL with every Flyway migration applied and
 * Hibernate validating the schema. It used to boot against whatever database localhost:5432 offered, so it
 * failed on every machine without that database; the shared PostgreSQL container gives it one everywhere.
 */
@Testcontainers(disabledWithoutDocker = true)
class AutoauctionApplicationTests extends AbstractPostgresIntegrationTest {

	@Autowired
	private DataSource dataSource;

	@Autowired
	private Flyway flyway;

	@Test
	void contextLoads() throws SQLException {
		// the URL of the real connection: @ServiceConnection overrides the test profile's H2 property
		try (Connection conn = dataSource.getConnection()) {
			assertThat(conn.getMetaData().getURL()).startsWith("jdbc:postgresql:");
		}
		assertThat(flyway.info().current()).as("migrations applied").isNotNull();
		assertThat(flyway.info().pending()).as("pending migrations").isEmpty();
	}

}
