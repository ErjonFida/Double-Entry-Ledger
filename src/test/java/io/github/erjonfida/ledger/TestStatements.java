package io.github.erjonfida.ledger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

final class TestStatements {

	private TestStatements() {
	}

	// Inserts the deposits' transfer rows directly, which is all reconciliation reads. Entries and balances are left out
	static UUID deposit(JdbcTemplate jdbc, int count, String day) {
		UUID client = jdbc.queryForObject("""
				INSERT INTO client (id, name, api_key_hash) VALUES (gen_random_uuid(), 'Bank', md5(random()::text))
				RETURNING id""", UUID.class);
		jdbc.update("""
				INSERT INTO account (id, client_id, name, currency)
				SELECT gen_random_uuid(), ?, 'Customer ' || n, 'EUR' FROM generate_series(1, 1000) n""", client);
		jdbc.update("""
				INSERT INTO transfer (id, client_id, from_account_id, to_account_id, amount, created_at)
				SELECT gen_random_uuid(), ?, (SELECT id FROM account WHERE cash AND currency = 'EUR'),
				       customers.ids[1 + n % 1000], 100 + n % 99900, ?::timestamptz + n * (interval '1 day' / ?)
				FROM generate_series(0, ? - 1) n, (SELECT array_agg(id) ids FROM account WHERE client_id = ?) customers""",
				client, day + "T00:00:00Z", count, count, client);
		return client;
	}

	// The client's deposits in order, with some left out, some at the wrong amount, some listed twice and some unknown.
	// Returns the mismatches the job should report, in the form "KIND reference statement-amount ledger-amount"
	static Set<String> writeStatement(JdbcTemplate jdbc, Path statement, UUID client, int errorsPerKind) throws Exception {
		List<String[]> deposits = jdbc.query("SELECT id::text, amount FROM transfer WHERE client_id = ? ORDER BY created_at",
				(row, i) -> new String[] { row.getString(1), row.getString(2) }, client);
		Random random = new Random(42);
		int[] picked = random.ints(0, deposits.size()).distinct().limit(3L * errorsPerKind).toArray();
		Set<Integer> missing = indexes(picked, 0, errorsPerKind);
		Set<Integer> wrongAmount = indexes(picked, 1, errorsPerKind);
		Set<Integer> duplicated = indexes(picked, 2, errorsPerKind);

		Set<String> injected = new HashSet<>();
		List<String> lines = new ArrayList<>(deposits.size() + 2 * errorsPerKind);
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
		for (int i = 0; i < errorsPerKind; i++) {
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

	static List<String> mismatches(JdbcTemplate jdbc, long reconciliation) {
		return jdbc.queryForList("""
				SELECT concat_ws(' ', kind, reference, statement_amount, ledger_amount)
				FROM mismatch WHERE reconciliation_id = ?""", String.class, reconciliation);
	}

	private static Set<Integer> indexes(int[] picked, int group, int size) {
		Set<Integer> indexes = new HashSet<>();
		for (int i = group * size; i < (group + 1) * size; i++) {
			indexes.add(picked[i]);
		}
		return indexes;
	}

}
