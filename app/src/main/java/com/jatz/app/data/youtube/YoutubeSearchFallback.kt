package com.jatz.app.data.youtube

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * A YouTube search that does NOT go through NewPipeExtractor.
 *
 * NewPipeExtractor's own search parser walks YouTube's response with chained
 * `.getObject("contents").getObject("twoColumnSearchResultsRenderer")...`
 * calls, and throws NullPointerException the moment YouTube returns a shape it
 * doesn't expect. That is exactly what happened on device — for every track of
 * every album, on the latest release (v0.26.5, no newer version to bump to),
 * and pinning the locale to en-US did not fix it.
 *
 * So this bypasses that code path entirely: fetch the results page, pull out
 * the embedded `ytInitialData` JSON, and find videos by **recursively walking
 * the whole tree for any object that has both a `videoId` and a title**. It
 * assumes no schema at all, so a YouTube layout change can't NPE it — at
 * worst it finds nothing and says so.
 */
object YoutubeSearchFallback {

    private const val TAG = "JATZ-YT-FB"

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    // ytInitialData is assigned in one of a couple of shapes depending on
    // which page variant is served; both are matched rather than assuming one.
    private val INITIAL_DATA = Regex(
        """(?:var\s+ytInitialData\s*=|window\["ytInitialData"\]\s*=)\s*(\{.*?\})\s*;\s*</script>""",
        RegexOption.DOT_MATCHES_ALL,
    )

    private val VIDEO_ID = Regex("""^[A-Za-z0-9_-]{11}$""")

    data class Candidate(val videoId: String, val title: String)

    fun search(client: OkHttpClient, query: String): List<Candidate> {
        val url = "https://www.youtube.com/results?search_query=" +
            URLEncoder.encode(query, "UTF-8") + "&hl=en&gl=US"

        val html = try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "results page HTTP ${resp.code} for \"$query\"")
                    return emptyList()
                }
                resp.body?.string()
            }
        } catch (e: Exception) {
            Log.w(TAG, "results page fetch failed for \"$query\"", e)
            return emptyList()
        } ?: return emptyList()

        val json = INITIAL_DATA.find(html)?.groupValues?.get(1)
        if (json == null) {
            // Fall back to the crudest possible extraction: video ids in page
            // order, no titles. Ranking then relies on YouTube's own ordering.
            val ids = Regex(""""videoId":"([A-Za-z0-9_-]{11})"""")
                .findAll(html).map { it.groupValues[1] }.distinct().take(10).toList()
            Log.w(TAG, "no ytInitialData for \"$query\"; raw id scan found ${ids.size}")
            return ids.map { Candidate(it, "") }
        }

        val root = try {
            JSONObject(json)
        } catch (e: Exception) {
            Log.w(TAG, "ytInitialData not valid JSON for \"$query\"", e)
            return emptyList()
        }

        val out = LinkedHashMap<String, Candidate>()
        collect(root, out, depth = 0)
        Log.i(TAG, "fallback search \"$query\" -> ${out.size} candidates")
        return out.values.take(15)
    }

    /** Depth-first walk collecting every {videoId, title} pair, schema-agnostic. */
    private fun collect(node: Any?, out: MutableMap<String, Candidate>, depth: Int) {
        if (depth > 40 || out.size >= 40) return
        when (node) {
            is JSONObject -> {
                val id = node.optString("videoId", "")
                if (VIDEO_ID.matches(id) && !out.containsKey(id)) {
                    val title = extractTitle(node)
                    // Only keep entries that actually look like a search result
                    // (they carry a title); bare videoId references appear all
                    // over the page in unrelated contexts.
                    if (title.isNotBlank()) out[id] = Candidate(id, title)
                }
                for (key in node.keys()) collect(node.opt(key), out, depth + 1)
            }
            is JSONArray -> {
                for (i in 0 until node.length()) collect(node.opt(i), out, depth + 1)
            }
        }
    }

    /** Titles come as either {"runs":[{"text":...}]} or {"simpleText":...}. */
    private fun extractTitle(node: JSONObject): String {
        val title = node.optJSONObject("title") ?: return ""
        title.optString("simpleText", "").let { if (it.isNotBlank()) return it }
        val runs = title.optJSONArray("runs") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until runs.length()) {
            sb.append(runs.optJSONObject(i)?.optString("text", "") ?: "")
        }
        return sb.toString()
    }
}
