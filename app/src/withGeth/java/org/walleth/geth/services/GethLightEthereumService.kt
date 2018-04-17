package org.walleth.geth.services

import android.annotation.TargetApi
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.NotificationManager.IMPORTANCE_HIGH
import android.app.PendingIntent
import android.arch.lifecycle.LifecycleService
import android.arch.lifecycle.Observer
import android.content.Context
import android.content.Intent
import android.os.Build
import android.support.v4.app.NotificationCompat
import kotlinx.coroutines.experimental.CommonPool
import kotlinx.coroutines.experimental.android.UI
import kotlinx.coroutines.experimental.async
import kotlinx.coroutines.experimental.delay
import kotlinx.coroutines.experimental.launch
import org.ethereum.geth.*
import org.kethereum.functions.encodeRLP
import org.kodein.di.KodeinAware
import org.kodein.di.android.closestKodein
import org.kodein.di.generic.instance
import org.ligi.tracedroid.logging.Log
import org.walleth.R
import org.walleth.activities.MainActivity
import org.walleth.data.AppDatabase
import org.walleth.data.balances.Balance
import org.walleth.data.config.Settings
import org.walleth.data.networks.CurrentAddressProvider
import org.walleth.data.networks.NetworkDefinitionProvider
import org.walleth.data.syncprogress.SyncProgressProvider
import org.walleth.data.syncprogress.WallethSyncProgress
import org.walleth.data.tokens.getEthTokenForChain
import org.walleth.data.transactions.TransactionEntity
import org.walleth.kethereum.geth.toGethAddr
import java.io.File
import java.math.BigInteger
import java.util.concurrent.TimeUnit
import org.ethereum.geth.Context as EthereumContext

private const val NOTIFICATION_ID = 101
private const val NOTIFICATION_CHANNEL_ID = "geth"

class GethLightEthereumService : LifecycleService(), KodeinAware {

    override val kodein by closestKodein()

    companion object {
        const val STOP_SERVICE_ACTION = "STOPSERVICE"
        fun Context.gethStopIntent() = Intent(this, GethLightEthereumService::class.java).apply {
            action = STOP_SERVICE_ACTION
        }

        var shouldRun = false
        var isRunning = false
    }

    private val syncProgress: SyncProgressProvider by instance()
    private val appDatabase: AppDatabase by instance()
    private val settings: Settings by instance()
    private val networkDefinitionProvider: NetworkDefinitionProvider by instance()
    private val currentAddressProvider: CurrentAddressProvider by instance()
    private val path by lazy { File(baseContext.cacheDir, "ethereumdata").absolutePath }
    private val notificationManager by lazy { getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager }

    private var isSyncing = false
    private var finishedSyncing = false

