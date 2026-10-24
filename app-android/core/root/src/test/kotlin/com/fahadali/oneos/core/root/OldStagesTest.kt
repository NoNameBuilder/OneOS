package com.fahadali.oneos.core.root

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OldStagesTest {
    @Test
    fun parsesOldListResults() {
        val rows = OldStages.parse(
            listOf(
                "old oneos.old-1792758413 10893312 coloros17-findn6 1 0",
                "old oneos.old-1792000000-77 4 unknown 0 1",
                "old oneos.trash-1792000200 12 unknown 0 0",
                // Its name up to 0.3.6, not renamed yet (busy): listed with the others, by time.
                "old lineage_stage.old-1792500000 9 coloros17-findx9u 1 1",
                "aside /data/local/tmp/oneos.old-1",
                "old ../etc 4 unknown 0 0",
                "old oneos.old-1 -4 unknown 0 0",
                "old oneos.old-2 4 Bad_Id 2 0",
                "old oneos.new-3 4 unknown 0 0",
                "old lineage_stage 4 unknown 0 0",
            ),
        )
        assertEquals(
            listOf("oneos.old-1792000000-77", "lineage_stage.old-1792500000", "oneos.old-1792758413", "oneos.trash-1792000200"),
            rows.map { it.name },
        )
        assertEquals(1792500000L, rows[1].setAsideEpoch)
        assertFalse(rows[1].isTrash)
        val n6 = rows.single { it.name == "oneos.old-1792758413" }
        assertEquals("coloros17-findn6", n6.imageId)
        assertTrue(n6.hasData)
        assertFalse(n6.busy)
        assertEquals(1792758413L, n6.setAsideEpoch)
        val other = rows.first()
        assertNull(other.imageId)
        assertTrue(other.busy)
        assertEquals(1792000000L, other.setAsideEpoch)
        assertTrue(rows.last().isTrash)
    }

    @Test
    fun readsTheFreedSize() {
        val r = listOf("purged oneos.old-1792758413 10893312")
        assertEquals(10893312L, OldStages.freedKib(r, "oneos.old-1792758413"))
        assertNull(OldStages.freedKib(r, "oneos.old-1"))
        assertTrue(OldStage("lineage_stage.trash-1", 1, null, false, false).isTrash)
    }
}
