package org.walleth.etherscan

import android.arch.lifecycle.*
import android.content.Intent
import kotlinx.coroutines.experimental.delay
import kotlinx.coroutines.experimental.launch
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject
import org.kethereum.functions.encodeRLP
import org.kethereum.model.Address
import org.kodein.di.KodeinAware
import org.kodein.di.android.closestKodein
import org.kodein.di.generic.instance
import org.ligi.kaxt.letIf
import org.ligi.tracedroid.logging.Log
import org.walleth.BuildConfig
import org.walleth.data.AppDatabase
import org.walleth.data.balances.Balance
import org.walleth.data.balances.upsertIfNewerBlock
import org.walleth.data.networks.CurrentAddressProvider
import org.walleth.data.networks.NetworkDefinition
import org.walleth.data.networks.NetworkDefinitionProvider
import org.walleth.data.tokens.CurrentTokenProvider
import org.walleth.data.tokens.isETH
import org.walleth.data.transactions.TransactionEntity
import org.walleth.data.transactions.setHash
import org.walleth.khex.toHexString
import java.io.IOException
import java.math.BigInteger
import java.security.cert.CertPathValidatorException

class EtherScanService : LifecycleService(), KodeinAware {

    override val kodein by closestKodein()
    private val okHttpClient: OkHttpClient by instance()
    private val currentAddressProvider: CurrentAddressProvider by instance()
    private val tokenProvider: CurrentTokenProvider by instance()
    private val appDatabase: AppDatabase by instance()
    private val networkDefinitionProvider: NetworkDefinitionProvider by instance()

    companion object {
        private var timing = 7_000 // in MilliSeconds
        private var last_run = 0L
        private var shortcut = false

        private var lastSeenTransactionsBlock = 0L
        private var lastSeenBalanceBlock = 0L
    }

    class TimingModifyingLifecycleObserver : LifecycleObserver {
        @OnLifecycleEvent(Lifecycle.Event.ON_RESUME)
        fun connectListener() {
            timing = 7_000
            shortcut = true
        }

        @OnLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        fun disconnectListener() {
            timing = 70_000
        }
    }

    class ResettingObserver<T> : Observer<T> {
        override fun onChanged(p0: T?) {
            shortcut = true
            lastSeenBalanceBlock = 0L
            lastSeenTransactionsBlock = 0L
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        currentAddressProvider.observe(this, ResettingObserver())
        networkDefinitionProvider.observe(this, ResettingObserver())

        lifecycle.addObserver(TimingModifyingLifecycleObserver())

        launch {

            while (true) {
                last_run = System.currentTimeMillis()

                currentAddressProvider.value?.let {
                    tryFetchFromEtherScan(it.hex)
                }

                while ((last_run + timing) > System.currentTimeMillis() && !shortcut) {
                    delay(100)
                }
                shortcut = false
            }
        }


        relayTransactionsIfNeeded()
        return START_STICKY
    }

    private fun relayTransactionsIfNeeded() {
        appDatabase.transactions.getAllToRelayLive().observe(this, Observer {
            it?.let { it.filter { it.signatureData != null && !it.transactionState.relayedEtherscan }.forEach { relayTransaction(it) } }
        })
    }

    private fun relayTransaction(transaction: TransactionEntity) {
        launch {
            val url = "module=proxy&action=eth_sendRawTransaction&hex=" + transaction.transaction.encodeRLP(transaction.signatureData).toHexString("0x")
            val result = getEtherscanResult(url, networkDefinitionProvider.value!!)

            if (result != null) {
                val oldHash = transaction.hash
                if (result.has("result")) {
                    val newHash = result.getString("result")

                    transaction.setHash(if (!newHash.startsWith("0x")) "0x" + newHash else newHash)
                } else if (result.has("error")) {
                    val error = result.getJSONObject("error")

                    if (error.has("message") &&
                            !error.getString("message").startsWith("known") &&
                            error.getString("message") != "Transaction with the same hash was already imported."
                    ) {
                        transaction.transactionState.error = result.toString()
                    }
                } else {
                    transaction.transactionState.error = result.toString()
                }
                transaction.transactionState.eventLog = transaction.transactionState.eventLog ?: "" + "relayed via EtherScan"
                transaction.transactionState.relayedEtherscan = true

                appDatabase.transactions.deleteByHash(oldHash)
                appDatabase.transactions.upsert(transaction)
            }
        }
    }

