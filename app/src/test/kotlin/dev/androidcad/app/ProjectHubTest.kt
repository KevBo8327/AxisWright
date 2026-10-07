package dev.androidcad.app

import dev.androidcad.project.ProjectInfo
import org.junit.Test
import kotlin.test.assertEquals

class ProjectHubTest {
    @Test
    fun mergedListIsNewestFirstAcrossBothKindsAndTiesSortByName() {
        val models = listOf(ProjectInfo("m1", "Platte", 300), ProjectInfo("m2", "Halter", 100))
        val scripts = listOf(ProjectInfo("s1", "Box", 200), ProjectInfo("s2", "alpha", 100))
        val merged = ProjectHub.merge(models, scripts)
        assertEquals(listOf("Platte", "Box", "alpha", "Halter"), merged.map { it.info.name })
        assertEquals(listOf(ProjectKind.MODEL, ProjectKind.SCRIPT, ProjectKind.SCRIPT, ProjectKind.MODEL), merged.map { it.kind })
    }

    @Test
    fun sameIdInBothStoresStaysTwoDistinctEntries() {
        val merged = ProjectHub.merge(listOf(ProjectInfo("x", "A", 1)), listOf(ProjectInfo("x", "B", 2)))
        assertEquals(2, merged.size)
        assertEquals(setOf(ProjectKind.MODEL, ProjectKind.SCRIPT), merged.map { it.kind }.toSet())
    }
}
