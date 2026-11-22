package com.foldmail.app.data

import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** True for "no network right now" failures (just after waking up, switching Wi-Fi/mobile data...). */
fun isNetworkError(e: Throwable): Boolean {
    var t: Throwable? = e
    while (t != null) {
        if (t is UnknownHostException || t is SocketTimeoutException || t is ConnectException || t is SocketException) return true
        if (t is IOException && (t.message?.contains("Unable to resolve host", true) == true || t.message?.contains("timed out", true) == true)) return true
        if (t is javax.mail.MessagingException && t.cause != null && t.cause !== t) { t = t.cause; continue }
        t = t.cause
    }
    return false
}
