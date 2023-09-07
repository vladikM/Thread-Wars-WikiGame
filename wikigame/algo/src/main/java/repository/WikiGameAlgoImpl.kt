package repository

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import model.BackwardPage
import model.ForwardPage
import point.rar.model.Page
import point.rar.repository.WikiGame
import point.rar.wikisuspend.WikiRemoteDataSource
import point.rar.wikisuspend.WikiRemoteDataSourceImpl
import java.lang.RuntimeException
import java.util.*
import java.util.concurrent.ConcurrentHashMap

class WikiGameAlgoImpl : WikiGame {

    private val wikiRemoteDataSource: WikiRemoteDataSource = WikiRemoteDataSourceImpl()
    override fun play(startPageTitle: String, endPageTitle: String, maxDepth: Int): List<String> {
        val res = process(startPageTitle, endPageTitle, maxDepth)
        if (res.isEmpty) {
            throw RuntimeException("Could not find")
        }

        val endPage = res.get()
        val path = mutableListOf<String>()

        var curPg: Page? = endPage
        do {
            path.add(curPg!!.title)
            curPg = curPg.parentPage
        } while (curPg != null)

//        val pagesWithResponseCount = visitedPages.entries.count { it.value == RESPONSE_RECEIVED }
//        println("Received responses from $pagesWithResponseCount pages")

        return path.reversed()
    }

    private fun process(
        startPageTitle: String,
        endPageTitle: String,
        maxDepth: Int,
    ): Optional<Page> = runBlocking {
        val visitedForwardPages: MutableMap<String, ForwardPage> = ConcurrentHashMap()
        val visitedBackwardPages: MutableMap<String, BackwardPage> = ConcurrentHashMap()
        val ctx = newFixedThreadPoolContext(4, "fixed-thread-context")
        val scope = CoroutineScope(ctx)

        val startForwardPage = ForwardPage(startPageTitle, null)
        val endBackwardPage = BackwardPage(endPageTitle, null)

        val ch = Channel<Optional<Pair<ForwardPage, BackwardPage>>>()

        scope.launch {
            val res = processPageForward(startForwardPage, endPageTitle, 0, maxDepth, visitedForwardPages, visitedBackwardPages, scope)
            ch.send(res)
        }
        scope.launch {
            val res = processPageBackward(endBackwardPage, endPageTitle, 0, maxDepth, visitedForwardPages, visitedBackwardPages, scope)
            ch.send(res)
        }

        for (i in 1..2) {
            val res = ch.receive()
            if (res.isPresent) {
                val pair = res.get()
                return@runBlocking Optional.of(getFinalPageFromForwardAndBackward(pair.first, pair.second))
            }
        }

        return@runBlocking Optional.empty()
    }

    private suspend fun processPageForward(
        page: ForwardPage,
        endPageTitle: String,
        curDepth: Int,
        maxDepth: Int,
        visitedForwardPages: MutableMap<String, ForwardPage>,
        visitedBackwardPages: MutableMap<String, BackwardPage>,
        coroutineScope: CoroutineScope
    ): Optional<Pair<ForwardPage, BackwardPage>> {
        if (visitedForwardPages.putIfAbsent(page.title, page) != null) {
            return Optional.empty()
        }

        val backwardPage = visitedBackwardPages[page.title]
        if (backwardPage != null) {
            return Optional.of(Pair(page, backwardPage))
        }

        if (curDepth == maxDepth) {
            return Optional.empty()
        }

//        println("Started for ${page.title}, depth = $curDepth")
        val links = wikiRemoteDataSource.getLinksByTitle(page.title)

        val ch = Channel<Optional<Pair<ForwardPage, BackwardPage>>>()

        links.forEach {
            // Creates new scope because we don't want to wait for all the coroutines to complete
            coroutineScope.launch {
                val coroRes = processPageForward(
                    ForwardPage(it, page),
                    endPageTitle,
                    curDepth + 1,
                    maxDepth,
                    visitedForwardPages,
                    visitedBackwardPages,
                    coroutineScope
                )

                ch.send(coroRes)
            }
        }

        for (i in 1..links.size) {
            val chRes = ch.receive()
            if (chRes.isPresent) {
                return chRes
            }
        }

        return Optional.empty()
    }

    private suspend fun processPageBackward(
        page: BackwardPage,
        endPageTitle: String,
        curDepth: Int,
        maxDepth: Int,
        visitedForwardPages: MutableMap<String, ForwardPage>,
        visitedBackwardPages: MutableMap<String, BackwardPage>,
        coroutineScope: CoroutineScope
    ): Optional<Pair<ForwardPage, BackwardPage>> {
        if (visitedBackwardPages.putIfAbsent(page.title, page) != null) {
            return Optional.empty()
        }

        val forwardPage = visitedForwardPages[page.title]
        if (forwardPage != null) {
            return Optional.of(Pair(forwardPage, page))
        }

        if (curDepth == maxDepth) {
            return Optional.empty()
        }

//        println("Started for ${page.title}, depth = $curDepth")
        val backlinks = wikiRemoteDataSource.getBacklinksByTitle(page.title)

        val ch = Channel<Optional<Pair<ForwardPage, BackwardPage>>>()

        backlinks.forEach {
            // Creates new scope because we don't want to wait for all the coroutines to complete
            coroutineScope.launch {
                val coroRes = processPageBackward(
                    BackwardPage(it, page),
                    endPageTitle,
                    curDepth + 1,
                    maxDepth,
                    visitedForwardPages,
                    visitedBackwardPages,
                    coroutineScope
                )

                ch.send(coroRes)
            }
        }

        for (i in 1..backlinks.size) {
            val chRes = ch.receive()
            if (chRes.isPresent) {
                return chRes
            }
        }

        return Optional.empty()
    }

    fun getFinalPageFromForwardAndBackward(forwardPage: ForwardPage, backwardPage: BackwardPage): Page {
        val forwardPages = mutableListOf<ForwardPage>()
        var curFwdPage: ForwardPage? = forwardPage
        do {
            forwardPages.add(curFwdPage!!)
            curFwdPage = curFwdPage.parentPage
        } while(curFwdPage != null)

        var lastPage: Page? = null
        for (fwdPg in forwardPages.reversed()) {
            lastPage = Page(fwdPg.title, lastPage)
        }

        val backwardPages = mutableListOf<BackwardPage>()
        var curBwdPage: BackwardPage? = backwardPage.childPage
        do {
            backwardPages.add(curBwdPage!!)
            curBwdPage = curBwdPage.childPage
        } while(curBwdPage != null)

        for (bwdPg in backwardPages) {
            lastPage = Page(bwdPg.title, lastPage)
        }

        return lastPage!!
    }
}