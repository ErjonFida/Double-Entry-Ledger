package io.github.erjonfida.ledger;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.batch.core.configuration.annotation.JobScope;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.converter.DefaultJobParametersConverter;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.job.parameters.DefaultJobParametersValidator;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.item.file.FlatFileItemReader;
import org.springframework.batch.infrastructure.item.file.builder.FlatFileItemReaderBuilder;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.util.StringUtils;

@Configuration(proxyBeanMethods = false)
class ReconciliationJob {

	private static final Logger log = LoggerFactory.getLogger(ReconciliationJob.class);

	@Bean
	Job reconciliation(JobRepository jobs, Step match, Step missing) {
		return new JobBuilder("reconciliation", jobs)
			.validator(new DefaultJobParametersValidator(new String[] { "statement", "date", "currency" }, new String[0]))
			.start(match)
			.next(missing)
			.build();
	}

	// A killed run stays recorded as started, so running it again is refused. Once the process is gone, --recover marks
	// it failed, and Spring Batch then restarts it from the last chunk it saved
	@Bean
	@Profile("reconcile")
	@Order(-1)
	ApplicationRunner recoverKilledRun(JobRepository jobs, JobOperator operator, Job reconciliation) {
		return arguments -> {
			if (!arguments.containsOption("recover")) {
				return;
			}
			Properties properties = StringUtils
				.splitArrayElementsIntoProperties(arguments.getNonOptionArgs().toArray(String[]::new), "=");
			JobParameters parameters = new DefaultJobParametersConverter()
				.getJobParameters(properties != null ? properties : new Properties());
			JobExecution last = jobs.getLastJobExecution(reconciliation.getName(), parameters);
			if (last != null && last.isRunning()) {
				operator.recover(last);
			}
		};
	}

	@Bean
	@JobScope
	Step match(JobRepository jobs, PlatformTransactionManager transactions, FlatFileItemReader<StatementLine> statement,
			StatementWriter statementWriter, @Value("#{jobParameters['chunk'] ?: 1000}") int chunkSize) {
		return new StepBuilder("match", jobs).<StatementLine, StatementLine>chunk(chunkSize)
			.reader(statement)
			.writer(statementWriter)
			.transactionManager(transactions)
			.build();
	}

	@Bean
	@StepScope
	FlatFileItemReader<StatementLine> statement(@Value("#{jobParameters['statement']}") String path) {
		return new FlatFileItemReaderBuilder<StatementLine>().name("statement")
			.resource(new FileSystemResource(path))
			.linesToSkip(1)
			.delimited()
			.names("reference", "amount")
			.targetType(StatementLine.class)
			.build();
	}

	@Bean
	@StepScope
	StatementWriter statementWriter(JdbcTemplate jdbc,
			@Value("#{stepExecution.jobExecution.jobInstance.instanceId}") long reconciliation,
			@Value("#{jobParameters['currency']}") String currency) {
		return new StatementWriter(jdbc, reconciliation, currency);
	}

	// Deposits that day which no statement line matched. Deleting first makes the step safe to run again
	@Bean
	Step missing(JobRepository jobs, PlatformTransactionManager transactions, JdbcTemplate jdbc) {
		return new StepBuilder("missing", jobs).tasklet((contribution, context) -> {
			StepExecution step = context.getStepContext().getStepExecution();
			long reconciliation = step.getJobExecution().getJobInstance().getInstanceId();
			JobParameters parameters = step.getJobParameters();
			OffsetDateTime from = LocalDate.parse(parameters.getString("date")).atStartOfDay().atOffset(ZoneOffset.UTC);
			jdbc.update("DELETE FROM mismatch WHERE reconciliation_id = ? AND kind = 'MISSING_FROM_STATEMENT'",
					reconciliation);
			// The first step just added a whole statement. Without fresh statistics the planner can take the table
			// for nearly empty and pick a plan that runs for many minutes instead of seconds
			jdbc.execute("ANALYZE statement_line");
			jdbc.update("""
					INSERT INTO mismatch (reconciliation_id, kind, reference, ledger_amount)
					SELECT ?, 'MISSING_FROM_STATEMENT', t.id::text, t.amount
					FROM transfer t JOIN account a ON a.id = t.from_account_id
					WHERE a.cash AND a.currency = ? AND t.created_at >= ? AND t.created_at < ?
					AND NOT EXISTS (SELECT FROM statement_line l WHERE l.reconciliation_id = ? AND l.reference = t.id::text)""",
					reconciliation, parameters.getString("currency"), from, from.plusDays(1), reconciliation);
			Map<String, Integer> mismatches = new TreeMap<>();
			jdbc.query("SELECT kind, count(*) FROM mismatch WHERE reconciliation_id = ? GROUP BY kind", row -> {
				mismatches.put(row.getString(1), row.getInt(2));
			}, reconciliation);
			log.info("Reconciliation {} mismatches: {}", reconciliation, mismatches.isEmpty() ? "none" : mismatches);
			return RepeatStatus.FINISHED;
		}, transactions).build();
	}

}
