package com.materia.backend.common.infrastructure.messaging.outbox;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface SpringDataOutboxRepository extends JpaRepository<OutboxMessageJpaEntity, UUID> {

    /** The oldest messages not relayed yet, parked ones aside. */
    @Query("select m from OutboxMessageJpaEntity m where m.publishedAt is null and m.parkedAt is null order by m.createdAt")
    List<OutboxMessageJpaEntity> findPending(Pageable page);

    /** Messages waiting to be relayed: a growing number means the broker is unreachable. */
    @Query("select count(m) from OutboxMessageJpaEntity m where m.publishedAt is null and m.parkedAt is null")
    long countPending();

    /** Messages that can never be published as they are, waiting for someone to fix them. */
    @Query("select count(m) from OutboxMessageJpaEntity m where m.parkedAt is not null and m.publishedAt is null")
    long countParked();

    /** Forgets messages relayed before this time: the broker has them, and consumers keep their own inbox. */
    @Modifying
    @Query("delete from OutboxMessageJpaEntity m where m.publishedAt < :before")
    int deletePublishedBefore(@Param("before") LocalDateTime before);
}
