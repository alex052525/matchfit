package com.matchFit.post.repository

import com.matchFit.post.entity.Post
import com.matchFit.post.entity.Status
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDateTime

interface PostRepository : JpaRepository<Post, Long>, PostRepositoryCustom {

    fun findAllByDateBetween(start: LocalDateTime, end: LocalDateTime): List<Post>

    fun findByUserIdOrderByCreatedAtDesc(userId: Long): List<Post>

    @Modifying
    @Query("update Post p set p.status = :expired where p.date < :now and p.status = :open")
    fun markExpired(
        @Param("now") now: LocalDateTime,
        @Param("expired") expired: Status,
        @Param("open") open: Status
    ): Int

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Post p WHERE p.id = :id")
    fun findByIdForUpdate(@Param("id") id: Long): Post?
}
