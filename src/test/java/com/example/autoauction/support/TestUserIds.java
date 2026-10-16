package com.example.autoauction.support;

import java.util.concurrent.atomic.AtomicLong;

/**
 * TEST-04: synthetic user ids for integration-test fixtures that need a user nobody else in the
 * same JVM uses - typically a winner or seller that gets its own deposit. deposits.user_id is
 * UNIQUE and DepositService.createDeposit returns the EXISTING deposit for a known user id, and
 * the H2 test database is shared by every test of a cached Spring context and never cleaned: a
 * reused id silently adds one test's frozen amount to another test's deposit.
 *
 * <p>These ids used to be {@code base + System.nanoTime() % 100_000}. On Windows nanoTime ticks in
 * 100 ns steps, which leaves only 1 000 possible values; two of eight runs of the auction test
 * package failed on such a collision. A JVM-wide counter cannot repeat: one static
 * {@link AtomicLong}, never reset - Surefire runs every test class in one forked JVM and class
 * loader, and each fork has its own in-memory H2 database.
 *
 * <p>Namespace: from {@link #FIRST} upwards - above every hard-coded test user id (the largest is
 * 1 000 000 000), every nanoTime-derived one (below 101 000 000), ids handed out by database
 * sequences, and the int range. For user ids only, not a general entity id source.
 */
public final class TestUserIds {

    /** The first id handed out; the class comment explains why the namespace is safe. */
    public static final long FIRST = 5_000_000_000L;

    private static final AtomicLong NEXT = new AtomicLong(FIRST);

    private TestUserIds() {}

    /** A user id no earlier call in this JVM has returned. Thread-safe. */
    public static long next() {
        return NEXT.getAndIncrement();
    }
}
