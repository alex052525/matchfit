package com.matchFit.participation.service

import com.matchFit.participation.dto.request.ManageApplicant
import com.matchFit.participation.dto.request.ManageApplicant.Decision
import com.matchFit.participation.entity.ApplicationStatus
import com.matchFit.participation.entity.Participation
import com.matchFit.participation.repository.ParticipationRepository
import com.matchFit.post.entity.Post
import com.matchFit.post.entity.Sports
import com.matchFit.post.entity.Status
import com.matchFit.post.entity.Town
import com.matchFit.post.repository.PostRepository
import com.matchFit.user.entity.Gender
import com.matchFit.user.entity.User
import com.matchFit.user.repository.UserRepository
import com.matchFit.user.security.CustomUserDetails
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.time.LocalDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

@SpringBootTest
@ActiveProfiles("test")
class WriteSkewTest {

    @Autowired lateinit var participationService: ParticipationService
    @Autowired lateinit var participationRepository: ParticipationRepository
    @Autowired lateinit var postRepository: PostRepository
    @Autowired lateinit var userRepository: UserRepository

    private lateinit var host: User
    private lateinit var post: Post
    private val applicants = mutableListOf<User>()

    @BeforeEach
    fun setUp() {
        participationRepository.deleteAll()
        postRepository.deleteAll()
        userRepository.deleteAll()
        applicants.clear()

        host = User().apply {
            email = "host@test.com"
            password = "password123"
            nickname = "host"
            gender = Gender.MALE
            age = 25
            sports = Sports.FOOTBALL
            town = "서울"
        }
        userRepository.save(host)

        post = Post().apply {
            title = "축구 같이 하실 분"
            description = "테스트 게시글"
            this.gender = Gender.MALE
            this.sports = Sports.FOOTBALL
            cost = 0
            status = Status.OPEN
            this.town = Town.SEOUL
            maxPeople = 3
            date = LocalDateTime.now().plusDays(7)
            location = "서울 잠실"
            user = host
        }
        postRepository.save(post)

        val hostParticipation = Participation(host, post)
        hostParticipation.status = ApplicationStatus.APPROVED
        participationRepository.save(hostParticipation)

        for (i in 1..5) {
            val applicant = User().apply {
                email = "applicant$i@test.com"
                password = "password123"
                nickname = "applicant$i"
                gender = Gender.MALE
                age = 20 + i
                sports = Sports.FOOTBALL
                town = "서울"
            }
            userRepository.save(applicant)
            applicants.add(applicant)

            val participation = Participation(applicant, post)
            participationRepository.save(participation)
        }
    }

    @Test
    @DisplayName("비관적 락 적용 후: 동시 승인 시 maxPeople 초과 방지")
    fun pessimisticLockPreventsWriteSkew() {
        val hostDetails = CustomUserDetails(host)
        val threadCount = 5
        val latch = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(threadCount)
        val successCount = AtomicInteger(0)
        val failCount = AtomicInteger(0)

        val futures = applicants.map { applicant ->
            executor.submit {
                try {
                    latch.await()
                    participationService.manageApplicant(
                        post.id!!,
                        ManageApplicant(applicant.id!!, Decision.ACCEPT),
                        hostDetails
                    )
                    successCount.incrementAndGet()
                } catch (e: Exception) {
                    failCount.incrementAndGet()
                    println("Thread failed for ${applicant.nickname}: ${e.message}")
                }
            }
        }

        latch.countDown()
        futures.forEach { it.get() }
        executor.shutdown()

        val approvedCount = participationRepository.countByPost_IdAndStatus(
            post.id!!, ApplicationStatus.APPROVED
        )
        val refreshedPost = postRepository.findById(post.id!!).get()

        println("==========================================")
        println("maxPeople: ${refreshedPost.maxPeople}")
        println("승인된 인원: $approvedCount")
        println("성공 스레드: ${successCount.get()}")
        println("실패 스레드: ${failCount.get()}")
        println("초과 여부: ${approvedCount > refreshedPost.maxPeople}")
        println("==========================================")

        assertThat(approvedCount)
            .describedAs("비관적 락으로 maxPeople(${refreshedPost.maxPeople})을 초과하지 않아야 합니다")
            .isLessThanOrEqualTo(refreshedPost.maxPeople)
    }
}
