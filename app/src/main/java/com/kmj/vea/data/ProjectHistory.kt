package com.kmj.vea.data

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf

/**
 * In-memory edit history for the immutable project model.
 * Every committed user edit becomes one undoable snapshot.
 */
class ProjectHistory(initial: VeaProject, private val limit: Int = 100) {
    private val snapshots = mutableStateListOf(initial)
    private var positionState = mutableIntStateOf(0)

    val canUndo: Boolean get() = positionState.intValue > 0
    val canRedo: Boolean get() = positionState.intValue < snapshots.lastIndex

    fun push(project: VeaProject): VeaProject {
        if (snapshots[positionState.intValue] == project) return project
        while (snapshots.lastIndex > positionState.intValue) {
            snapshots.removeAt(snapshots.lastIndex)
        }
        snapshots.add(project)
        if (snapshots.size > limit) {
            snapshots.removeAt(0)
        }
        positionState.intValue = snapshots.lastIndex
        return project
    }

    fun undo(): VeaProject? {
        if (!canUndo) return null
        positionState.intValue -= 1
        return snapshots[positionState.intValue]
    }

    fun redo(): VeaProject? {
        if (!canRedo) return null
        positionState.intValue += 1
        return snapshots[positionState.intValue]
    }
}
