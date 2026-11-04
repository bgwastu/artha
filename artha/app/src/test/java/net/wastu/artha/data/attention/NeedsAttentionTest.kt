package net.wastu.artha.data.attention

import net.wastu.artha.core.model.Provider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NeedsAttentionTest {
    private val all = Provider.entries.toSet()

    @Test
    fun missingJagoPinNeedsAttention() {
        val items = evaluateAttention(AttentionInputs(importEnabled = all, jagoPinSet = false))
        assertEquals(listOf("jago-pin"), items.map { it.id })
        assertEquals(AttentionAction.SET_JAGO_PIN, items.single().action)
        assertEquals(items, items.forSource(Provider.JAGO))
        assertTrue(items.forSource(Provider.GRAB).isEmpty())
    }

    @Test
    fun setPinOrPausedJagoNeedsNothing() {
        assertTrue(evaluateAttention(AttentionInputs(importEnabled = all, jagoPinSet = true)).isEmpty())
        assertTrue(evaluateAttention(AttentionInputs(importEnabled = all - Provider.JAGO, jagoPinSet = false)).isEmpty())
    }

    @Test
    fun labelNamesTheSource() {
        val item = evaluateAttention(AttentionInputs(importEnabled = all, jagoPinSet = false)).single()
        assertEquals("Bank Jago: background PIN not set", item.label)
    }

    @Test
    fun wrongJagoPinNeedsAttention() {
        val inputs = AttentionInputs(
            importEnabled = all,
            jagoPinSet = true,
            jagoPinCheck = net.wastu.artha.data.providers.JagoPinCheck(status = net.wastu.artha.data.providers.JagoPinStatus.WRONG)
        )
        val items = evaluateAttention(inputs)
        assertEquals(listOf("jago-wrong-pin"), items.map { it.id })
        assertEquals("Bank Jago: incorrect background PIN", items.single().label)
    }
}
