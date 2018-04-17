package org.walleth.activities

import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import android.view.Menu
import android.view.MenuItem
import kotlinx.android.synthetic.main.activity_account_edit.*
import kotlinx.coroutines.experimental.CommonPool
import kotlinx.coroutines.experimental.async
import org.kodein.di.KodeinAware
import org.kodein.di.android.closestKodein
import org.kodein.di.generic.instance
import org.ligi.kaxt.doAfterEdit
import org.ligi.kaxt.startActivityFromURL
import org.walleth.R
import org.walleth.data.AppDatabase
import org.walleth.data.addressbook.AddressBookEntry
import org.walleth.data.addressbook.getByAddressAsync
import org.walleth.data.networks.CurrentAddressProvider
import org.walleth.data.networks.NetworkDefinitionProvider
import org.walleth.util.copyToClipboard

class EditAccountActivity : AppCompatActivity(), KodeinAware {

    override val kodein by closestKodein()
    private val appDatabase: AppDatabase by instance()
    private val networkDefinitionProvider: NetworkDefinitionProvider by instance()
    private val currentAddressProvider: CurrentAddressProvider by instance()
    private lateinit var currentAddressInfo: AddressBookEntry

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_account_edit)

        supportActionBar?.subtitle = getString(R.string.edit_account_subtitle)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        appDatabase.addressBook.getByAddressAsync(currentAddressProvider.getCurrent()) {
            currentAddressInfo = it!!


            nameInput.setText(currentAddressInfo.name)
            noteInput.setText(currentAddressInfo.note)

            notification_checkbox.isChecked = currentAddressInfo.isNotificationWanted

            nameInput.doAfterEdit {
                currentAddressInfo.name = nameInput.text.toString()
            }

            noteInput.doAfterEdit {
                currentAddressInfo.note = noteInput.text.toString()
            }

            notification_checkbox.setOnCheckedChangeListener { _, isChecked ->
                currentAddressInfo.isNotificationWanted = isChecked
            }
        }
    }

    override fun onPause() {
        super.onPause()
        async(CommonPool) {
            appDatabase.addressBook.upsert(currentAddressInfo)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?) = super.onCreateOptionsMenu(menu.apply { menuInflater.inflate(R.menu.menu_edit, menu) })


    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        R.id.menu_copy -> true.also {
            copyToClipboard(currentAddressInfo.address, activity_main)
        }
        R.id.menu_etherscan -> true.also {
            startActivityFromURL(networkDefinitionProvider.value!!.getBlockExplorer().getURLforAddress(currentAddressProvider.getCurrent()))
        }
        android.R.id.home -> true.also {
            finish()
        }
        else -> super.onOptionsItemSelected(item)
    }
}
