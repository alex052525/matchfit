package com.matchFit.participation.service

import com.matchFit.participation.dto.request.ManageApplicant
import com.matchFit.participation.dto.request.ManageApplicant.Decision
import com.matchFit.participation.dto.response.DecisionApplicant
import com.matchFit.participation.dto.response.GetMyPostsParticipationResponseDto
import com.matchFit.participation.entity.ApplicationStatus
import com.matchFit.participation.entity.Participation
import com.matchFit.participation.exception.ParticipationCancellationTimeExceededException
import com.matchFit.participation.repository.ParticipationRepository
import com.matchFit.post.dto.response.GetMyPostApplicant
import com.matchFit.post.dto.response.GetMyPostApplicants
import com.matchFit.post.entity.Status
import com.matchFit.post.exception.PostNotFoundException
import com.matchFit.post.exception.UnauthorizedUserException
import com.matchFit.post.repository.PostRepository
import com.matchFit.user.exception.UserNotFoundException
import com.matchFit.user.repository.UserRepository
import com.matchFit.user.security.CustomUserDetails
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.LocalDateTime

@Service
class ParticipationService(
    private val participationRepository: ParticipationRepository,
    private val userRepository: UserRepository,
    private val postRepository: PostRepository,
    private val redisTemplate: StringRedisTemplate
) {
    private val log = LoggerFactory.getLogger(ParticipationService::class.java)

    private fun getApplicantKey(postId: Long): String =
        String.format(APPLICANT_KEY_FMT, postId)

    private fun updateApplicantCount(postId: Long, delta: Long): Long? {
        val key = getApplicantKey(postId)
        return try {
            val ttl = Duration.ofMinutes(APPLICANT_KEY_TTL_MINUTES.toLong())
            if (redisTemplate.hasKey(key) == true) {
                val newCount = redisTemplate.opsForValue().increment(key, delta)
                redisTemplate.expire(key, ttl)
                newCount
            } else {
                val approvedCount = participationRepository.countByPost_IdAndStatus(
                    postId,
                    ApplicationStatus.APPROVED
                )
                redisTemplate.opsForValue().set(key, approvedCount.toString(), ttl)
                approvedCount.toLong()
            }
        } catch (ex: Exception) {
            log.error("Redis update failed for post {}: {}", postId, ex.message, ex)
            null
        }
    }

    @Transactional
    fun applyPost(postId: Long, userId: Long) {
        val user = userRepository.findById(userId)
            .orElseThrow { IllegalArgumentException("사용자 없음") }
        val post = postRepository.findById(postId)
            .orElseThrow { IllegalArgumentException("모집 글이 존재하지 않습니다") }

        if (participationRepository.findByPostIdAndUserId(postId, userId) != null) {
            throw IllegalStateException("이미 신청한 모집글입니다")
        }

        val currentPeople = participationRepository.countByPost_IdAndStatus(
            postId,
            ApplicationStatus.APPROVED
        )
        if (currentPeople >= post.maxPeople) {
            throw IllegalStateException("마감되었습니다")
        }

        val participation = Participation(user, post)
        participationRepository.saveAndFlush(participation)
    }

    @Transactional
    fun cancelApplyPost(postId: Long, userId: Long) {
        userRepository.findById(userId)
            .orElseThrow { UserNotFoundException() }
        val post = postRepository.findById(postId)
            .orElseThrow { PostNotFoundException() }
        val participation = participationRepository.findByPostIdAndUserId(postId, userId)!!

        val now = LocalDateTime.now()
        val eventTime = post.date
        val cutoffTime = eventTime.toLocalDate().minusDays(1).atStartOfDay()
        if (now.isAfter(cutoffTime)) {
            throw ParticipationCancellationTimeExceededException()
        }

        val wasApproved = participation.status == ApplicationStatus.APPROVED
        participationRepository.delete(participation)

        if (wasApproved) {
            updateApplicantCount(postId, -1)
            val currentApproved = participationRepository.countByPost_IdAndStatus(
                postId,
                ApplicationStatus.APPROVED
            )
            if (currentApproved < post.maxPeople && post.status == Status.CLOSED) {
                post.status = Status.OPEN
                postRepository.save(post)
            }
        }
    }

    fun getApplicantsByPost(postId: Long, userDetails: CustomUserDetails): GetMyPostApplicants {
        val post = postRepository.findById(postId)
            .orElseThrow { IllegalArgumentException("존재하지 않는 모집글입니다.") }
        val currentUser = userDetails.user

        if (post.user.id != currentUser.id) {
            throw AccessDeniedException("본인의 모집글만 조회할 수 있습니다.")
        }

        val applicants = participationRepository.findAllByPostIdWithUser(postId)
        val applicantDtos = GetMyPostApplicant.from(applicants)
        return GetMyPostApplicants.from(applicantDtos)
    }

    fun getMyPostsParticipation(userId: Long): List<GetMyPostsParticipationResponseDto> {
        val participations = participationRepository.findByUserIdWithPost(userId)
        return participations.map { convertToDto(it) }
    }

    private fun convertToDto(participation: Participation): GetMyPostsParticipationResponseDto {
        val post = participation.post
        val currentPeople = participationRepository.countByPost_IdAndStatus(
            post.id!!,
            ApplicationStatus.APPROVED
        )

        return GetMyPostsParticipationResponseDto(
            post.id!!,
            post.title,
            post.date.toString(),
            currentPeople,
            post.maxPeople,
            post.location,
            post.cost,
            participation.status,
            post.status
        )
    }

    @Transactional
    fun manageApplicant(
        postId: Long,
        dto: ManageApplicant,
        userDetails: CustomUserDetails
    ): DecisionApplicant {
        val post = postRepository.findByIdForUpdate(postId) ?: throw PostNotFoundException()
        val currentUser = userDetails.user
        if (post.user.id != currentUser.id) {
            throw UnauthorizedUserException()
        }

        val participation = participationRepository.findByPostIdAndUserId(postId, dto.applicantId)!!
        val previous = participation.status

        if (dto.decision == Decision.ACCEPT) {
            if (previous == ApplicationStatus.APPROVED) {
                return DecisionApplicant(
                    participation.user.id!!,
                    participation.user.nickname,
                    participation.status
                )
            }

            val currentApproved = participationRepository.countByPost_IdAndStatus(postId, ApplicationStatus.APPROVED)
            if (currentApproved >= post.maxPeople) {
                throw IllegalStateException("모집 인원이 마감되었습니다.")
            }

            participation.status = ApplicationStatus.APPROVED
            participationRepository.saveAndFlush(participation)

            val newCount = updateApplicantCount(postId, 1) ?: 0L
            if (newCount >= post.maxPeople.toLong() && post.status != Status.CLOSED) {
                post.status = Status.CLOSED
                postRepository.save(post)
            }
        } else {
            if (previous == ApplicationStatus.APPROVED) {
                updateApplicantCount(postId, -1)
            }
            participation.status = ApplicationStatus.REJECTED
            participationRepository.saveAndFlush(participation)
        }

        return DecisionApplicant(
            participation.user.id!!,
            participation.user.nickname,
            participation.status
        )
    }

    companion object {
        private const val APPLICANT_KEY_FMT = "applicants:post_%d"
        private const val APPLICANT_KEY_TTL_MINUTES = 5
    }
}
