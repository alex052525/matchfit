package com.matchFit.post.service

import com.matchFit.participation.entity.ApplicationStatus
import com.matchFit.participation.entity.Participation
import com.matchFit.participation.repository.ParticipationRepository
import com.matchFit.post.entity.Post
import com.matchFit.post.entity.SortType
import com.matchFit.post.entity.Sports
import com.matchFit.post.entity.Status
import com.matchFit.post.entity.Town
import com.matchFit.post.repository.PostRepository
import com.matchFit.user.entity.Gender
import com.matchFit.user.entity.User
import com.matchFit.user.repository.UserRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.SpyBean
import org.springframework.data.domain.PageRequest
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.test.context.ActiveProfiles
import java.time.LocalDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@SpringBootTest
@ActiveProfiles("test")
class CacheStampedeTest {

    @Autowired lateinit var postService: PostService
    @Autowired lateinit var postRepository: PostRepository
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var redisTemplate: StringRedisTemplate

    @SpyBean lateinit var participationRepository: ParticipationRepository

    private lateinit var host: User
    private val posts = mutableListOf<Post>()

    @BeforeEach
    fun setUp() {
        participationRepository.deleteAll()
        postRepository.deleteAll()
        userRepository.deleteAll()
        posts.clear()

        val keys = redisTemplate.keys("applicants:post_*")
        if (keys.isNotEmpty()) {
            redisTemplate.delete(keys)
        }

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

        for (i in 1..5) {
            val post = Post().apply {
                title = "축구 같이 하실 분 $i"
                description = "테스트 게시글"
                this.gender = Gender.MALE
                this.sports = Sports.FOOTBALL
                cost = 0
                status = Status.OPEN
                this.town = Town.SEOUL
                maxPeople = 10
                date = LocalDateTime.now().plusDays(7L + i)
                location = "서울 잠실"
                user = host
            }
            postRepository.save(post)
            posts.add(post)

            val hostParticipation = Participation(host, post)
            hostParticipation.status = ApplicationStatus.APPROVED
            participationRepository.save(hostParticipation)
        }

        Mockito.clearInvocations(participationRepository)
    }

    @Test
    @DisplayName("캐시 스탬피드: 캐시 미스 상태에서 동시 요청 시 DB 집계 쿼리가 중복 호출됨")
    fun cacheStampedeOccursOnConcurrentRequests() {
        val threadCount = 100
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(threadCount)
        val executor = Executors.newFixedThreadPool(threadCount)
        val successCount = AtomicInteger(0)
        val failCount = AtomicInteger(0)
        val pageable = PageRequest.of(0, 10)

        repeat(threadCount) {
            executor.submit {
                try {
                    startLatch.await()
                    postService.findByFilters(
                        sports = null,
                        gender = null,
                        sortType = SortType.DATE,
                        date = null,
                        pageable = pageable,
                        userId = null
                    )
                    successCount.incrementAndGet()
                } catch (e: Exception) {
                    failCount.incrementAndGet()
                    println("Thread failed: ${e.message}")
                } finally {
                    doneLatch.countDown()
                }
            }
        }

        startLatch.countDown()
        doneLatch.await(30, TimeUnit.SECONDS)
        executor.shutdown()

        val dbCallCount = Mockito.mockingDetails(participationRepository)
            .invocations
            .count { it.method.name == "countApprovedByPostIds" }

        println("==========================================")
        println("동시 요청 스레드 수: $threadCount")
        println("성공 스레드: ${successCount.get()}")
        println("실패 스레드: ${failCount.get()}")
        println("countApprovedByPostIds 호출 횟수: $dbCallCount")
        println("스탬피드 발생 여부: ${dbCallCount > 1}")
        println("==========================================")

        assertThat(dbCallCount)
            .describedAs(
                "캐시가 비어있는 상태에서 동시 요청이 들어오면 " +
                    "여러 스레드가 DB 집계 쿼리를 중복 호출해야 함(스탬피드)"
            )
            .isGreaterThan(1)
    }
}
