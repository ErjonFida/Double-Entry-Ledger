package io.github.erjonfida.ledger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.function.ToDoubleFunction;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

// Not part of ./mvnw verify (Surefire only runs *Test and *Tests classes). Run it with:
//   ./mvnw test -Dtest=DepositBenchmark
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class DepositBenchmark {

	static final int CLIENTS = 200;

	static final Duration ROUND = Duration.ofSeconds(10);

	static final int ROUNDS = 3;

	@Autowired
	LedgerService ledger;

	@Autowired
	ClientRepository clients;

	@Autowired
	JdbcTemplate jdbc;

	record Round(int deposits, double throughput, double medianMillis, double p99Millis) {
	}

	// Each client deposits 1 cent at a time into an account of its own, so only the cash account is shared
	@Test
	void depositsIntoDifferentAccounts() throws Exception {
		UUID client = clients.save(new Client("Benchmark", Client.newApiKey())).getId();
		List<UUID> accounts = new ArrayList<>();
		for (int i = 0; i < CLIENTS; i++) {
			accounts.add(ledger.open(client, "Customer " + i, "EUR").getId());
		}

		int deposits = round(client, accounts).deposits();
		List<Round> rounds = new ArrayList<>();
		for (int i = 0; i < ROUNDS; i++) {
			Round round = round(client, accounts);
			deposits += round.deposits();
			rounds.add(round);
		}

		assertThat(jdbc.queryForObject("SELECT sum(balance) FROM account WHERE client_id = ?", Long.class, client))
			.isEqualTo(deposits);
		assertThat(jdbc.queryForObject("SELECT coalesce(sum(amount), 0) FROM entry", Long.class)).isZero();
		String table = String.format(Locale.ROOT, """
				| Deposits/s | Median latency (ms) | p99 latency (ms) |
				|--:|--:|--:|
				| %,.0f | %.0f | %.0f |
				""", median(rounds, Round::throughput), median(rounds, Round::medianMillis),
				median(rounds, Round::p99Millis));
		System.out.println(table);
		Files.writeString(Path.of("target", "deposit-benchmark.md"), table);
	}

	Round round(UUID client, List<UUID> accounts) throws Exception {
		Queue<Long> latencies = new ConcurrentLinkedQueue<>();
		CountDownLatch go = new CountDownLatch(1);
		long start;
		try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
			for (UUID account : accounts) {
				pool.submit(() -> {
					go.await();
					long deadline = System.nanoTime() + ROUND.toNanos();
					while (System.nanoTime() < deadline) {
						long t = System.nanoTime();
						ledger.deposit(client, account, 1, UUID.randomUUID().toString());
						latencies.add(System.nanoTime() - t);
					}
					return null;
				});
			}
			start = System.nanoTime();
			go.countDown();
		}
		double seconds = (System.nanoTime() - start) / 1e9;
		long[] sorted = latencies.stream().mapToLong(Long::longValue).sorted().toArray();
		return new Round(sorted.length, sorted.length / seconds, TransferBenchmark.millisAt(sorted, 0.5),
				TransferBenchmark.millisAt(sorted, 0.99));
	}

	static double median(List<Round> rounds, ToDoubleFunction<Round> metric) {
		double[] values = rounds.stream().mapToDouble(metric).sorted().toArray();
		return values[values.length / 2];
	}

}
