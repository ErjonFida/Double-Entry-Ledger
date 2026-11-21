package io.github.erjonfida.ledger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

// Not part of ./mvnw verify (Surefire only runs *Test and *Tests classes). Run it with:
//   ./mvnw test -Dtest=ReconciliationBenchmark
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ReconciliationBenchmark {

	static final int DEPOSITS = 1_000_000;

	static final int ERRORS_PER_KIND = 250;

	static final int[] CHUNK_SIZES = { 100, 1000, 5000 };

	static final int ROUNDS = 3;

	static final String DAY = "2026-01-15";

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	ClientRepository clients;

	@Autowired
	JobOperator jobOperator;

	@Autowired
	Job reconciliation;

	@TempDir
	Path dir;

	int runs;

	@Test
	void millionLineStatement() throws Exception {
		Path statement = dir.resolve("statement.csv");
		Set<String> injected = writeStatement(statement, deposit());
		assertThat(injected).hasSize(4 * ERRORS_PER_KIND);
		long lines;
		try (var statementLines = Files.lines(statement)) {
			lines = statementLines.count() - 1;
		}

		StringBuilder table = new StringBuilder("""
				| Chunk size | Seconds | Lines/s |
				|--:|--:|--:|
				""");
		for (int chunkSize : CHUNK_SIZES) {
			reconcile(statement, chunkSize, injected);
			double[] seconds = new double[ROUNDS];
			for (int i = 0; i < ROUNDS; i++) {
				seconds[i] = reconcile(statement, chunkSize, injected);
			}
			Arrays.sort(seconds);
			double median = seconds[ROUNDS / 2];
			table.append(String.format(Locale.ROOT, "| %,d | %.1f | %,.0f |%n", chunkSize, median, lines / median));
		}

		System.out.println(table);
		Files.writeString(Path.of("target", "reconciliation-benchmark.md"), table);
	}

	double reconcile(Path statement, int chunkSize, Set<String> injected) throws Exception {
		JobExecution run = jobOperator.start(reconciliation, new JobParametersBuilder()
			.addString("statement", statement.toString())
			.addString("date", DAY)
			.addString("currency", "EUR")
			.addString("run", String.valueOf(++runs))
			.addString("chunk", String.valueOf(chunkSize), false)
			.toJobParameters());
		assertThat(run.getStatus()).isEqualTo(BatchStatus.COMPLETED);
		assertThat(jdbc.queryForList("""
				SELECT concat_ws(' ', kind, reference, statement_amount, ledger_amount)
				FROM mismatch WHERE reconciliation_id = ?""", String.class, run.getJobInstance().getInstanceId()))
			.containsExactlyInAnyOrderElementsOf(injected);
		jdbc.execute("TRUNCATE statement_line, mismatch");
		jdbc.execute("CHECKPOINT");
		return Duration.between(run.getStartTime(), run.getEndTime()).toMillis() / 1000.0;
	}

	// Inserts the deposits' transfer rows directly, which is all reconciliation reads. Entries and balances are left out
	UUID deposit() {
		UUID client = clients.save(new Client("Bank", Client.newApiKey())).getId();
		jdbc.update("""
				INSERT INTO account (id, client_id, name, currency)
				SELECT gen_random_uuid(), ?, 'Customer ' || n, 'EUR' FROM generate_series(1, 1000) n""", client);
		jdbc.update("""
				INSERT INTO transfer (id, client_id, from_account_id, to_account_id, amount, created_at)
				SELECT gen_random_uuid(), ?, (SELECT id FROM account WHERE cash AND currency = 'EUR'),
				       customers.ids[1 + n % 1000], 100 + n % 99900, ?::timestamptz + n * interval '86 milliseconds'
				FROM generate_series(0, ? - 1) n, (SELECT array_agg(id) ids FROM account WHERE client_id = ?) customers""",
				client, DAY + "T00:00:00Z", DEPOSITS, client);
		return client;
	}

	// The day's deposits in order, with 250 left out, 250 at the wrong amount, 250 listed twice and 250 unknown
	Set<String> writeStatement(Path statement, UUID client) throws Exception {
		List<String[]> deposits = jdbc.query("SELECT id::text, amount FROM transfer WHERE client_id = ? ORDER BY created_at",
				(row, i) -> new String[] { row.getString(1), row.getString(2) }, client);
		Random random = new Random(42);
		int[] picked = random.ints(0, deposits.size()).distinct().limit(3 * ERRORS_PER_KIND).toArray();
		Set<Integer> missing = indexes(picked, 0);
		Set<Integer> wrongAmount = indexes(picked, 1);
		Set<Integer> duplicated = indexes(picked, 2);

		Set<String> injected = new HashSet<>();
		List<String> lines = new ArrayList<>(deposits.size() + 2 * ERRORS_PER_KIND);
		List<String> extra = new ArrayList<>();
		for (int i = 0; i < deposits.size(); i++) {
			String id = deposits.get(i)[0];
			long amount = Long.parseLong(deposits.get(i)[1]);
			if (missing.contains(i)) {
				injected.add("MISSING_FROM_STATEMENT " + id + " " + amount);
			}
			else if (wrongAmount.contains(i)) {
				lines.add(id + "," + (amount + 1));
				injected.add("WRONG_AMOUNT " + id + " " + (amount + 1) + " " + amount);
			}
			else {
				lines.add(id + "," + amount);
				if (duplicated.contains(i)) {
					extra.add(id + "," + amount);
					injected.add("DUPLICATE " + id + " " + amount);
				}
			}
		}
		for (int i = 0; i < ERRORS_PER_KIND; i++) {
			UUID unknown = new UUID(random.nextLong(), random.nextLong());
			int amount = 100 + random.nextInt(1000);
			extra.add(unknown + "," + amount);
			injected.add("NOT_IN_LEDGER " + unknown + " " + amount);
		}
		for (String line : extra) {
			lines.add(random.nextInt(lines.size() + 1), line);
		}
		lines.addFirst("reference,amount");
		Files.write(statement, lines);
		return injected;
	}

	static Set<Integer> indexes(int[] picked, int group) {
		Set<Integer> indexes = new HashSet<>();
		for (int i = group * ERRORS_PER_KIND; i < (group + 1) * ERRORS_PER_KIND; i++) {
			indexes.add(picked[i]);
		}
		return indexes;
	}

}
