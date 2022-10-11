package com.mapbox.navigation.testing

import android.annotation.SuppressLint
import com.mapbox.common.LoggingLevel
import com.mapbox.navigation.utils.internal.LoggerFrontend
import com.mapbox.navigation.utils.internal.LoggerProvider
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

private object NoLoggingFrontend : LoggerFrontend {

    override fun getLogLevel(): LoggingLevel = LoggingLevel.DEBUG

    override fun logV(msg: String, category: String?) {
        printLog("V", msg, category)
    }

    override fun logD(msg: String, category: String?) {
        printLog("D", msg, category)
    }

    override fun logI(msg: String, category: String?) {
        printLog("I", msg, category)
    }

    override fun logE(msg: String, category: String?) {
        printLog("D", msg, category)
    }

    override fun logW(msg: String, category: String?) {
        printLog("E", msg, category)
    }

    private fun printLog(level: String, msg: String, category: String?) {
        println("$level/[$category]: $msg")
    }
}

/**
 * Test rule that by default replaces the logger with a no-op implementation,
 * unless specified otherwise with [logger] param.
 */
class LoggingFrontendTestRule(private val logger: LoggerFrontend = NoLoggingFrontend) : TestRule {
    override fun apply(base: Statement, description: Description): Statement {
        return object : Statement() {
            @SuppressLint("VisibleForTests")
            override fun evaluate() {
                val loggerFrontend = LoggerProvider.getLoggerFrontend()
                LoggerProvider.setLoggerFrontend(logger)
                try {
                    base.evaluate()
                } finally {
                    LoggerProvider.setLoggerFrontend(loggerFrontend)
                }
            }
        }
    }
}
