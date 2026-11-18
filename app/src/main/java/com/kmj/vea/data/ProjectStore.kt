package com.kmj.vea.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object ProjectStore {
    private const val FILE_NAME = "vea-project.json"

    fun save(context: Context, project: VeaProject): Boolean {
        return runCatching {
            val root = JSONObject()
                .put("id", project.id)
                .put("name", project.name)
                .put("bpm", project.bpm)
                .put("numerator", project.numerator)
                .put("denominator", project.denominator)

            val tracks = JSONArray()
            project.tracks.forEach { track ->
                val trackJson = JSONObject()
                    .put("id", track.id)
                    .put("name", track.name)
                    .put("kind", track.kind)
                    .put("language", track.language)
                    .put("color", track.color)

                val clips = JSONArray()
                track.clips.forEach { clip ->
                    val clipJson = JSONObject()
                        .put("id", clip.id)
                        .put("name", clip.name)
                        .put("startBar", clip.startBar)
                        .put("lengthBars", clip.lengthBars)

                    val notes = JSONArray()
                    clip.notes.forEach { note ->
                        val noteJson = JSONObject()
                            .put("id", note.id)
                            .put("startTick", note.startTick)
                            .put("lengthTick", note.lengthTick)
                            .put("pitch", note.pitch)
                            .put("lyric", note.lyric)
                            .put("phoneme", note.phoneme)

                        val points = JSONArray()
                        note.pitchPoints.forEach { point ->
                            points.put(JSONObject().put("tick", point.tick).put("cents", point.cents))
                        }
                        noteJson.put("pitchPoints", points)
                        notes.put(noteJson)
                    }
                    clipJson.put("notes", notes)
                    clips.put(clipJson)
                }
                trackJson.put("clips", clips)
                tracks.put(trackJson)
            }
            root.put("tracks", tracks)
            File(context.filesDir, FILE_NAME).writeText(root.toString(2), Charsets.UTF_8)
        }.isSuccess
    }

    fun load(context: Context): VeaProject? {
        return runCatching {
            val file = File(context.filesDir, FILE_NAME)
            if (!file.exists()) return null

            val root = JSONObject(file.readText(Charsets.UTF_8))
            val tracksJson = root.optJSONArray("tracks") ?: JSONArray()
            val tracks = buildList {
                for (trackIndex in 0 until tracksJson.length()) {
                    val trackJson = tracksJson.getJSONObject(trackIndex)
                    val clipsJson = trackJson.optJSONArray("clips") ?: JSONArray()
                    val clips = buildList {
                        for (clipIndex in 0 until clipsJson.length()) {
                            val clipJson = clipsJson.getJSONObject(clipIndex)
                            val notesJson = clipJson.optJSONArray("notes") ?: JSONArray()
                            val notes = buildList {
                                for (noteIndex in 0 until notesJson.length()) {
                                    val noteJson = notesJson.getJSONObject(noteIndex)
                                    val pointsJson = noteJson.optJSONArray("pitchPoints") ?: JSONArray()
                                    val points = buildList {
                                        for (pointIndex in 0 until pointsJson.length()) {
                                            val pointJson = pointsJson.getJSONObject(pointIndex)
                                            add(PitchPoint(
                                                tick = pointJson.optInt("tick"),
                                                cents = pointJson.optInt("cents")
                                            ))
                                        }
                                    }
                                    add(VeaNote(
                                        id = noteJson.optString("id"),
                                        startTick = noteJson.optInt("startTick"),
                                        lengthTick = noteJson.optInt("lengthTick", 480),
                                        pitch = noteJson.optInt("pitch", 60),
                                        lyric = noteJson.optString("lyric", "あ"),
                                        phoneme = noteJson.optString("phoneme", "a"),
                                        pitchPoints = points
                                    ))
                                }
                            }
                            add(VeaClip(
                                id = clipJson.optString("id"),
                                name = clipJson.optString("name", "Clip"),
                                startBar = clipJson.optInt("startBar", 1),
                                lengthBars = clipJson.optInt("lengthBars", 4),
                                notes = sanitizeMonophonicNotes(notes)
                            ))
                        }
                    }
                    add(VeaTrack(
                        id = trackJson.optString("id"),
                        name = trackJson.optString("name", "Track"),
                        kind = trackJson.optString("kind", "VOCAL_AI"),
                        language = trackJson.optString("language", "jpn"),
                        color = trackJson.optLong("color", 0xFF8DBF4A),
                        clips = clips
                    ))
                }
            }
            val loaded = VeaProject(
                id = root.optString("id"),
                name = root.optString("name", "Untitled Project"),
                bpm = root.optInt("bpm", 120),
                numerator = root.optInt("numerator", 4),
                denominator = root.optInt("denominator", 4),
                tracks = tracks
            )
            val oldPrototypeTrackNames = listOf("Main01", "Cho01", "Cho02", "Cho03", "MONO", "STEREO")
            if (loaded.name == "The voice of beginning" &&
                loaded.tracks.map { it.name } == oldPrototypeTrackNames
            ) {
                VeaProject().also { emptyProject -> save(context, emptyProject) }
            } else {
                loaded
            }
        }.getOrNull()
    }
}
