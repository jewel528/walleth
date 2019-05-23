package org.walleth.infrastructure

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
import androidx.room.Room
import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.kethereum.DEFAULT_GAS_PRICE
import org.kethereum.keystore.api.KeyStore
import org.kethereum.rpc.EthereumRPC
import org.kethereum.rpc.model.StringResultResponse
import org.koin.androidx.viewmodel.ext.koin.viewModel
import org.koin.dsl.module.module
import org.mockito.Mockito
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.walleth.App
import org.walleth.contracts.FourByteDirectory
import org.walleth.data.AppDatabase
import org.walleth.data.config.Settings
import org.walleth.data.exchangerate.ExchangeRateProvider
import org.walleth.data.networks.ChainInfoProvider
import org.walleth.data.networks.CurrentAddressProvider
import org.walleth.data.rpc.RPCProvider
import org.walleth.data.syncprogress.SyncProgressProvider
import org.walleth.data.syncprogress.WallethSyncProgress
import org.walleth.data.tokens.CurrentTokenProvider
import org.walleth.kethereum.model.ContractFunction
import org.walleth.testdata.DefaultCurrentAddressProvider
import org.walleth.testdata.FixedValueExchangeProvider
import org.walleth.testdata.TestKeyStore
import org.walleth.viewmodels.TransactionListViewModel

private fun <T> any(): T {
    Mockito.any<T>()
    return uninitialized()
}

private fun <T> uninitialized(): T = null as T

class TestApp : App() {

    override fun createKoin() = module {
        single { fixedValueExchangeProvider as ExchangeRateProvider }
        single {
            SyncProgressProvider().apply {
                value = WallethSyncProgress(true, 42000, 42042)
            }
        }
        single { keyStore as KeyStore }
        single { mySettings }
        single { currentAddressProvider as CurrentAddressProvider }
        single { chainInfoProvider }
        single { currentTokenProvider }
        single { testDatabase }
        single { testFourByteDirectory }
        single {
            mock(RPCProvider::class.java).apply {
                `when`(get()).thenReturn(RPCMock)
            }
        }

        viewModel { TransactionListViewModel(this@TestApp, get(), get(), get()) }
    }

    override fun executeCodeWeWillIgnoreInTests() = Unit
    override fun onCreate() {
        companionContext = this
        resetDB()
        super.onCreate()
    }

    companion object {
        val RPCMock = mock(EthereumRPC::class.java).apply {
            `when`(estimateGas(any())).thenReturn(StringResultResponse("0x00"))
        }
        val fixedValueExchangeProvider = FixedValueExchangeProvider()
        val keyStore = TestKeyStore()
        val mySettings = mock(Settings::class.java).apply {
            `when`(currentFiat).thenReturn("EUR")
            `when`(getNightMode()).thenReturn(MODE_NIGHT_YES)
            `when`(onboardingDone).thenReturn(true)
            `when`(chain).thenReturn(4L)
            `when`(isLightClientWanted()).thenReturn(false)
            `when`(addressInitVersion).thenReturn(0)
            `when`(tokensInitVersion).thenReturn(0)
            `when`(getGasPriceFor(any())).thenReturn(DEFAULT_GAS_PRICE)
        }
        val currentAddressProvider = DefaultCurrentAddressProvider(mySettings, keyStore)
        val chainInfoProvider by lazy {
            ChainInfoProvider(mySettings, testDatabase, Moshi.Builder().add(BigIntegerAdapter()).build(), companionContext!!.assets)
        }
        val currentTokenProvider by lazy {
            CurrentTokenProvider(chainInfoProvider)
        }

        val contractFunctionTextSignature1 = "aFunctionCall1(address)"
        val contractFunctionTextSignature2 = "aFunctionCall2(address)"
        val testFourByteDirectory = mock(FourByteDirectory::class.java).apply {
            `when`(getSignaturesFor(any())).then { invocation ->
                listOf(
                        ContractFunction(invocation.arguments[0] as String, textSignature = contractFunctionTextSignature1),
                        ContractFunction(invocation.arguments[0] as String, textSignature = contractFunctionTextSignature2)
                )
            }
        }

        val testDatabase by lazy {
            Room.inMemoryDatabaseBuilder(companionContext!!, AppDatabase::class.java).build()
        }
        var companionContext: Context? = null
        fun resetDB() {
            GlobalScope.launch(Dispatchers.Default) {
                testDatabase.clearAllTables()
            }
        }

    }
}
