package osku.me.epubconverter

import android.Manifest
import android.os.Bundle
import android.os.Environment
import android.text.Editable
import android.text.Html
import android.text.Html.FROM_HTML_MODE_LEGACY
import android.text.TextWatcher
import android.view.*
import android.widget.CheckBox
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.Gson
import com.obsez.android.lib.filechooser.ChooserDialog
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.android.synthetic.main.layout_novel_list_item.view.*
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import nl.siegmann.epublib.domain.Book
import nl.siegmann.epublib.domain.Resource
import nl.siegmann.epublib.epub.EpubWriter
import org.apache.commons.io.IOUtils
import osku.me.epubconverter.models.Chapter
import osku.me.epubconverter.models.ChapterBrief
import osku.me.epubconverter.models.NovelInfo
import permissions.dispatcher.NeedsPermission
import permissions.dispatcher.RuntimePermissions
import java.io.*
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.nio.file.Paths
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException

@RuntimePermissions
class MainActivity : AppCompatActivity() {

    val novelListAll = mutableListOf<NovelInfo>()
    val novelListFiltered = mutableListOf<NovelInfo>()
//    val selectedNovels: MutableList<Boolean> = mutableListOf<Boolean>()


    val bom = byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte())
    val header = bom + """<?xml version='1.0' encoding='utf-8'?>
                        <html xmlns="http://www.w3.org/1999/xhtml">
                        <body>""".toByteArray()
    val footer = """</body></html>""".toByteArray()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        rvNovelList.adapter = novelListAdaper
        rvNovelList.layoutManager = LinearLayoutManager(this@MainActivity)

        etFilter.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                filterList()
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
            }

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
            }

        })
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.open_dir, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val id = item.itemId

        if (id == R.id.action_choose_directory) {
            if (progressbar.visibility == View.INVISIBLE)
                showChooserWithPermissionCheck()
            return true
        }

        if (id == R.id.action_output) {
            if (progressbar.visibility == View.INVISIBLE)
                GlobalScope.async {
                    //                    buildEPubWithPermissionCheck()

                    for (index in 0 until novelListFiltered.size)
                        if (novelListFiltered[index].selected == true)
                            buildEPubWithPermissionCheck(novelListFiltered[index])
//                    selectedNovels.map {
//                        if (it == true)
//                            buildEPubWithPermissionCheck(novelList[selectedNovels.indexOf(it)])
//                    }
                }
            return true
        }

        if (id == R.id.action_output_txt) {
            if (progressbar.visibility == View.INVISIBLE)
                GlobalScope.async {
                    //                    buildEPubWithPermissionCheck()

                    for (index in 0 until novelListFiltered.size)
                        if (novelListFiltered[index].selected == true)
                            buildTxtFileWithPermissionCheck(novelListFiltered[index])
//                    selectedNovels.map {
//                        if (it == true)
//                            buildEPubWithPermissionCheck(novelList[selectedNovels.indexOf(it)])
//                    }
                }
            return true
        }

        return super.onOptionsItemSelected(item)
    }

    @NeedsPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    fun showChooser() {

        novelListAll.clear()
        novelListFiltered.clear()

        ChooserDialog(this@MainActivity)
            .withFilter(true, false, FileFilter {
                val path = it.name
                if (path != null) {
                    if (path!!.contains("easy"))
                        true
//                    else if (path!!.contains(etFilter.text.toString()))
//                        true
                    else false
                } else
                    false
            })
//            .withStartFile(startingDir)
            .titleFollowsDir(true)
            .displayPath(true)
            .withChosenListener(object : ChooserDialog.Result {
                override fun onChoosePath(path: String, pathFile: File) {
                    window.decorView.post { loadNovelInfoList(pathFile) }
                }
            })
            .build()
            .show()
    }

    private fun loadNovelInfoList(pathFile: File) {
        pathFile.list().map {
            it?.let {
                val file = File(pathFile.absolutePath, it)
                if (file.isDirectory) {
                    val ni = loadNovelInfo(file.absolutePath)
                    ni?.let { novelListAll.add(ni) }
                }
            }
        }

        filterList()
    }

    private fun loadNovelInfo(path: String): NovelInfo? {
        try {
            val novel_info_path = Paths.get(path, "chapters")
            val novel_info_content =
                IOUtils.toString(novel_info_path.toUri(), StandardCharsets.UTF_8)
            val novel_info = Gson().fromJson(novel_info_content, NovelInfo::class.java)

            val chapter_brief_list_path = Paths.get(path, novel_info.base_datafile)
            val chapter_brief_list_content =
                IOUtils.toString(chapter_brief_list_path.toUri(), StandardCharsets.UTF_8)
            val chapter_brief_list =
                Gson().fromJson(chapter_brief_list_content, Array<ChapterBrief>::class.java)

            val content_path = Paths.get(path, "dl_contents")
            val dir_file_list = content_path.toFile().list()
            val files = dir_file_list.size

            novel_info.chapterBriefList = chapter_brief_list
            novel_info.path = path
            novel_info.dlContents = files

            return novel_info
        } catch (all: java.lang.Exception) {
            all.printStackTrace()
//            txvOutput.text = all.toString()
        }

        return null
    }

    @NeedsPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    fun buildEPub(novelInfo: NovelInfo) {

        var novelPath: String? = null
        var chapterBriefList: Array<ChapterBrief>? = null

        chapterBriefList = novelInfo!!.chapterBriefList
        novelPath = novelInfo!!.path

        if (novelInfo == null || chapterBriefList == null || novelPath == null)
            return

        var output_path = Paths.get(
            Environment.getExternalStorageDirectory().absolutePath,
            Environment.DIRECTORY_DOWNLOADS,
            "EPubConverter"
        )

        output_path.toFile().mkdirs()
        var filename: String = novelInfo!!.novelname

        runOnUiThread { progressbar.visibility = View.VISIBLE }
        try {
            var book = Book()
            var metadata = book.getMetadata()


//            metadata.addAuthor(Author("Joe", "Tester"))
//            book.setCoverImage(
//                getResource("/book1/test_cover.png", "cover.png")
//            )

            var count: Int = 1
            var chapter_count: Int = 1
            val batch_size = 50000;
            for (chapter_brief in chapterBriefList!!) {

                if (chapter_brief.url == null)
                    continue

                if (chapter_count > batch_size && chapterBriefList!!.size - count > 200) {
                    filename =
                        String.format("%s_part%d", novelInfo!!.novelname, count / batch_size)
                    metadata.addTitle(filename)
                    val full_output_path = Paths.get(output_path.toString(), filename + ".epub")

                    val epubWriter = EpubWriter()
                    epubWriter.write(book, FileOutputStream(full_output_path.toFile()))

                    book = Book()
                    metadata = book.getMetadata()
                    filename =
                        String.format(
                            "%s_part%d",
                            novelInfo!!.novelname,
                            (count / batch_size) + 1
                        )
                    chapter_count = 0
                }

                if (chapter_brief.url.length == 0) {
                    book.addSection(
                        chapter_brief.name,
                        Resource(" ".toByteArray(), "chapter$count.html")
                    )
                } else {
//                    val ref_index = chapter_brief.url.indexOf(novelInfo.host) + novelInfo.host.length
//                    val ref = chapter_brief.url.substring(ref_index)
                    val ref = chapter_brief.url.substring(chapter_brief.url.indexOf("/", 8))
                    val content_filename = getMD5String(ref)
                    val content_path = Paths.get(novelPath, "dl_contents", content_filename)
                    try {
                        val content = IOUtils.toString(content_path.toUri(), StandardCharsets.UTF_8)
                        val chapter = Gson().fromJson(content, Chapter::class.java)
                        val cleaned =
                            Html.fromHtml(chapter.content, FROM_HTML_MODE_LEGACY).toString()
                        val final = cleaned.replace("\n", "<br/>")
                        val finalBytes = final.toByteArray()

                        val outputBytes = header + finalBytes + footer
                        book.addSection(
                            chapter_brief.name,
                            Resource(outputBytes, "chapter$count.html")
                        )
                    } catch (io: IOException) {
                        io.printStackTrace()
                        runOnUiThread {
                            txvOutput.text =
                                txvOutput.text.toString() + "\n" + "failed to find chapter: ${chapter_brief.name}"
                            txvOutput.text = txvOutput.text.toString() + "\n" + io.toString()
                        }
                    } catch (others: java.lang.Exception) {
                        others.printStackTrace()
                        runOnUiThread {
                            txvOutput.text =
                                txvOutput.text.toString() + "\n" + others.toString()
                        }
                    }
                }
                count++
                chapter_count++
            }

            metadata.addTitle(filename)
            val full_output_path = Paths.get(output_path.toString(), filename + ".epub")

            val epubWriter = EpubWriter()
            epubWriter.write(book, FileOutputStream(full_output_path.toFile()))
            runOnUiThread {
                txvOutput.text = txvOutput.text.toString() + "\n" + filename + "done..."
            }
        } catch (all: Exception) {
            all.printStackTrace()
            runOnUiThread { txvOutput.text = txvOutput.text.toString() + "\n" + all.toString() }
        } finally {
            runOnUiThread { progressbar.visibility = View.INVISIBLE }
        }
    }

    @NeedsPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    fun buildTxtFile(novelInfo: NovelInfo) {
        var novelPath: String? = null
        var chapterBriefList: Array<ChapterBrief>? = null

        chapterBriefList = novelInfo!!.chapterBriefList
        novelPath = novelInfo!!.path

        if (novelInfo == null || chapterBriefList == null || novelPath == null)
            return

        var output_path = Paths.get(
            Environment.getExternalStorageDirectory().absolutePath,
            Environment.DIRECTORY_DOWNLOADS,
            "EPubConverter"
        )

        output_path.toFile().mkdirs()
        var filename: String = novelInfo!!.novelname

        runOnUiThread { progressbar.visibility = View.VISIBLE }

        val full_output_path = Paths.get(output_path.toString(), filename + ".txt").toFile()
        try {
            val writer = full_output_path.bufferedWriter()

            var count: Int = 1
            var chapter_count: Int = 1
            for (chapter_brief in chapterBriefList!!) {

                if (chapter_brief.url == null)
                    continue

                if (chapter_brief.url.length == 0) {
                    continue
                } else {
                    val ref = chapter_brief.url.substring(chapter_brief.url.indexOf("/", 8))
                    val content_filename = getMD5String(ref)
                    val content_path = Paths.get(novelPath, "dl_contents", content_filename)
                    try {
                        val content = IOUtils.toString(content_path.toUri(), StandardCharsets.UTF_8)
                        val chapter = Gson().fromJson(content, Chapter::class.java)
                        val cleaned =
                            Html.fromHtml(chapter.content, FROM_HTML_MODE_LEGACY).toString().trim()
                                .replace("[\n]{2,}", "\n")
                        val finalBytes = cleaned.toCharArray()//.toByteArray()

                        val outputBytes = finalBytes
                        //write chapter title
                        writer.write(chapter_brief.name)
                        writer.newLine()
                        //write chapter content
                        writer.write(outputBytes, 0, outputBytes.size)
                        writer.newLine()
                        //write line break
                        writer.newLine()

                    } catch (io: IOException) {
                        io.printStackTrace()
                        runOnUiThread {
                            txvOutput.text =
                                txvOutput.text.toString() + "\n" + "failed to find chapter: ${chapter_brief.name}"
                            txvOutput.text = txvOutput.text.toString() + "\n" + io.toString()
                        }
                    } catch (others: java.lang.Exception) {
                        others.printStackTrace()
                        runOnUiThread {
                            txvOutput.text =
                                txvOutput.text.toString() + "\n" + others.toString()
                        }
                    }
                }
                count++
                chapter_count++
            }

            try {
                writer.close()
            } catch (ignored: IOException) { //empty
            }

            runOnUiThread {
                txvOutput.text = txvOutput.text.toString() + "\n" + filename + "done..."
            }
        } catch (all: Exception) {
            all.printStackTrace()
            runOnUiThread { txvOutput.text = txvOutput.text.toString() + "\n" + all.toString() }
        } finally {
            runOnUiThread { progressbar.visibility = View.INVISIBLE }
        }
    }

    private fun getMD5String(str: String): String? {

        var ret: String? = null
        try {
            val sb = StringBuilder()
            val instance = MessageDigest.getInstance("MD5")
            instance.update(str.toByteArray())
            sb.append(BigInteger(instance.digest()).abs().toString(36))
            ret = sb.toString()
        } catch (e: NoSuchAlgorithmException) {
            e.printStackTrace()
        }

        return ret
    }

    private fun filterList() {

        novelListFiltered.clear()
        val toFilter = etFilter.text.toString()
        for (novel in novelListAll)
            if (novel.novelname.contains(toFilter))
                novelListFiltered.add(novel)
        novelListAdaper.notifyDataSetChanged()
    }


    val novelListAdaper = object : RecyclerView.Adapter<ItemHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ItemHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.layout_novel_list_item, parent, false)

            val viewHolder = ItemHolder(view)
            return viewHolder
        }

        override fun getItemCount(): Int = novelListFiltered.size

        override fun onBindViewHolder(holder: ItemHolder, position: Int) {

            val novelInfo = novelListFiltered[position]
            holder.cbSelection.isChecked = novelListFiltered[position].selected
            holder.txvTitle.text = novelInfo.novelname
            holder.txvTotal.text = "" + novelInfo.chapters_count
            holder.txvDL.text = "" + novelInfo.dlContents

            holder.itemView.setOnClickListener {
                novelListFiltered[position].selected = !novelListFiltered[position].selected
                notifyDataSetChanged()
            }
        }
    }

    class ItemHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        var cbSelection: CheckBox = itemView.cbSelection
        var txvTitle: TextView = itemView.txvTitle
        var txvTotal: TextView = itemView.txvTotal
        var txvDL: TextView = itemView.txvDL
    }
}
