package io.github.erjonfida.ledger;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface TransferRepository extends JpaRepository<Transfer, UUID> {

	Optional<Transfer> findByClientIdAndIdempotencyKey(UUID clientId, String idempotencyKey);

	@Modifying
	@Query(value = """
			INSERT INTO transfer (id, client_id, from_account_id, to_account_id, amount, created_at, idempotency_key)
			VALUES (:#{#t.id}, :#{#t.clientId}, :#{#t.fromAccountId}, :#{#t.toAccountId}, :#{#t.amount},
			        :#{#t.createdAt}, :#{#t.idempotencyKey})
			ON CONFLICT (client_id, idempotency_key) DO NOTHING
			""", nativeQuery = true)
	int insertIfKeyIsNew(Transfer t);

}
