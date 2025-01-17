package eu.kanade.tachiyomi.source.online.all

import android.net.Uri
import android.os.Build
import android.util.Log
import app.cash.quickjs.QuickJs
import com.github.salomonbrys.kotson.array
import com.github.salomonbrys.kotson.get
import com.github.salomonbrys.kotson.string
import com.google.gson.JsonParser
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.source.online.LewdSource
import eu.kanade.tachiyomi.source.online.UrlImportableSource
import eu.kanade.tachiyomi.util.asJsoup
import exh.HITOMI_SOURCE_ID
import exh.hitomi.HitomiNozomi
import exh.metadata.metadata.HitomiSearchMetadata
import exh.metadata.metadata.HitomiSearchMetadata.Companion.BASE_URL
import exh.metadata.metadata.HitomiSearchMetadata.Companion.LTN_BASE_URL
import exh.metadata.metadata.HitomiSearchMetadata.Companion.TAG_TYPE_DEFAULT
import exh.metadata.metadata.base.RaisedSearchMetadata.Companion.TAG_TYPE_VIRTUAL
import exh.metadata.metadata.base.RaisedTag
import exh.util.urlImportFetchSearchManga
import java.text.SimpleDateFormat
import java.util.Locale
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.vepta.vdm.ByteCursor
import rx.Observable
import rx.Single
import rx.schedulers.Schedulers
import uy.kohesive.injekt.injectLazy

/**
 * Man, I hate this source :(
 */
class Hitomi : HttpSource(), LewdSource<HitomiSearchMetadata, Document>, UrlImportableSource {
    override fun headersBuilder(): Headers.Builder = super.headersBuilder().add("Referer", "$BASE_URL/")

    private val prefs: PreferencesHelper by injectLazy()

    override val id = HITOMI_SOURCE_ID

    /**
     * Whether the source has support for latest updates.
     */
    override val supportsLatest = true
    /**
     * Name of the source.
     */
    override val name = "hitomi.la"
    /**
     * The class of the metadata used by this source
     */
    override val metaClass = HitomiSearchMetadata::class

    private var cachedTagIndexVersion: Long? = null
    private var tagIndexVersionCacheTime: Long = 0
    private fun tagIndexVersion(): Single<Long> {
        val sCachedTagIndexVersion = cachedTagIndexVersion
        return if (sCachedTagIndexVersion == null ||
            tagIndexVersionCacheTime + INDEX_VERSION_CACHE_TIME_MS < System.currentTimeMillis()
        ) {
            HitomiNozomi.getIndexVersion(client, "tagindex").subscribeOn(Schedulers.io()).doOnNext {
                cachedTagIndexVersion = it
                tagIndexVersionCacheTime = System.currentTimeMillis()
            }.toSingle()
        } else {
            Single.just(sCachedTagIndexVersion)
        }
    }

    private var cachedGalleryIndexVersion: Long? = null
    private var galleryIndexVersionCacheTime: Long = 0
    private fun galleryIndexVersion(): Single<Long> {
        val sCachedGalleryIndexVersion = cachedGalleryIndexVersion
        return if (sCachedGalleryIndexVersion == null ||
            galleryIndexVersionCacheTime + INDEX_VERSION_CACHE_TIME_MS < System.currentTimeMillis()
        ) {
            HitomiNozomi.getIndexVersion(client, "galleriesindex").subscribeOn(Schedulers.io()).doOnNext {
                cachedGalleryIndexVersion = it
                galleryIndexVersionCacheTime = System.currentTimeMillis()
            }.toSingle()
        } else {
            Single.just(sCachedGalleryIndexVersion)
        }
    }

    /**
     * Parse the supplied input into the supplied metadata object
     */
    override fun parseIntoMetadata(metadata: HitomiSearchMetadata, input: Document) {
        with(metadata) {
            url = input.location()

            tags.clear()

            thumbnailUrl = "https:" + input.selectFirst("source")!!.attr("data-srcset").substringBefore(' ')

            val galleryElement = input.selectFirst("div")

            title = galleryElement!!.selectFirst("h1")!!.text()
            artists = galleryElement!!.select(".artist-list a").map { it.text() }
            tags += artists.map { RaisedTag("artist", it, TAG_TYPE_VIRTUAL) }

            input.select(".dj-desc tr").forEach {
                val content = it.child(1)
                when (it.child(0).text().toLowerCase()) {
                    "group" -> {
                        group = content.text()
                        tags += RaisedTag("group", group!!, TAG_TYPE_VIRTUAL)
                    }
                    "type" -> {
                        genre = content.text()
                        tags += RaisedTag("type", genre!!, TAG_TYPE_VIRTUAL)
                    }
                    "series" -> {
                        series = content.select("a").map { it.text() }
                        tags += series.map {
                            RaisedTag("series", it, TAG_TYPE_VIRTUAL)
                        }
                    }
                    "language" -> {
                        language = content.selectFirst("a")?.attr("href")?.split('-')?.get(1)
                        language?.let {
                            tags += RaisedTag("language", it.substringBefore("."), TAG_TYPE_VIRTUAL)
                        }
                    }
                    "characters" -> {
                        characters = content.select("a").map { it.text() }
                        tags += characters.map { RaisedTag("character", it, TAG_TYPE_DEFAULT) }
                    }
                    "tags" -> {
                        tags += content.select("a").map {
                            val ns = if (it.attr("href").startsWith("/tag/male")) "male"
                            else if (it.attr("href").startsWith("/tag/female")) "female"
                            else "misc"
                            RaisedTag(ns, it.text().dropLast(if (ns == "misc") 0 else 2), TAG_TYPE_DEFAULT)
                        }
                    }
                }
            }

            uploadDate = try {
                DATE_FORMAT.parse(input.selectFirst(".date")!!.text())!!.time
            } catch (e: Exception) {
                null
            }
        }
    }

