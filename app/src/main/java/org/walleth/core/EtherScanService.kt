package org.walleth.core

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.SystemClock
import com.github.salomonbrys.kodein.LazyKodein
import com.github.salomonbrys.kodein.android.appKodein
import com.github.salomonbrys.kodein.instance
import okhttp3.*
import org.json.JSONObject
import org.threeten.bp.Instant
import org.threeten.bp.LocalDateTime
import org.threeten.bp.ZoneOffset
import org.walleth.BuildConfig
import org.walleth.data.BalanceProvider
import org.walleth.data.WallethAddress
import org.walleth.data.keystore.WallethKeyStore
import org.walleth.data.transactions.Transaction
import org.walleth.data.transactions.TransactionProvider
import org.walleth.data.transactions.TransactionSource
import java.io.IOException
import java.math.BigInteger


class EtherScanService : Service() {

    val binder by lazy { Binder() }
    override fun onBind(intent: Intent) = binder


    val lazyKodein = LazyKodein(appKodein)

    val okHttpClient: OkHttpClient by lazyKodein.instance()
    val keyStore: WallethKeyStore by lazyKodein.instance()
    val transactionProvider: TransactionProvider by lazyKodein.instance()
    val balanceProvider: BalanceProvider by lazyKodein.instance()


    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        Thread({

            while (true) {
                tryFetchFromEtherScan(keyStore.getCurrentAddress().hex)

                SystemClock.sleep(10000)
            }
        }).start()


        return START_STICKY
    }

    fun tryFetchFromEtherScan(addressHex: String) {
        queryTransactions(addressHex)
        queryEtherscanForBalance(addressHex)
    }

    fun queryTransactions(addressHex: String) {

        getEtherscanResult("module=account&action=txlist&address=$addressHex&startblock=0&endblock=99999999&sort=asc") {

            val jsonArray = it.getJSONArray("result")
            (0..(jsonArray.length() - 1)).forEach {
                val transactionJson = jsonArray.getJSONObject(it)
                val value = BigInteger(transactionJson.getString("value"))
                val timeStamp = Instant.ofEpochSecond(transactionJson.getString("timeStamp").toLong())
                val ofInstant = LocalDateTime.ofInstant(timeStamp, ZoneOffset.systemDefault())
                val transaction = Transaction(
                        value,
                        WallethAddress(transactionJson.getString("from")),
                        WallethAddress(transactionJson.getString("to")),
                        nonce = transactionJson.getLong("nonce"),
                        ref = TransactionSource.ETHERSCAN,
                        txHash = transactionJson.getString("hash"),
                        localTime = ofInstant
                )
                transactionProvider.addTransaction(transaction)
            }
        }

    }

    fun queryEtherscanForBalance(addressHex: String) {

        getEtherscanResult("module=account&action=balance&address=$addressHex&tag=latest") {
            val balance = BigInteger(it.getString("result"))
            getEtherscanResult("module=proxy&action=eth_blockNumber") {
                balanceProvider.setBalance(WallethAddress(addressHex), it.getString("result").replace("0x","").toLong(16), balance)
            }

        }

    }

    fun Call.enqueueOnlySuccess(success: (it: JSONObject) -> Unit) {
        enqueue(object : Callback {
            override fun onFailure(call: Call?, e: IOException) {
                e.printStackTrace()
            }

            override fun onResponse(call: Call?, response: Response) {
                success(JSONObject(response.body().string()))
            }
        })
    }


    fun getEtherscanResult(requestSTring: String, successCallback: (responseJSON: JSONObject) -> Unit) {
        val urlString = "https://rinkeby.etherscan.io/api?$requestSTring&apikey=$"+ BuildConfig.ETHERSCAN_APIKEY
        val url = Request.Builder().url(urlString).build()
        val newCall: Call = okHttpClient.newCall(url)
        newCall.enqueueOnlySuccess(successCallback)

    }
}