    private var shouldRestart = false

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent.action == STOP_SERVICE_ACTION) {
            shouldRun = false
            return START_NOT_STICKY
        }

        shouldRun = true


        async(CommonPool) {
            Geth.setVerbosity(settings.currentGoVerbosity.toLong())
            val ethereumContext = EthereumContext()

            var initial = true
            while (shouldRestart || initial) {
                initial = false
                shouldRestart = false // just did restart
                shouldRun = true

                async (UI) {
                    val pendingStopIntent = PendingIntent.getService(baseContext, 0, gethStopIntent(), 0)
                    val contentIntent = PendingIntent.getActivity(baseContext, 0, Intent(baseContext, MainActivity::class.java), 0)

                    if (Build.VERSION.SDK_INT > 25) {
                        setNotificationChannel()
                    }

                    val notification = NotificationCompat.Builder(this@GethLightEthereumService, NOTIFICATION_CHANNEL_ID)
                            .setContentTitle(getString(R.string.geth_service_notification_title))
                            .setContentText(resources.getString(R.string.geth_service_notification_content_text, networkDefinitionProvider.getCurrent().getNetworkName()))
                            .setContentIntent(contentIntent)
                            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "exit", pendingStopIntent)
                            .setSmallIcon(R.drawable.notification)
                            .build()

                    startForeground(NOTIFICATION_ID, notification)
                }.await()

                val network = networkDefinitionProvider.getCurrent()

                networkDefinitionProvider.observe(this@GethLightEthereumService, Observer {
                    if (network != networkDefinitionProvider.getCurrent()) {
                        shouldRun = false
                        shouldRestart = true
                    }
                })

                val subPath = File(path, "chain" + network.chain.id.toString())
                subPath.mkdirs()
                val nodeConfig = NodeConfig().apply {

                    if (network.bootNodes.isEmpty()) {
                        val bootNodes = Enodes()

                        network.bootNodes.forEach {
                            bootNodes.append(Enode(it))
                        }

                        bootstrapNodes = bootNodes
                    }

                    ethereumNetworkID = network.chain.id

                    ethereumGenesis = if (!network.genesis.isEmpty()) {
                        network.genesis
                    } else {
                        when (network.chain.id) {
                            1L -> Geth.mainnetGenesis()
                            3L -> Geth.testnetGenesis()
                            4L -> Geth.rinkebyGenesis()
                            else -> throw (IllegalStateException("NO genesis"))
                        }
                    }

                    if (!network.statsSuffix.isEmpty()) {
                        ethereumNetStats = settings.getStatsName() + network.statsSuffix
                    }
                }
                val ethereumNode = Geth.newNode(subPath.absolutePath, nodeConfig)

                Log.i("Starting Node for " + nodeConfig.ethereumNetworkID)
                ethereumNode.start()
                isRunning = true
                while (shouldRun && !finishedSyncing) {
                    delay(1000)
                    syncTick(ethereumNode, ethereumContext)
                }
                val transactionsLiveData = appDatabase.transactions.getAllToRelayLive()
                val transactionObserver = Observer<List<TransactionEntity>> {
                    it?.forEach { transaction ->
                        transaction.execute(ethereumNode.ethereumClient, ethereumContext)
                    }
                }
                transactionsLiveData.observe(this@GethLightEthereumService, transactionObserver)
                try {
                    ethereumNode.ethereumClient.subscribeNewHead(ethereumContext, object : NewHeadHandler {
                        override fun onNewHead(p0: Header) {
                            val address = currentAddressProvider.getCurrent()
                            val gethAddress = address.toGethAddr()
                            val balance = ethereumNode.ethereumClient.getBalanceAt(ethereumContext, gethAddress, p0.number)
                            appDatabase.balances.upsert(Balance(
                                    address = address,
                                    tokenAddress = getEthTokenForChain(network).address,
                                    chain = network.chain,
                                    balance = BigInteger(balance.string()),
                                    block = p0.number))
                        }

                        override fun onError(p0: String?) {}

                    }, 16)

                } catch (e: Exception) {
                    Log.e("node error", e)
                }

                while (shouldRun) {
                    syncTick(ethereumNode, ethereumContext)
                }

                async(UI) {
                    transactionsLiveData.removeObserver(transactionObserver)
                    launch {
                        ethereumNode.stop()
                    }

                    if (!shouldRestart) {
                        stopForeground(true)
                        stopSelf()
                        isRunning = false
                    } else {
                        notificationManager.cancel(NOTIFICATION_ID)
                    }
                }.await()
            }

        }
        return START_NOT_STICKY
    }

    @TargetApi(26)
    private fun setNotificationChannel() {
        val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, "Geth Service", IMPORTANCE_HIGH)
        channel.description = getString(R.string.geth_service_notification_channel_description)
        notificationManager.createNotificationChannel(channel)
    }

    private suspend fun syncTick(ethereumNode: Node, ethereumContext: EthereumContext) {
        try {
            val ethereumSyncProgress = ethereumNode.ethereumClient.syncProgress(ethereumContext)

            async(UI) {
                if (ethereumSyncProgress != null) {
                    isSyncing = true
                    val newSyncProgress = ethereumSyncProgress.let {
                        WallethSyncProgress(true, it.currentBlock, it.highestBlock)
                    }
                    syncProgress.postValue(newSyncProgress)
                } else {
                    syncProgress.postValue(WallethSyncProgress())
                    if (isSyncing) {
                        finishedSyncing = true
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        delay(1,TimeUnit.SECONDS)
    }


    private fun TransactionEntity.execute(client: EthereumClient, ethereumContext: EthereumContext) {
        try {
            val rlp = transaction.encodeRLP()
            val transactionWithSignature = Geth.newTransactionFromRLP(rlp)
            client.sendTransaction(ethereumContext, transactionWithSignature)
            transactionState.relayedLightClient = true
        } catch (e: Exception) {
            transactionState.error = e.message
        }
    }

}
