package com.jsbeth.camslow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckTest {
    private val sha = "83705e1aa2b3c4d5e6f708192a3b4c5d6e7f8091"

    @Test fun parsesReleaseBody() {
        val b = UpdateCheck.parseBody("브랜치 cam-slowdown / 커밋 $sha 빌드.\nbuild: 19 $sha\n")!!
        assertEquals(19, b.code)
        assertEquals(sha, b.sha)
        assertNull(UpdateCheck.parseBody("브랜치 cam-slowdown / 커밋 $sha 빌드.")) // 예전 본문
        assertNull(UpdateCheck.parseBody(null))
    }

    @Test fun newerRules() {
        val rel = UpdateCheck.Build(19, sha)
        assertTrue(UpdateCheck.isNewer(rel, 18, sha))
        assertFalse(UpdateCheck.isNewer(rel, 20, "ffff"))
        assertFalse(UpdateCheck.isNewer(rel, 19, sha)) // 같은 빌드
        assertTrue(UpdateCheck.isNewer(rel, 19, "0123456789abcdef0123456789abcdef01234567")) // 같은 버전, 다음 커밋
        assertFalse(UpdateCheck.isNewer(rel, 19, "")) // 로컬 빌드
    }
}
