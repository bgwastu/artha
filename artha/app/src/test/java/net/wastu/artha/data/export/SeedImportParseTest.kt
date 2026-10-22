package net.wastu.artha.data.export

import net.wastu.artha.core.model.Direction
import net.wastu.artha.core.model.Provider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SeedImportParseTest {

    @Test fun parsesTheSeedFile() {
        val body = SeedImportParseTest::class.java
            .getResourceAsStream("/artha_seed.jsonl")!!.readBytes().decodeToString()
        val parsed = LedgerJsonlImport.parse(body)
        assertEquals(7, parsed.transactions.size)
        println("providers=" + parsed.transactions.map { it.provider })
        assertTrue(parsed.transactions.all {
            it.provider == Provider.JAGO || it.provider == Provider.GOJEK
        })
        assertTrue(parsed.transactions.any { it.direction == Direction.DEBIT })
        assertEquals(7, parsed.labels.size)
    }
}
