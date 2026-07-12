package com.squarely.auth.repo;

import com.squarely.auth.domain.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * Atomic single-use claim on a token. Concurrent refreshes with the same token
     * serialize on the row lock; only one sees 1 row affected, the rest see 0 and
     * must be treated as reuse. Do not replace with a read-then-setRevoked.
     */
    @Modifying
    @Query("update RefreshToken t set t.revoked = true where t.id = :id and t.revoked = false")
    int revokeIfActive(Long id);

    @Modifying
    @Transactional
    @Query("update RefreshToken t set t.revoked = true where t.userId = :userId and t.revoked = false")
    void revokeAllForUser(Long userId);

    /**
     * REQUIRES_NEW is load-bearing: the caller detects reuse and then throws a 401,
     * which rolls its transaction back. Joining that transaction would roll the
     * revocation back too and reuse detection would silently do nothing.
     */
    @Modifying
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query("update RefreshToken t set t.revoked = true where t.familyId = :familyId and t.revoked = false")
    void revokeAllForFamily(String familyId);
}
