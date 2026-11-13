package com.sillyclient.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeepAliveNoticeTest {
    @Test
    fun anInProgressReplyTellsTheUserItSurvivesLockAndAppSwitch() {
        val session = KeepAlive.notificationText(sessionActive = true)
        val task = KeepAlive.notificationText(sessionActive = false)
        assertTrue(session.contains("锁屏"))
        assertTrue(session.contains("接收"))
        assertFalse(task.contains("锁屏"))
        assertTrue(task.contains("实例任务"))
    }
}