    override val lang = "all"

    /**
     * Base url of the website without the trailing slash, like: http://mysite.com
     */
    override val baseUrl = BASE_URL

    /**
     * Returns the request for the popular manga given the page.
     *
     * @param page the page number to retrieve.
     */
    override fun popularMangaRequest(page: Int) = HitomiNozomi.rangedGet(
        "$LTN_BASE_URL/popular-all.nozomi",
        100L * (page - 1),
        99L + 100 * (page - 1)
    )

    /**
     * Parses the response from the site and returns a [MangasPage] object.
     *
     * @param response the response from the site.
     */
    override fun popularMangaParse(response: Response) = throw UnsupportedOperationException()

    /**
     * Returns the request for the search manga given the page.
     *
     * @param page the page number to retrieve.
     * @param query the search query.
     * @param filters the list of filters to apply.
     */
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList) = throw UnsupportedOperationException()

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        return urlImportFetchSearchManga(query) {
            val splitQuery = query.split(" ")

            val positive = splitQuery.filter { !it.startsWith('-') }.toMutableList()
            val negative = (splitQuery - positive).map { it.removePrefix("-") }

            // TODO Cache the results coming out of HitomiNozomi
            val hn = Single.zip(tagIndexVersion(), galleryIndexVersion()) { tv, gv -> tv to gv }
                .map { HitomiNozomi(client, it.first, it.second) }

            var base = if (positive.isEmpty()) {
                hn.flatMap { n -> n.getGalleryIdsFromNozomi(null, "index", "all").map { n to it.toSet() } }
            } else {
                val q = positive.removeAt(0)
                hn.flatMap { n -> n.getGalleryIdsForQuery(q).map { n to it.toSet() } }
            }

            base = positive.fold(base) { acc, q ->
                acc.flatMap { (nozomi, mangas) ->
                    nozomi.getGalleryIdsForQuery(q).map {
                        nozomi to mangas.intersect(it)
                    }
                }
            }

            base = negative.fold(base) { acc, q ->
                acc.flatMap { (nozomi, mangas) ->
                    nozomi.getGalleryIdsForQuery(q).map {
                        nozomi to (mangas - it)
                    }
                }
            }

            base.flatMap { (_, ids) ->
                val chunks = ids.chunked(PAGE_SIZE)

                nozomiIdsToMangas(chunks[page - 1]).map { mangas ->
                    MangasPage(mangas, page < chunks.size)
                }
            }.toObservable()
        }
    }

    /**
     * Parses the response from the site and returns a [MangasPage] object.
     *
     * @param response the response from the site.
     */
    override fun searchMangaParse(response: Response) = throw UnsupportedOperationException()

    /**
     * Returns the request for latest manga given the page.
     *
     * @param page the page number to retrieve.
     */
    override fun latestUpdatesRequest(page: Int) = HitomiNozomi.rangedGet(
        "$LTN_BASE_URL/index-all.nozomi",
        100L * (page - 1),
        99L + 100 * (page - 1)
    )

    /**
     * Parses the response from the site and returns a [MangasPage] object.
     *
     * @param response the response from the site.
     */
    override fun latestUpdatesParse(response: Response) = throw UnsupportedOperationException()

    override fun fetchPopularManga(page: Int): Observable<MangasPage> {
        return client.newCall(popularMangaRequest(page))
            .asObservableSuccess()
            .flatMap { responseToMangas(it) }
    }

    override fun fetchLatestUpdates(page: Int): Observable<MangasPage> {
        return client.newCall(latestUpdatesRequest(page))
            .asObservableSuccess()
            .flatMap { responseToMangas(it) }
    }

    fun responseToMangas(response: Response): Observable<MangasPage> {
        val range = response.header("Content-Range")!!
        val total = range.substringAfter('/').toLong()
        val end = range.substringBefore('/').substringAfter('-').toLong()
        val body = response.body!!
        return parseNozomiPage(body.bytes())
            .map {
                MangasPage(it, end < total - 1)
            }
    }

    private fun parseNozomiPage(array: ByteArray): Observable<List<SManga>> {
        val cursor = ByteCursor(array)
        val ids = (1..array.size / 4).map {
            cursor.nextInt()
        }

        return nozomiIdsToMangas(ids).toObservable()
    }

    private fun nozomiIdsToMangas(ids: List<Int>): Single<List<SManga>> {
        return Single.zip(
            ids.map {
                client.newCall(GET("$LTN_BASE_URL/galleryblock/$it.html"))
                    .asObservableSuccess()
                    .subscribeOn(Schedulers.io()) // Perform all these requests in parallel
                    .map { parseGalleryBlock(it) }
                    .toSingle()
            }
        ) { it.map { m -> m as SManga } }
    }

    private fun parseGalleryBlock(response: Response): SManga {
        val doc = response.asJsoup()
        return SManga.create().apply {
            val titleElement = doc.selectFirst("h1")
            title = titleElement!!.text()
            thumbnail_url = "https:" + if (prefs.eh_hl_useHighQualityThumbs().get()) {
                doc.selectFirst("source")!!.attr("data-srcset").substringBefore(' ')
            } else {
                doc.selectFirst("img")!!.attr("data-src")
            }
            url = titleElement.child(0)!!.attr("href")

            // TODO Parse tags and stuff
        }
    }

    /**
     * Returns an observable with the updated details for a manga. Normally it's not needed to
     * override this method.
     *
     * @param manga the manga to be updated.
     */
    override fun mangaDetailsRequest(manga: SManga): Request {
        return GET(LTN_BASE_URL + "/galleryblock/" + manga.url.substringAfterLast("-"))
    }

    override fun fetchMangaDetails(manga: SManga): Observable<SManga> {
        return client.newCall(mangaDetailsRequest(manga))
            .asObservableSuccess()
            .flatMap {
                parseToManga(manga, it.asJsoup()).andThen(
                    Observable.just(
                        manga.apply {
                            initialized = true
                        }
                    )
                )
            }
    }

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> {
        return Observable.just(
            listOf(
                SChapter.create().apply {
                    url = manga.url
                    name = "Chapter"
                    chapter_number = 0.0f
                }
            )
        )
    }

    override fun pageListRequest(chapter: SChapter): Request {
        return GET("$LTN_BASE_URL/galleries/${HitomiSearchMetadata.hlIdFromUrl(chapter.url)}.js")
    }

    /**
     * Parses the response from the site and returns the details of a manga.
     *
     * @param response the response from the site.
     */
    override fun mangaDetailsParse(response: Response) = throw UnsupportedOperationException()

    /**
     * Parses the response from the site and returns a list of chapters.
     *
     * @param response the response from the site.
     */
    override fun chapterListParse(response: Response) = throw UnsupportedOperationException()

    /**
     * Parses the response from the site and returns a list of pages.
     *
     * @param response the response from the site.
     */
    override fun pageListParse(response: Response): List<Page> {
        val str = response.body!!.string()
        val json = JsonParser.parseString(str.removePrefix("var galleryinfo = "))
        return json["files"].array.mapIndexed { index, jsonElement ->
            val hash = jsonElement["hash"].string
            Page(
                index,
                hash
            )
        }
    }

    override fun fetchImageUrl(page: Page): Observable<String> {
        val ggUrl = "$LTN_BASE_URL/gg.js"
        val commonUrl = "$LTN_BASE_URL/common.js"

        val ggJS = client.newCall(GET(ggUrl, headers)).execute().body!!.string().substringAfter("'use strict';")
        val commonJS = client.newCall(GET(commonUrl, headers)).execute().body!!.string().substringAfter("navigator.userAgent);").substringBefore("function show_loading()")

        val hashJS = "url_from_url_from_hash('', {'hash':'${page.url}'}, 'webp', undefined, 'a');"

        QuickJs.create().use {
            it.evaluate(ggJS)
            it.evaluate(commonJS)
            val imgurl = it.evaluate(hashJS).toString()
            Log.d("hitomi", imgurl)
            return Observable.just(imgurl)
        }
    }

    /**
     * Parses the response from the site and returns the absolute url to the source image.
     *
     * @param response the response from the site.
     */
    override fun imageUrlParse(response: Response) = throw UnsupportedOperationException()

    override val matchingHosts = listOf(
        "hitomi.la"
    )

    override fun mapUrlToMangaUrl(uri: Uri): String? {
        val lcFirstPathSegment = uri.pathSegments.firstOrNull()?.toLowerCase() ?: return null

        if (lcFirstPathSegment != "manga" && lcFirstPathSegment != "reader") {
            return null
        }

        return "https://hitomi.la/manga/${uri.pathSegments[1].substringBefore('.')}.html"
    }

    companion object {
        private val INDEX_VERSION_CACHE_TIME_MS = 1000 * 60 * 10
        private val PAGE_SIZE = 25

        private val DATE_FORMAT by lazy {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                SimpleDateFormat("yyyy-MM-dd HH:mm:ssX", Locale.US)
            } else {
                SimpleDateFormat("yyyy-MM-dd HH:mm:ss'-05'", Locale.US)
            }
        }
    }
}
