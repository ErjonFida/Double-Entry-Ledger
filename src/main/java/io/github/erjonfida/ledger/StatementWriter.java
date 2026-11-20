package io.github.erjonfida.ledger;

import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.jdbc.core.JdbcTemplate;

class StatementWriter implements ItemWriter<StatementLine> {

	private final JdbcTemplate jdbc;

	private final long reconciliation;

	private final String currency;

	StatementWriter(JdbcTemplate jdbc, long reconciliation, String currency) {
		this.jdbc = jdbc;
		this.reconciliation = reconciliation;
		this.currency = currency;
	}

	@Override
	public void write(Chunk<? extends StatementLine> chunk) {
		List<? extends StatementLine> lines = chunk.getItems();
		int[] stored = jdbc.batchUpdate("""
				INSERT INTO statement_line (reconciliation_id, reference, amount) VALUES (?, ?, ?)
				ON CONFLICT DO NOTHING""", lines, lines.size(), (statement, line) -> {
			statement.setLong(1, reconciliation);
			statement.setString(2, line.reference());
			statement.setLong(3, line.amount());
		})[0];

		List<Mismatch> mismatches = new ArrayList<>();
		List<StatementLine> firstSeen = new ArrayList<>();
		for (int i = 0; i < lines.size(); i++) {
			if (stored[i] == 0) {
				mismatches.add(new Mismatch("DUPLICATE", lines.get(i).reference(), lines.get(i).amount(), null));
			}
			else {
				firstSeen.add(lines.get(i));
			}
		}

		Map<UUID, Long> deposits = deposits(firstSeen);
		for (StatementLine line : firstSeen) {
			Long ledgerAmount = deposits.get(line.transferId());
			if (ledgerAmount == null) {
				mismatches.add(new Mismatch("NOT_IN_LEDGER", line.reference(), line.amount(), null));
			}
			else if (ledgerAmount != line.amount()) {
				mismatches.add(new Mismatch("WRONG_AMOUNT", line.reference(), line.amount(), ledgerAmount));
			}
		}

		jdbc.batchUpdate("""
				INSERT INTO mismatch (reconciliation_id, kind, reference, statement_amount, ledger_amount)
				VALUES (?, ?, ?, ?, ?)""", mismatches, mismatches.size(), (statement, mismatch) -> {
			statement.setLong(1, reconciliation);
			statement.setString(2, mismatch.kind());
			statement.setString(3, mismatch.reference());
			statement.setObject(4, mismatch.statementAmount(), Types.BIGINT);
			statement.setObject(5, mismatch.ledgerAmount(), Types.BIGINT);
		});
	}

	private Map<UUID, Long> deposits(List<StatementLine> lines) {
		UUID[] ids = lines.stream().map(StatementLine::transferId).filter(Objects::nonNull).toArray(UUID[]::new);
		Map<UUID, Long> amounts = new HashMap<>();
		jdbc.query("""
				SELECT t.id, t.amount FROM transfer t JOIN account a ON a.id = t.from_account_id
				WHERE a.cash AND a.currency = ? AND t.id = ANY (?)""", row -> {
			amounts.put(row.getObject(1, UUID.class), row.getLong(2));
		}, currency, ids);
		return amounts;
	}

	private record Mismatch(String kind, String reference, Long statementAmount, Long ledgerAmount) {
	}

}
