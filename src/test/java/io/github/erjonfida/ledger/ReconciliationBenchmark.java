package io.github.erjonfida.ledger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;

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
	JobOperator jobOperator;

	@Autowired
	Job reconciliation;

	@TempDir
	Path dir;

	int runs;

	@Test
	void millionLineStatement() throws Exception {
		Path statement = dir.resolve("statement.csv");
		Set<String> injected = TestStatements.writeStatement(jdbc, statement, TestStatements.deposit(jdbc, DEPOSITS, DAY),
				ERRORS_PER_KIND);
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
		assertThat(TestStatements.mismatches(jdbc, run.getJobInstance().getInstanceId()))
			.containsExactlyInAnyOrderElementsOf(injected);
		jdbc.execute("TRUNCATE statement_line, mismatch");
		jdbc.execute("CHECKPOINT");
		return Duration.between(run.getStartTime(), run.getEndTime()).toMillis() / 1000.0;
	}

}
