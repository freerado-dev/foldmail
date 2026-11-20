package com.foldmail.app.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class GmailParserTest {
    private fun b64(s: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray(Charsets.UTF_8))

    private fun sample(unread: Boolean = true) = JSONObject(
        """
        {
          "id": "18c3f2a1b2c3d4e5", "threadId": "18c3f2a1b2c3d4e5", "internalDate": "1763640000000",
          "labelIds": ["INBOX"${if (unread) ", \"UNREAD\"" else ""}],
          "payload": {
            "mimeType": "multipart/mixed",
            "headers": [
              {"name": "From", "value": "=?UTF-8?B?QW5uYSBOb3bDoWtvdsOh?= <anna@example.com>"},
              {"name": "To", "value": "Me <me@gmail.com>, \"Doe, John\" <john@example.com>"},
              {"name": "Subject", "value": "=?UTF-8?Q?Fakt=C3=BAra_2026?="},
              {"name": "Message-ID", "value": "<abc@mail.example.com>"},
              {"name": "In-Reply-To", "value": "<prev@mail.example.com>"},
              {"name": "References", "value": "<a@x> <prev@mail.example.com>"}
            ],
            "parts": [
              {"mimeType": "text/plain", "filename": "", "body": {"size": 5, "data": "${b64("Ahoj svet")}"}},
              {"mimeType": "text/html", "filename": "", "body": {"size": 5, "data": "${b64("<p>Ignored</p>")}"}},
              {"mimeType": "application/pdf", "filename": "faktura.pdf", "body": {"size": 1234, "attachmentId": "att1"}}
            ]
          }
        }
        """.trimIndent(),
    )

    @Test fun parsesHeadersBodyAndAttachments() {
        val p = GmailParser.parse(sample(), 2, "me@gmail.com")
        val e = p.item.email
        assertEquals("Anna Nováková <anna@example.com>", e.from)
        assertEquals("Faktúra 2026", e.subject)
        assertEquals(listOf("Me <me@gmail.com>", "\"Doe, John\" <john@example.com>"), e.to)
        assertEquals("<abc@mail.example.com>", e.messageId)
        assertEquals("<prev@mail.example.com>", e.inReplyTo)
        assertEquals(listOf("<a@x>", "<prev@mail.example.com>"), e.references)
        assertEquals(1763640000000L, e.sentAtMillis)
        assertEquals(2L, e.accountId)
        assertFalse(e.isRead)
        assertFalse(e.isOutgoing)
        assertEquals("Ahoj svet", p.item.body)
        assertEquals(1, p.item.attachments.size)
        assertEquals("faktura.pdf", p.item.attachments[0].name)
        assertTrue(p.item.attachments[0].isPdf)
        assertEquals("18c3f2a1b2c3d4e5", p.gmailId)
    }

    @Test fun readWhenNoUnreadLabel() {
        assertTrue(GmailParser.parse(sample(unread = false), 1, "me@gmail.com").item.email.isRead)
    }

    @Test fun outgoingDetectedFromAccountAddress() {
        val j = JSONObject(
            """{"id":"1","threadId":"1","internalDate":"1","labelIds":["SENT"],
               "payload":{"mimeType":"text/plain","headers":[{"name":"From","value":"Rado <ME@gmail.com>"},{"name":"To","value":"a@b.sk"}],
               "body":{"size":1,"data":"${b64("x")}"}}}""",
        )
        assertTrue(GmailParser.parse(j, 1, "me@gmail.com").item.email.isOutgoing)
    }

    @Test fun htmlOnlyBodyIsConvertedToText() {
        val j = JSONObject(
            """{"id":"2","threadId":"2","internalDate":"1","labelIds":[],
               "payload":{"mimeType":"text/html","headers":[],
               "body":{"size":1,"data":"${b64("<style>p{}</style><p>Ahoj&nbsp;svet</p><p>R&amp;D</p>")}"}}}""",
        )
        assertEquals("Ahoj svet\nR&D", GmailParser.parse(j, 1, "me@gmail.com").item.body)
    }

    @Test fun splitsAddressesOnTopLevelCommasOnly() {
        assertEquals(
            listOf("\"A, B\" <a@x.sk>", "c@y.sk"),
            GmailParser.splitAddresses("\"A, B\" <a@x.sk>, c@y.sk"),
        )
    }

    @Test fun mimeBuilderProducesDecodableMessage() {
        val raw = MimeBuilder.build(
            from = "me@gmail.com", to = listOf("a@b.sk"), subject = "Faktúra", body = "Ahoj ľudia",
            inReplyTo = "<x@y>", references = listOf("<x@y>"),
            parts = listOf(MimeBuilder.Part("príloha.txt", "text/plain", "data".toByteArray())),
            boundary = "BOUND",
        )
        val text = String(Base64.getUrlDecoder().decode(raw), Charsets.UTF_8)
        assertTrue(text.contains("From: me@gmail.com"))
        assertTrue(text.contains("Subject: =?UTF-8?B?"))
        assertTrue(text.contains("In-Reply-To: <x@y>"))
        assertTrue(text.contains("multipart/mixed; boundary=\"BOUND\""))
        assertTrue(text.trimEnd().endsWith("--BOUND--"))
        assertEquals("Faktúra", GmailParser.decodeHeader(Regex("Subject: (.*)\r\n").find(text)!!.groupValues[1]))
    }
}
