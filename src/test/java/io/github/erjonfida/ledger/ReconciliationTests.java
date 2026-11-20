package io.github.erjonfida.ledger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
	Job reconciliation;

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
		JobParameters parameters = new JobParametersBuilder().addString("statement", statement.toString())
			.addString("date", "2020-02-29")
			.addString("currency", "EUR")
			.toJobParameters();

		JobExecution run = jobOperator.start(reconciliation, parameters);

		assertThat(run.getStatus()).isEqualTo(BatchStatus.COMPLETED);
		assertThat(mismatches(run)).containsExactlyInAnyOrder(
				"WRONG_AMOUNT " + wrongAmount.getId() + " 2500 2000",
				"DUPLICATE " + duplicated.getId() + " 3000",
				"NOT_IN_LEDGER " + unknown + " 800",
				"NOT_IN_LEDGER not-a-transfer 700",
				"NOT_IN_LEDGER " + internal.getId() + " 100",
				"MISSING_FROM_STATEMENT " + missing.getId() + " 4000");
		assertThatThrownBy(() -> jobOperator.start(reconciliation, parameters))
			.isInstanceOf(JobInstanceAlreadyCompleteException.class);
	}

	private List<String> mismatches(JobExecution run) {
		return jdbc.queryForList("""
				SELECT concat_ws(' ', kind, reference, statement_amount, ledger_amount)
				FROM mismatch WHERE reconciliation_id = ?""", String.class, run.getJobInstance().getInstanceId());
	}

}
