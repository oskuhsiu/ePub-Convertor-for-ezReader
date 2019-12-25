package osku.me.epubconverter.models

/**
 * Created by Osku on 2019-07-25.
 */
data class NovelInfo(
    val base_datafile: String,
    val chapters_count: Int,
    val datafile_count: Int,
    val novelname: String,
    val host: String,
    val novelurl: String
) {
    var chapterBriefList: Array<ChapterBrief>? = null
    var path: String? = null
    var dlContents: Int = 0
}