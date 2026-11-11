package io.github.linuxkernel44.opentypebeat

/** Quick-access beat styles shown as chips under the toolbar. */
object BeatGenres {
    val all = listOf(
        "Trap",
        "Drill",
        "Boom Bap",
        "Lo-fi",
        "R&B",
        "Afrobeat",
        "Reggaeton",
        "Dark",
        "Melodic",
        "Hard",
        "Sad",
        "Pop",
        "Jersey Club",
        "Phonk",
        "Old School",
    )

    fun searchUrl(genre: String, music: Boolean): String {
        val q = java.net.URLEncoder.encode("$genre type beat", "UTF-8")
        return if (music) "https://music.youtube.com/search?q=$q"
        else "https://www.youtube.com/results?search_query=$q"
    }
}
