package eu.kanade.tachiyomi.extension.en.toongod

import eu.kanade.tachiyomi.multisrc.madara.GenreRoute
import eu.kanade.tachiyomi.multisrc.madara.MadaraNoAjax
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.annotation.Source
import keiyoushi.network.Fetch
import keiyoushi.network.get
import keiyoushi.utils.asJsoup
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonElement
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlinx.serialization.json.JsonElement
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A text field whose value is sent as a ToonGod search parameter. */
private class ToonGodTextFilter(name: String, val parameter: String) : Filter.Text(name)

/** A select filter that retains the corresponding website parameter value. */
private class ToonGodValueFilter(
    name: String,
    val parameter: String,
    private val options: List<Pair<String, String>>,
) : Filter.Select<String>(name, options.map { it.first }.toTypedArray()) {
    fun selectedValue(): String = options.getOrNull(state)?.second.orEmpty()
}

private class ToonGodGenreOption(name: String, val slug: String) : Filter.CheckBox(name)

private class ToonGodGenreFilter(name: String, genres: List<GenreRoute>) :
    Filter.Group<ToonGodGenreOption>(name, genres.map { ToonGodGenreOption(it.name, it.slug) }) {
    fun selectedSlugs(): List<String> = state.filter { it.state }.map { it.slug }
}

private class ToonGodStatusOption(name: String, val slug: String) : Filter.CheckBox(name)

private class ToonGodStatusFilter(name: String, options: List<Pair<String, String>>) :
    Filter.Group<ToonGodStatusOption>(name, options.map { ToonGodStatusOption(it.first, it.second) }) {
    fun selectedSlugs(): List<String> = state.filter { it.state }.map { it.slug }
}

@Source
abstract class ToonGod : MadaraNoAjax() {
    override val chapterDateFormat = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.US)
    override val mangaSubString = "webtoons"
    override val genreDirectory = "webtoon-genre"

    // ToonGod's filter form is on the site root and uses input[name="genre[]"],
    // not the genre anchor links expected by MadaraBase.fetchFilterData().
    override suspend fun fetchFilterData(): JsonElement {
        val document = client.get(baseUrl, Fetch.DOCUMENT).asJsoup()
        val routes = mutableListOf<GenreRoute>()

        document.select("input").filter { it.attr("name") == "genre[]" }.forEach { input ->
            val slug = input.attr("value").trim().takeIf(String::isNotBlank) ?: return@forEach
            val id = input.attr("id")
            val associatedLabel = if (id.isNotBlank()) {
                document.select("label").firstOrNull { it.attr("for") == id }?.text()
            } else {
                null
            }
            val wrappingLabel = generateSequence(input.parent()) { it.parent() }
                .firstOrNull { it.normalName() == "label" }
                ?.text()
            val nextText = input.nextSibling()?.toString()?.trim()?.takeIf { it.isNotBlank() && it.length <= 60 }
            val parentText = input.parent()?.text()?.takeIf { it.isNotBlank() && it.length <= 60 }
            val name = sequenceOf(associatedLabel, wrappingLabel, input.nextElementSibling()?.text(), nextText, parentText)
                .filterNotNull()
                .map { it.trim() }
                .firstOrNull { it.isNotBlank() && it.length <= 60 }
                ?: slug.split('-', '_').joinToString(" ") { part -> part.replaceFirstChar { it.uppercase() } }

            routes += GenreRoute(name, slug, "/$genreDirectory/$slug/")
        }

        // Some site layouts also expose the same genres as taxonomy links.
        routes += document.select("a[href*='/$genreDirectory/']").mapNotNull { element ->
            val href = element.attr("abs:href").takeIf(String::isNotBlank) ?: return@mapNotNull null
            val path = runCatching { href.toHttpUrl().encodedPath }.getOrNull() ?: return@mapNotNull null
            val slug = path.trimEnd('/').substringAfterLast('/').takeIf(String::isNotBlank) ?: return@mapNotNull null
            val name = element.text().takeIf(String::isNotBlank) ?: return@mapNotNull null
            GenreRoute(name, slug, path)
        }

        return routes.distinctBy(GenreRoute::slug).toJsonElement()
    }

    override val statusFilterOptions: List<Pair<String, String>> = listOf(
        intl["status_filter_ongoing"] to "on-going",
        intl["status_filter_completed"] to "end",
        intl["status_filter_canceled"] to "canceled",
        intl["status_filter_on_hold"] to "on-hold",
        "Upcoming" to "upcoming",
    )

    override fun getFilterList(data: JsonElement?): FilterList {
        val genres = data?.parseAs<List<GenreRoute>>().orEmpty()
        return FilterList(
            buildList {
                add(ToonGodTextFilter(intl["author_filter_title"], "author"))
                add(ToonGodTextFilter(intl["artist_filter_title"], "artist"))
                add(ToonGodTextFilter(intl["year_filter_title"], "release"))
                add(ToonGodStatusFilter(intl["status_filter_title"], statusFilterOptions))
                add(ToonGodValueFilter(intl["order_by_filter_title"], "sort", orderByFilterOptions))
                add(ToonGodValueFilter(intl["adult_content_filter_title"], "adult", adultFilterOptions))

                if (genres.isNotEmpty()) {
                    add(Filter.Separator())
                    add(Filter.Header(intl["genre_filter_header"]))
                    add(ToonGodValueFilter(intl["genre_condition_filter_title"], "op", genreConditionFilterOptions))
                    add(ToonGodGenreFilter(intl["genre_filter_title"], genres))
                }
            },
        )
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val textFilters = filters.filterIsInstance<ToonGodTextFilter>()
        fun textValue(parameter: String): String = textFilters.firstOrNull { it.parameter == parameter }?.state.orEmpty()

        val valueFilters = filters.filterIsInstance<ToonGodValueFilter>()
        fun selectedValue(parameter: String): String = valueFilters.firstOrNull { it.parameter == parameter }?.selectedValue().orEmpty()

        val selectedGenres = filters.filterIsInstance<ToonGodGenreFilter>().firstOrNull()?.selectedSlugs().orEmpty()
        val selectedStatuses = filters.filterIsInstance<ToonGodStatusFilter>().firstOrNull()?.selectedSlugs().orEmpty()

        val url = baseUrl.toHttpUrl().newBuilder().apply {
            if (page > 1) addPathSegments("page/$page/")
            addQueryParameter("s", query)
            addQueryParameter("post_type", "wp-manga")
            selectedGenres.forEach { addQueryParameter("genre[]", it) }
            addQueryParameter("op", selectedValue("op"))
            addQueryParameter("author", textValue("author"))
            addQueryParameter("artist", textValue("artist"))
            addQueryParameter("release", textValue("release"))
            addQueryParameter("adult", selectedValue("adult"))
            selectedStatuses.forEach { addQueryParameter("status[]", it) }
            selectedValue("sort").takeIf(String::isNotBlank)?.let { addQueryParameter("m_orderby", it) }
        }.build()

        val document = client.get(url, Fetch.DOCUMENT).asJsoup()
        val archiveMangas = parseArchive(document)
        val mangas = if (archiveMangas.isNotEmpty()) {
            archiveMangas
        } else {
            // Search results can omit data-post-id. Their canonical paths are sufficient
            // for the details and chapter requests used by this source.
            parseSearchCards(document).map { card ->
                SManga.create().apply {
                    this.url = card.path
                    title = card.title
                    thumbnail_url = card.thumbnail
                    memo = mangaMemo(card.path, emptyList())
                }
            }
        }

        return MangasPage(mangas, document.selectFirst(nextPageSelector()) != null)
    }
}
