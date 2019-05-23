package org.walleth.ui

import android.content.Context
import androidx.appcompat.app.AlertDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.kethereum.model.ChainId
import org.ligi.kaxtui.alert
import org.walleth.R
import org.walleth.data.AppDatabase
import org.walleth.data.networks.ChainInfoProvider


// TODO - handle cancellation of dialog

fun Context.chainIDAlert(chainInfoProvider: ChainInfoProvider,
                         appDatabase: AppDatabase,
                         chainId: ChainId?,
                         continuationWithWrongChainId: () -> Unit = {},
                         continuationWithCorrectOrNullChainId: () -> Unit) {

    if (chainId == null || chainId == chainInfoProvider.getCurrentChainId()) {
        continuationWithCorrectOrNullChainId()
    } else {
        GlobalScope.launch(Dispatchers.Default) {
            val networkToSwitchTo = appDatabase.chainInfo.getByChainId(chainId.value)

            GlobalScope.launch(Dispatchers.Main) {
                if (networkToSwitchTo == null) {
                    alert(
                            message = getString(R.string.alert_network_unsupported_message, chainId.value),
                            title = getString(R.string.alert_network_unsupported_title),
                            onOK = {
                                continuationWithWrongChainId()
                            }
                    )
                } else {
                    AlertDialog.Builder(this@chainIDAlert)
                            .setMessage("wrong chainID - do you want to switch?")
                            .setPositiveButton(android.R.string.yes) { _, _ ->
                                chainInfoProvider.setCurrent(networkToSwitchTo)
                                continuationWithCorrectOrNullChainId()
                            }
                            .setNegativeButton(android.R.string.no) { _, _ ->
                                continuationWithWrongChainId()
                            }
                            .setCancelable(false)
                            .show()
                }
            }
        }

    }
}