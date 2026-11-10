package net.wastu.artha.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JagoScreensTest {
    private fun screen(vararg nodes: String) =
        "<?xml version='1.0' encoding='UTF-8' standalone='yes' ?><hierarchy rotation=\"0\">${nodes.joinToString("")}</hierarchy>"

    private fun node(label: String, bounds: String = "[100,200][300,400]", pkg: String = "com.jago.digitalBanking") =
        "<node text=\"\" class=\"android.widget.Button\" package=\"$pkg\" content-desc=\"$label\" bounds=\"$bounds\"/>"

    @Test fun thePinPromptIsRecognisedInEnglishAndIndonesian() {
        assertTrue(JagoScreens.hasPinPrompt(screen(node("Enter your PIN"))))
        assertTrue(JagoScreens.hasPinPrompt(screen(node("Masukkan PIN kamu"))))
        assertTrue(JagoScreens.hasPinPrompt(screen(node("Masukkan PIN Jago"))))
        assertFalse(JagoScreens.hasPinPrompt(screen(node("Welcome back"))))
        assertFalse("a label that merely mentions a pin elsewhere", JagoScreens.hasPinPrompt(screen(node("Forgot PIN?"))))
    }

    @Test fun pastThePinScreenNeedsAnActualScreenThatIsNotThePrompt() {
        assertTrue(JagoScreens.isPastPinScreen(screen(node("Home"))))
        assertFalse(JagoScreens.isPastPinScreen(screen(node("Enter your PIN"))))
        assertFalse("an empty hierarchy is a screen in transition, not an unlock", JagoScreens.isPastPinScreen("ok <hierarchy></hierarchy>"))
    }

    @Test fun usePinIsTappedBeforeAnyDismissAndConsentButtonsAreIgnored() {
        val withBoth = screen(node("Not now", "[0,0][100,100]"), node("Use PIN", "[200,400][400,600]"))
        assertEquals(300 to 500, JagoScreens.dismissTarget(withBoth))
        assertEquals(50 to 50, JagoScreens.dismissTarget(screen(node("Nanti saja", "[0,0][100,100]"))))
        assertEquals(50 to 50, JagoScreens.dismissTarget(screen(node("Lewati.", "[0,0][100,100]"))))
        assertNull(JagoScreens.dismissTarget(screen(node("OK"), node("Allow"), node("Update now"), node("Izinkan"))))
        assertNull(JagoScreens.dismissTarget(screen()))
    }

    @Test fun aSignatureKeepsTheShapeOfAScreenButMasksDigitsAndEmails() {
        val dump = screen(
            node("Verify your identity"),
            node("Call 0812 3456 7890"),
            node("someone@example.com"),
            node("Rp 1.250.000"),
        )
        val signature = JagoScreens.signature(dump)
        assertTrue(signature, "Verify your identity" in signature)
        assertTrue(signature, "Call #### #### ####" in signature)
        assertTrue(signature, "Rp #.###.###" in signature)
        assertTrue(signature, "<email>" in signature)
        assertFalse(signature, "someone" in signature || "1250" in signature || "0812" in signature)
        assertTrue("com.jago.digitalBanking" in signature)
    }

    @Test fun aSignatureIsBoundedAndAnEmptyScreenSaysSo() {
        val many = screen(*(0 until 200).map { node("Label ${('a' + it % 26)}${('a' + it / 26)} with many more words to take up space") }.toTypedArray())
        assertTrue(JagoScreens.signature(many).length <= 700)
        assertEquals("empty screen", JagoScreens.signature("ok <hierarchy></hierarchy>"))
        assertEquals("empty screen", JagoScreens.signature("not xml"))
    }

    @Test fun anExitRecordNamesTheReasonAndWhoStoppedIt() {
        val record = JagoScreens.parseExitInfo(
            """
            ACTIVITY MANAGER EXIT INFO (dumpsys activity exit-info)
            Process #0:
              ApplicationExitInfo #0:
                timestamp=2026-11-10 11:04:02.311 pid=17261 realUid=10351 packageUid=10351 definingUid=10351 user=0
                process=com.jago.digitalBanking reason=1 (SELF) subreason=0 (UNKNOWN) status=0
                importance=125 pss=0 rss=0 description=exit info state=cached
            """.trimIndent().lines(),
        )
        assertNotNull(record)
        assertEquals("SELF (code 1)", record!!.reason)
        assertEquals("exit info", record.description)
        assertEquals("2026-11-10 11:04:02", record.timestamp)
        assertEquals("SELF (code 1) \"exit info\" at 2026-11-10 11:04:02", record.toString())
    }

    @Test fun anUnfamiliarExitDumpGivesNoRecordInsteadOfAGuess() {
        assertNull(JagoScreens.parseExitInfo(emptyList()))
        assertNull(JagoScreens.parseExitInfo(listOf("No records")))
    }
}
