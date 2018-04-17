package org.walleth.activities

import android.os.Bundle
import android.support.v7.app.AlertDialog
import android.support.v7.app.AppCompatActivity
import org.kodein.di.KodeinAware
import org.kodein.di.android.closestKodein
import org.kodein.di.generic.instance
import org.ligi.kaxt.startActivityFromClass
import org.walleth.R
import org.walleth.data.keystore.WallethKeyStore
import org.walleth.data.networks.CurrentAddressProvider

class KeysActivity : AppCompatActivity(), KodeinAware {

    override val kodein by closestKodein()
    val keyStore: WallethKeyStore by instance()
    val currentAddressProvider: CurrentAddressProvider by instance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!keyStore.hasKeyForForAddress(currentAddressProvider.getCurrent())) {
            startActivityFromClass(ImportActivity::class.java)
            finish()
        } else {
            val items = arrayOf(getString(R.string.nav_drawer_import_key), getString(R.string.nav_drawer_export_key))
            AlertDialog.Builder(this)
                    .setOnCancelListener {
                        finish()
                    }
                    .setItems(items, { _, i ->
                        when (i) {
                            0 -> startActivityFromClass(ImportActivity::class.java)
                            else -> startActivityFromClass(ExportKeyActivity::class.java)
                        }
                        finish()
                    })
                    .show()
        }


    }

}
