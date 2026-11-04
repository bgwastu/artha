package net.wastu.artha.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JagoScreenSyncTest {

    @Test
    fun parseDigitCentersExtractsCoordinatesFromHierarchyXml() {
        val xml = """
            <?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
            <hierarchy rotation="0">
                <node text="" class="android.view.View" content-desc="Enter your PIN" bounds="[368,532][712,595]"/>
                <node text="" class="android.widget.Button" content-desc="1" bounds="[134,764][353,983]"/>
                <node text="" class="android.widget.Button" content-desc="2" bounds="[430,764][650,983]"/>
                <node text="" class="android.widget.Button" content-desc="3" bounds="[727,764][946,983]"/>
                <node text="" class="android.widget.Button" content-desc="4" bounds="[134,1027][353,1246]"/>
                <node text="" class="android.widget.Button" content-desc="5" bounds="[430,1027][650,1246]"/>
                <node text="" class="android.widget.Button" content-desc="6" bounds="[727,1027][946,1246]"/>
                <node text="" class="android.widget.Button" content-desc="7" bounds="[134,1290][353,1509]"/>
                <node text="" class="android.widget.Button" content-desc="8" bounds="[430,1290][650,1509]"/>
                <node text="" class="android.widget.Button" content-desc="9" bounds="[727,1290][946,1509]"/>
                <node text="" class="android.widget.Button" content-desc="0" bounds="[430,1553][650,1772]"/>
            </hierarchy>
        """.trimIndent()

        val centers = JagoScreenSync.parseDigitCenters(xml)
        assertEquals(10, centers.size)
        assertEquals(Pair(243, 873), centers['1'])
        assertEquals(Pair(540, 873), centers['2'])
        assertEquals(Pair(836, 873), centers['3'])
        assertEquals(Pair(243, 1136), centers['4'])
        assertEquals(Pair(540, 1136), centers['5'])
        assertEquals(Pair(836, 1136), centers['6'])
        assertEquals(Pair(243, 1399), centers['7'])
        assertEquals(Pair(540, 1399), centers['8'])
        assertEquals(Pair(836, 1399), centers['9'])
        assertEquals(Pair(540, 1662), centers['0'])
    }

    @Test
    fun parseDigitCentersHandlesOkPrefixAndEmptyHierarchy() {
        val empty = JagoScreenSync.parseDigitCenters("ok <hierarchy></hierarchy>")
        assertTrue(empty.isEmpty())

        val malformed = JagoScreenSync.parseDigitCenters("not xml at all")
        assertTrue(malformed.isEmpty())
    }
}
