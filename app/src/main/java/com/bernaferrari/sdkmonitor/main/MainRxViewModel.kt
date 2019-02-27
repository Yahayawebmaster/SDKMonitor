package com.bernaferrari.sdkmonitor.main

import com.bernaferrari.sdkmonitor.Injector
import com.bernaferrari.sdkmonitor.core.AppManager
import com.bernaferrari.sdkmonitor.core.MvRxViewModel
import com.bernaferrari.sdkmonitor.data.App
import com.bernaferrari.sdkmonitor.data.Version
import com.bernaferrari.sdkmonitor.extensions.convertTimestampToDate
import com.bernaferrari.sdkmonitor.extensions.normalizeString
import com.jakewharton.rxrelay2.BehaviorRelay
import io.reactivex.Observable
import io.reactivex.rxkotlin.Observables
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * initialState *must* be implemented as a constructor parameter.
 */
class MainRxViewModel(initialState: MainState) : MvRxViewModel<MainState>(initialState) {

    private val mAppsDao = Injector.get().appsDao()
    private val mVersionsDao = Injector.get().versionsDao()

    val itemsList = mutableListOf<AppVersion>()
    var hasLoaded = false
    var maxListSize: BehaviorRelay<Int> = BehaviorRelay.create<Int>()
    val inputRelay: BehaviorRelay<String> = BehaviorRelay.create<String>()
    val showProgressRelay: BehaviorRelay<Boolean> = BehaviorRelay.create<Boolean>()

    init {
        fetchData()
    }

    private fun fetchData() = withState {
        Observables.combineLatest(
            getAppsListObservable(),
            inputRelay
        ) { list, filter ->
            // get the string without special characters and filter the list.
            // If the filter is not blank, it will filter the list.
            // If it is blank, it will return the original list.
            val pattern = filter.normalizeString()
            list.takeIf { filter.isNotBlank() }
                ?.filter { pattern in it.app.title.normalizeString() }
                    ?: list
        }.doOnNext {
            itemsList.clear()
            itemsList.addAll(it)
        }.execute {
            copy(listOfItems = it)
        }
    }

    suspend fun fetchAllVersions(packageName: String): List<Version>? =
        withContext(Dispatchers.IO) { mVersionsDao.getAllValues(packageName) }

    fun fetchAppDetails(packageName: String): MutableList<AppDetails> {

        val packageInfo = AppManager.getPackageInfo(packageName) ?: return mutableListOf()

        return mutableListOf<AppDetails>().apply {

            packageInfo.applicationInfo.className?.also {
                this += AppDetails("Class Name", it)
            }

            packageInfo.applicationInfo.sourceDir?.also {
                this += AppDetails("Source Dir", it)
            }

            packageInfo.applicationInfo.dataDir?.also {
                this += AppDetails("Data Dir", it)
            }
        }
    }

    var firstRun = true

    private fun getAppsListObservable(): Observable<List<AppVersion>> =

        mAppsDao.getAppsListFlowable().toObservable()
            .debounce { list ->
                // debounce with a 200ms delay all items except the first one
                val flow = Observable.just(list)
                hasLoaded = true
                flow.takeIf { list.isEmpty() }
                    ?.let { it } ?: flow.delay(200, TimeUnit.MILLISECONDS)
            }
            .skipWhile {
                if (it.isEmpty() || firstRun) {
                    firstRun = false
                    updateAll()
                }
                it.isEmpty()
            }
            .also {
                println("RAWRRR REFRESHING IT!!!")
            }
            .map { list ->
                val allItems = mutableListOf<AppVersion>()
                list.asSequence()
                    .sortedBy { it.title.toLowerCase() }
                    .mapTo(allItems) { app ->
                        val (sdkVersion, lastUpdate) = getSdkDate(app)
                        AppVersion(app, sdkVersion, lastUpdate)
                    }.toList()
            }.doOnNext { maxListSize.accept(it.size) }

    private fun getSdkDate(app: App): Pair<Int, String> {

        // since insertApp is called before insertVersion, mVersionsDao.getValue(...) will
        // return null on app's first run. This will avoid the situation.
        val version = mVersionsDao.getLastValue(app.packageName)

        val sdkVersion =
            version?.targetSdk ?: AppManager.getApplicationInfo(app.packageName)?.targetSdkVersion
            ?: 0

        val lastUpdate =
            version?.lastUpdateTime ?: AppManager.getPackageInfo(
                app.packageName
            )?.lastUpdateTime ?: 0

        return Pair(sdkVersion, lastUpdate.convertTimestampToDate())
    }

    fun updateAll() = GlobalScope.launch(Dispatchers.IO) {
        showProgressRelay.accept(true)

        AppManager.getPackagesWithUserPrefs()
            // this condition will only happen when app is run on emulator.
            .let { if (it.isEmpty()) AppManager.getPackages() else it }
            .forEach { packageInfo ->
                AppManager.insertNewApp(packageInfo)
                AppManager.insertNewVersion(packageInfo)
            }

        showProgressRelay.accept(false)
    }
}