    private fun tryFetchFromEtherScan(addressHex: String) {
        queryEtherscanForBalance(addressHex)
        queryTransactions(addressHex)
    }

    private fun queryTransactions(addressHex: String) {
        networkDefinitionProvider.value?.let { currentNetwork ->
            val requestString = "module=account&action=txlist&address=$addressHex&startblock=$lastSeenTransactionsBlock&endblock=${lastSeenBalanceBlock + 1L}&sort=asc"

            try {
                val etherscanResult = getEtherscanResult(requestString, currentNetwork)
                if (etherscanResult != null && etherscanResult.has("result")) {
                    val jsonArray = etherscanResult.getJSONArray("result")
                    val newTransactions = parseEtherScanTransactions(jsonArray, currentNetwork.chain)

                    lastSeenTransactionsBlock = newTransactions.highestBlock

                    newTransactions.list.forEach {

                        val oldEntry = appDatabase.transactions.getByHash(it.hash)
                        if (oldEntry == null || oldEntry.transactionState.isPending) {
                            appDatabase.transactions.upsert(it)
                        }
                    }

                }
            } catch (e: JSONException) {
                Log.w("Problem with JSON from EtherScan: " + e.message)
            }
        }
    }

    private fun queryEtherscanForBalance(addressHex: String) {

        networkDefinitionProvider.value?.let { currentNetwork ->
            val currentToken = tokenProvider.currentToken
            val etherscanResult = getEtherscanResult("module=proxy&action=eth_blockNumber", currentNetwork)

            if (etherscanResult?.has("result") != true) {
                Log.w("Cannot parse " + etherscanResult)
                return
            }
            val blockNum = etherscanResult.getString("result")?.replace("0x", "")?.toLongOrNull(16)

            if (blockNum != null) {
                lastSeenBalanceBlock = blockNum

                val balanceString = if (currentToken.isETH()) {
                    getEtherscanResult("module=account&action=balance&address=$addressHex&tag=latest", currentNetwork)?.getString("result")

                } else {
                    getEtherscanResult("module=account&action=tokenbalance&contractaddress=${currentToken.address}&address=$addressHex&tag=latest", currentNetwork)?.getString("result")

                }

                if (balanceString != null) {
                    try {
                        appDatabase.balances.upsertIfNewerBlock(
                                Balance(address = Address(addressHex),
                                        block = blockNum,
                                        balance = BigInteger(balanceString),
                                        tokenAddress = currentToken.address,
                                        chain = currentNetwork.chain
                                )
                        )
                    } catch (e: NumberFormatException) {
                        Log.i("could not parse number $balanceString")
                    }
                }
            }
        }
    }

    private fun getEtherscanResult(requestString: String, networkDefinition: NetworkDefinition) = try {
        getEtherscanResult(requestString, networkDefinition, true)
    } catch (e: CertPathValidatorException) {
        getEtherscanResult(requestString, networkDefinition, true)
    }

    private fun getEtherscanResult(requestString: String, networkDefinition: NetworkDefinition, httpFallback: Boolean): JSONObject? {
        val baseURL = networkDefinition.getBlockExplorer().baseAPIURL.letIf(httpFallback) {
            replace("https://", "http://") // :-( https://github.com/walleth/walleth/issues/134 )
        }
        val urlString = "$baseURL/api?$requestString&apikey=$" + BuildConfig.ETHERSCAN_APIKEY
        val url = Request.Builder().url(urlString).build()
        val newCall: Call = okHttpClient.newCall(url)

        try {
            val resultString = newCall.execute().body().use { it?.string() }
            resultString.let {
                return JSONObject(it)
            }
        } catch (ioe: IOException) {
            ioe.printStackTrace()
        } catch (jsonException: JSONException) {
            jsonException.printStackTrace()
        }

        return null
    }

}