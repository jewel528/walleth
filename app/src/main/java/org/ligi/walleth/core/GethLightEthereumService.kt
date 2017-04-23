package org.ligi.walleth.core

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.SystemClock
import com.github.salomonbrys.kodein.LazyKodein
import com.github.salomonbrys.kodein.android.appKodein
import com.github.salomonbrys.kodein.instance
import okhttp3.OkHttpClient
import org.ethereum.geth.*
import org.ligi.walleth.App
import org.ligi.walleth.App.Companion.networḱ
import org.ligi.walleth.data.*
import org.ligi.walleth.data.Transaction
import org.ligi.walleth.data.syncprogress.SyncProgressProvider
import org.ligi.walleth.data.syncprogress.WallethSyncProgress
import java.io.File
import java.math.BigInteger


class GethLightEthereumService : Service() {

    val binder by lazy { Binder() }
    override fun onBind(intent: Intent) = binder
    val okHttpClient = OkHttpClient.Builder().build()!!

    val ethereumContext = Context()

    val balanceProvider: BalanceProvider by LazyKodein(appKodein).instance()
    val transactionProvider: TransactionProvider by LazyKodein(appKodein).instance()
    val syncProgress: SyncProgressProvider by LazyKodein(appKodein).instance()

    private val path by lazy { File(baseContext.filesDir, ".ethereum_rb").absolutePath }

    val ethereumNode by lazy {
        Geth.newNode(path, NodeConfig().apply {
            val bootNodes = Enodes()

            networḱ.bootNodes.forEach {
                bootNodes.append(Enode(it))
            }

            bootstrapNodes = bootNodes
            ethereumGenesis = networḱ.genesis
            ethereumNetworkID = 4
            ethereumNetStats = "ligi:Respect my authoritah!@stats.rinkeby.io"
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        try {
            ethereumNode.start()
        } catch (e: Exception) {
            // TODO better handling - unfortunately ethereumNode does not have one isStarted method which would come handy here
        }

        ethereumNode.ethereumClient.subscribeNewHead(ethereumContext, object : NewHeadHandler {
            override fun onNewHead(p0: Header) {
                val address = App.currentAddress!!.toGethAddr()
                val balance = ethereumNode.ethereumClient.getBalanceAt(ethereumContext, address, p0.number)
                balanceProvider.setBalance(WallethAddress(address.hex), p0.number, BigInteger(balance.string()))
            }

            override fun onError(p0: String?) {}

        }, 16)

        Thread({

            while (true) {

                val ethereumSyncProgress = ethereumNode.ethereumClient.syncProgress(ethereumContext)

                if (ethereumSyncProgress != null) {
                    val newSyncProgress = WallethSyncProgress(true, ethereumSyncProgress.currentBlock, ethereumSyncProgress.highestBlock)
                    syncProgress.setSyncProgress(newSyncProgress)
                } else {
                    syncProgress.setSyncProgress(WallethSyncProgress())
                }


                transactionProvider.getAllTransactions().forEach {
                    if (it.ref == Source.WALLETH) {
                        executeTransaction(it)
                    }
                }

                SystemClock.sleep(1000)
            }
        }).start()

        return START_STICKY
    }


    private fun executeTransaction(it: Transaction) {
        it.ref = Source.WALLETH_PROCESSED

        val client = ethereumNode.ethereumClient
        val nonceAt = client.getNonceAt(ethereumContext, it.from.toGethAddr(), -1)

        val gasPrice = client.suggestGasPrice(ethereumContext)

        val gasLimit = BigInt(21_000)

        val newTransaction = Geth.newTransaction(nonceAt, it.to.toGethAddr(), BigInt(it.value.toLong()), gasLimit, gasPrice, ByteArray(0))

        newTransaction.hashCode()
        val accounts = App.keyStore.accounts
        App.keyStore.unlock(accounts.get(0), "default")
        val signHash = App.keyStore.signHash(it.from.toGethAddr(), newTransaction.sigHash.bytes)
        val transactionWithSignature = newTransaction.withSignature(signHash)

        transactionWithSignature.hash.hex
        it.sigHash = newTransaction.sigHash.hex

        client.sendTransaction(ethereumContext, transactionWithSignature)
    }

}
