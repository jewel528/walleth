package org.ligi.walleth

import android.app.Application
import android.content.Intent
import android.support.v7.app.AppCompatDelegate
import com.github.salomonbrys.kodein.*
import com.jakewharton.threetenabp.AndroidThreeTen
import org.ligi.tracedroid.TraceDroid
import org.ligi.walleth.core.GethLightEthereumService
import org.ligi.walleth.data.*
import org.ligi.walleth.data.addressbook.AddressBook
import org.ligi.walleth.data.addressbook.FileBackedAddressBook
import org.ligi.walleth.data.keystore.GethBackedWallethKeyStore
import org.ligi.walleth.data.keystore.WallethKeyStore
import org.ligi.walleth.data.networks.NetworkDefinition
import org.ligi.walleth.data.networks.RinkebyNetworkDefinition
import org.ligi.walleth.data.syncprogress.SyncProgressProvider

open class App : Application(), KodeinAware {

    override val kodein by Kodein.lazy {
        import(createKodein())
    }

    open fun createKodein() = Kodein.Module {
        bind<AddressBook>() with singleton { FileBackedAddressBook() }
        bind<BalanceProvider>() with singleton { BalanceProvider() }
        bind<TransactionProvider>() with singleton { FileBackedTransactionProvider() }
        bind<ExchangeRateProvider>() with singleton { CachingExchangeProvider(FixedValueExchangeProvider(), instance()) }
        bind<SyncProgressProvider>() with singleton { SyncProgressProvider() }
        bind<WallethKeyStore>() with singleton { GethBackedWallethKeyStore(this@App) }
    }

    override fun onCreate() {
        super.onCreate()

        TraceDroid.init(this)
        AndroidThreeTen.init(this)

        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        executeCodeWeWillIgnoreInTests()
    }

    open fun executeCodeWeWillIgnoreInTests() {
        startService(Intent(this, GethLightEthereumService::class.java))
    }

    companion object {
        var networḱ: NetworkDefinition = RinkebyNetworkDefinition()
    }
}

