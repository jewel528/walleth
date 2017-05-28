package org.walleth.activities

import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.support.v7.app.ActionBarDrawerToggle
import android.support.v7.app.AlertDialog
import android.support.v7.app.AppCompatActivity
import android.support.v7.widget.LinearLayoutManager
import android.view.Menu
import android.view.MenuItem
import android.view.View.INVISIBLE
import com.github.salomonbrys.kodein.LazyKodein
import com.github.salomonbrys.kodein.android.appKodein
import com.github.salomonbrys.kodein.instance
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.android.synthetic.main.activity_main_in_drawer_container.*
import kotlinx.android.synthetic.main.value.*
import org.ligi.kaxt.recreateWhenPossible
import org.ligi.kaxt.setVisibility
import org.ligi.kaxt.startActivityFromClass
import org.ligi.kaxtui.alert
import org.ligi.tracedroid.TraceDroid
import org.ligi.tracedroid.sending.TraceDroidEmailSender
import org.walleth.R
import org.walleth.data.BalanceAtBlock
import org.walleth.data.BalanceProvider
import org.walleth.data.addressbook.AddressBook
import org.walleth.data.config.Settings
import org.walleth.data.keystore.WallethKeyStore
import org.walleth.data.syncprogress.SyncProgressProvider
import org.walleth.data.transactions.TransactionProvider
import org.walleth.iac.BarCodeIntentIntegrator
import org.walleth.iac.BarCodeIntentIntegrator.QR_CODE_TYPES
import org.walleth.iac.isERC67String
import org.walleth.ui.ChangeObserver
import org.walleth.ui.TransactionAdapterDirection.INCOMMING
import org.walleth.ui.TransactionAdapterDirection.OUTGOING
import org.walleth.ui.TransactionRecyclerAdapter
import java.math.BigInteger


class MainActivity : AppCompatActivity() {

    val lazyKodein = LazyKodein(appKodein)

    val actionBarDrawerToggle by lazy { ActionBarDrawerToggle(this, drawer_layout, R.string.drawer_open, R.string.drawer_close) }

    val balanceProvider: BalanceProvider by lazyKodein.instance()
    val transactionProvider: TransactionProvider by lazyKodein.instance()
    val syncProgressProvider: SyncProgressProvider by lazyKodein.instance()
    val addressBook: AddressBook by lazyKodein.instance()
    val keyStore: WallethKeyStore by lazyKodein.instance()
    val settings: Settings by lazyKodein.instance()
    var lastNightMode: Int? = null

    override fun onResume() {
        super.onResume()

        if (lastNightMode != null && lastNightMode != settings.getNightMode()) {
            recreateWhenPossible()
            return
        }
        lastNightMode = settings.getNightMode()

        syncProgressProvider.registerChangeObserverWithInitialObservation(object : ChangeObserver {
            override fun observeChange() {
                runOnUiThread {
                    val progress = syncProgressProvider.currentSyncProgress

                    if (progress.isSyncing) {
                        val percent = ((progress.currentBlock.toDouble() / progress.highestBlock) * 100).toInt()
                        supportActionBar?.subtitle = "Block ${progress.currentBlock}/${progress.highestBlock} ($percent%)"
                    }
                }
            }
        })

        transactionProvider.registerChangeObserverWithInitialObservation(object : ChangeObserver {
            override fun observeChange() {
                val allTransactions = transactionProvider.getTransactionsForAddress(keyStore.getCurrentAddress())
                val incomingTransactions = allTransactions.filter { it.to == keyStore.getCurrentAddress() }.sortedByDescending { it.localTime }
                val outgoingTransactions = allTransactions.filter { it.from == keyStore.getCurrentAddress() }.sortedByDescending { it.localTime }

                val hasNoTransactions = incomingTransactions.size + outgoingTransactions.size == 0

                runOnUiThread {
                    transaction_recycler_out.adapter = TransactionRecyclerAdapter(outgoingTransactions, addressBook, OUTGOING)
                    transaction_recycler_in.adapter = TransactionRecyclerAdapter(incomingTransactions, addressBook, INCOMMING)

                    empty_view_container.setVisibility(hasNoTransactions)

                    send_container.setVisibility(!hasNoTransactions, INVISIBLE)

                    transaction_recycler_in.setVisibility(!hasNoTransactions)
                    transaction_recycler_out.setVisibility(!hasNoTransactions)

                    fab.setVisibility(!hasNoTransactions)
                }
            }
        })
        balanceProvider.registerChangeObserverWithInitialObservation(object : ChangeObserver {
            override fun observeChange() {
                var balanceForAddress = BalanceAtBlock(balance = BigInteger("0"), block = 0)
                balanceProvider.getBalanceForAddress(keyStore.getCurrentAddress())?.let {
                    balanceForAddress = it
                }
                val balanceIsZero = balanceForAddress.balance == BigInteger.ZERO

                runOnUiThread {
                    value_view.setEtherValue(balanceForAddress.balance)

                    if (!syncProgressProvider.currentSyncProgress.isSyncing) {
                        supportActionBar?.subtitle = "Block " + balanceForAddress.block
                    }
                }
            }
        })
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (data != null && data.hasExtra("SCAN_RESULT")) {
            if (!data.getStringExtra("SCAN_RESULT").isERC67String()) {
                AlertDialog.Builder(this)
                        .setMessage("Only ERC67 supported currently")
                        .setPositiveButton("OK", null)
                        .show()
            } else {
                val intent = Intent(this, TransferActivity::class.java).apply {
                    setData(Uri.parse(data.getStringExtra("SCAN_RESULT")))
                }
                startActivity(intent)
            }
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!settings.startupWarningDone) {
            alert(title = "Special Awareness", message = "Please note this is one alpha on the rinkeby test-network. Please do not work with real values yet!")
            settings.startupWarningDone = true
        }

        if (TraceDroid.getStackTraceFiles().isNotEmpty()) {
            TraceDroidEmailSender.sendStackTraces("ligi@ligi.de", this)
        }

        setContentView(R.layout.activity_main_in_drawer_container)

        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        drawer_layout.addDrawerListener(actionBarDrawerToggle)

        receive_container.setOnClickListener {
            startActivityFromClass(RequestActivity::class)
        }

        send_container.setOnClickListener {
            startActivityFromClass(TransferActivity::class)
        }

        fab.setOnClickListener {
            BarCodeIntentIntegrator(this).initiateScan(QR_CODE_TYPES)
        }

        transaction_recycler_out.layoutManager = LinearLayoutManager(this)
        transaction_recycler_in.layoutManager = LinearLayoutManager(this)

        current_fiat_symbol.setOnClickListener {
            startActivityFromClass(SelectReferenceActivity::class)
        }
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        actionBarDrawerToggle.syncState()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        actionBarDrawerToggle.onConfigurationChanged(newConfig)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        R.id.menu_info -> {
            startActivityFromClass(InfoActivity::class.java)
            true
        }
        else -> actionBarDrawerToggle.onOptionsItemSelected(item) || super.onOptionsItemSelected(item)
    }

}
