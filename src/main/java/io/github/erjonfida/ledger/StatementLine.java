package io.github.erjonfida.ledger;

import java.util.UUID;

record StatementLine(String reference, long amount) {

	// Banks often upper-case references, so a transfer id in any case is stored in its usual form
	StatementLine {
		UUID id = parse(reference);
		if (id != null) {
			reference = id.toString();
		}
	}

	UUID transferId() {
		return parse(reference);
	}

	private static UUID parse(String reference) {
		try {
			return UUID.fromString(reference);
		}
		catch (IllegalArgumentException notAnId) {
			return null;
		}
	}

}
