package io.github.erjonfida.ledger;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.postgresql.PostgreSQLContainer;

import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ReconciliationTests {

	@Autowired
	LedgerService ledger;

	@Autowired
	ClientRepository clients;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	JobOperator jobOperator;

	@Autowired
	JobRepository jobs;

	@Autowired
	Job reconciliation;

	@Autowired
	PostgreSQLContainer postgres;

	@TempDir
	Path dir;

	@Test
	void reportsEveryKindOfMismatchAndNothingElse() throws Exception {
		UUID client = clients.save(new Client("Shop", Client.newApiKey())).getId();
		UUID alice = ledger.open(client, "Alice", "EUR").getId();
		UUID bob = ledger.open(client, "Bob", "EUR").getId();
		UUID carol = ledger.open(client, "Carol", "USD").getId();

		Transfer matched = ledger.deposit(client, alice, 1000, "matched");
		Transfer wrongAmount = ledger.deposit(client, alice, 2000, "wrong-amount");
		Transfer duplicated = ledger.deposit(client, alice, 3000, "duplicated");
		Transfer missing = ledger.deposit(client, alice, 4000, "missing");
		Transfer otherDay = ledger.deposit(client, alice, 5000, "other-day");
		ledger.deposit(client, carol, 6000, "other-currency");
		Transfer internal = ledger.transfer(client, alice, bob, 100, "internal");
		jdbc.update("UPDATE transfer SET created_at = '2020-02-29 12:00Z' WHERE client_id = ?", client);
		jdbc.update("UPDATE transfer SET created_at = '2020-02-28 23:59Z' WHERE id = ?", otherDay.getId());

		UUID unknown = UUID.randomUUID();
		Path statement = dir.resolve("statement.csv");
		Files.writeString(statement, """
				reference,amount
				%s,1000
				%s,2500
				%s,3000
				%s,3000
				%s,800
				not-a-transfer,700
				%s,100
				""".formatted(matched.getId().toString().toUpperCase(), wrongAmount.getId(), duplicated.getId(),
				duplicated.getId(), unknown, internal.getId()));
		JobParameters parameters = parameters(statement, "2020-02-29");

		JobExecution run = jobOperator.start(reconciliation, parameters);

		assertThat(run.getStatus()).isEqualTo(BatchStatus.COMPLETED);
		assertThat(TestStatements.mismatches(jdbc, run.getJobInstance().getInstanceId())).containsExactlyInAnyOrder(
				"WRONG_AMOUNT " + wrongAmount.getId() + " 2500 2000",
				"DUPLICATE " + duplicated.getId() + " 3000",
				"NOT_IN_LEDGER " + unknown + " 800",
				"NOT_IN_LEDGER not-a-transfer 700",
				"NOT_IN_LEDGER " + internal.getId() + " 100",
				"MISSING_FROM_STATEMENT " + missing.getId() + " 4000");
		assertThatThrownBy(() -> jobOperator.start(reconciliation, parameters))
			.isInstanceOf(JobInstanceAlreadyCompleteException.class);
	}

	@Test
	void carriesOnFromTheLastSavedChunkAfterTheProcessIsKilled() throws Exception {
		Path statement = dir.resolve("big-statement.csv");
		Set<String> injected = TestStatements.writeStatement(jdbc, statement,
				TestStatements.deposit(jdbc, 200_000, "2021-06-01"), 50);
		long lines = Files.readAllLines(statement).size() - 1;
		String[] job = { "statement=" + statement, "date=2021-06-01", "currency=EUR" };

		Process killed = reconcile(dir.resolve("killed.log"), job);
		await().atMost(Duration.ofMinutes(2))
			.failFast("the job ended before it was killed", () -> !killed.isAlive())
			.until(() -> linesSavedByRunningJob() >= 20_000);
		killed.destroyForcibly().waitFor();

		assertThat(exitCode(reconcile(dir.resolve("refused.log"), job))).isEqualTo(1);
		assertThat(Files.readString(dir.resolve("refused.log"), StandardCharsets.ISO_8859_1))
			.contains("JobExecutionAlreadyRunningException");

		String[] recover = Arrays.copyOf(job, job.length + 1);
		recover[job.length] = "--recover";
		assertThat(exitCode(reconcile(dir.resolve("recovered.log"), recover))).isZero();

		long instance = jobs.getJobInstance("reconciliation", parameters(statement, "2021-06-01")).getInstanceId();
		List<Map<String, Object>> runs = jdbc.queryForList("""
				SELECT e.status, s.read_count FROM batch_job_execution e
				JOIN batch_step_execution s ON s.job_execution_id = e.job_execution_id AND s.step_name = 'match'
				WHERE e.job_instance_id = ? ORDER BY e.job_execution_id""", instance);
		assertThat(runs).extracting(run -> run.get("status")).containsExactly("FAILED", "COMPLETED");
		long beforeKill = (long) runs.get(0).get("read_count");
		long afterRestart = (long) runs.get(1).get("read_count");
		System.out.printf("Killed with %,d of %,d lines saved; the restart read %,d%n", beforeKill, lines, afterRestart);
		assertThat(beforeKill).isGreaterThanOrEqualTo(20_000);
		assertThat(beforeKill + afterRestart).isEqualTo(lines);
		assertThat(TestStatements.mismatches(jdbc, instance)).containsExactlyInAnyOrderElementsOf(injected);
	}

	static JobParameters parameters(Path statement, String date) {
		return new JobParametersBuilder().addString("statement", statement.toString())
			.addString("date", date)
			.addString("currency", "EUR")
			.toJobParameters();
	}

	long linesSavedByRunningJob() {
		return jdbc.queryForObject(
				"SELECT coalesce(max(read_count), 0) FROM batch_step_execution WHERE step_name = 'match' AND status = 'STARTED'",
				Long.class);
	}

	// Runs the job the way an operator would, as its own JVM, so that it can be killed
	Process reconcile(Path log, String... arguments) throws Exception {
		Path classpath = dir.resolve("classpath.txt");
		Files.writeString(classpath, "-cp \"" + mainClasspath().replace('\\', '/') + "\"");
		List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
				"@" + classpath, LedgerApplication.class.getName(), "--spring.profiles.active=reconcile",
				"--spring.datasource.url=" + postgres.getJdbcUrl(), "--spring.datasource.username=" + postgres.getUsername(),
				"--spring.datasource.password=" + postgres.getPassword()));
		command.addAll(List.of(arguments));
		return new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
	}

	// Without the test classes, which would start containers of their own
	static String mainClasspath() {
		String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
		return Arrays.stream(classpath.split(File.pathSeparator))
			.filter(entry -> !Path.of(entry).endsWith("test-classes"))
			.collect(Collectors.joining(File.pathSeparator));
	}

	static int exitCode(Process process) throws InterruptedException {
		assertThat(process.waitFor(2, TimeUnit.MINUTES)).as("the job finished within 2 minutes").isTrue();
		return process.exitValue();
	}

}
