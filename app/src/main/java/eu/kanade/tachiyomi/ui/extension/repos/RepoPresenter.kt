package eu.kanade.tachiyomi.ui.extension.repos

import android.os.Bundle
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.ui.base.presenter.BasePresenter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import rx.Observable
import rx.android.schedulers.AndroidSchedulers
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Presenter of [RepoController]. Used to manage the repos for the extensions.
 */
class RepoPresenter(
    private val preferences: PreferencesHelper = Injekt.get()
) : BasePresenter<RepoController>() {
    val scope = CoroutineScope(Job() + Dispatchers.Main)

    /**
     * List containing repos.
     */
    private var repos: List<String> = emptyList()

    /**
     * Called when the presenter is created.
     *
     * @param savedState The saved state of this presenter.
     */
    override fun onCreate(savedState: Bundle?) {
        super.onCreate(savedState)

        preferences.extensionRepos().asFlow().onEach { repos ->
            this.repos = repos.toList().sortedBy { it.toLowerCase() }

            Observable.just(this.repos)
                .map { it.map(::RepoItem) }
                .observeOn(AndroidSchedulers.mainThread())
                .subscribeLatestCache(RepoController::setRepos)
        }.launchIn(scope)
    }

    /**
     * Creates and adds a new repo to the database.
     *
     * @param name The name of the repo to create.
     */
    fun createRepo(name: String) {
        // Do not allow duplicate repos.
        if (repoExists(name)) {
            Observable.just(Unit).subscribeFirst({ view, _ -> view.onRepoExistsError() })
            return
        }

        // Do not allow invalid formats
        if (!name.matches(repoRegex) && !name.matches(urlRegex)) {
            Observable.just(Unit).subscribeFirst({ view, _ -> view.onRepoInvalidNameError() })
            return
        }

        preferences.extensionRepos().set((repos + name).toSet())
    }

    /**
     * Deletes the given repos from the database.
     *
     * @param repos The list of repos to delete.
     */
    fun deleteRepos(repos: List<String>) {
        preferences.extensionRepos().set(
            this.repos.filterNot { it in repos }.toSet()
        )
    }

    /**
     * Returns true if a repo with the given name already exists.
     */
    private fun repoExists(name: String): Boolean {
        return repos.any { it.equals(name, true) }
    }

    companion object {
        val repoRegex =
            """^[a-zA-Z-_.]*?\/[a-zA-Z-_.]*?$""".toRegex()
        val urlRegex =
            """^https://.*/index\.min\.json$""".toRegex()
    }
}
