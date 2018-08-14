package org.walleth.activities

import android.annotation.TargetApi
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.support.v7.app.AlertDialog
import android.support.v7.app.AppCompatActivity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import kotlinx.android.synthetic.main.activity_import_json.*
import kotlinx.coroutines.experimental.CommonPool
import kotlinx.coroutines.experimental.android.UI
import kotlinx.coroutines.experimental.async
import kotlinx.coroutines.experimental.launch
import org.kethereum.bip39.dirtyPhraseToMnemonicWords
import org.kethereum.bip39.toKey
import org.kethereum.bip39.validate
import org.kethereum.bip39.wordlists.ENGLISH
import org.kethereum.crypto.ECKeyPair
import org.kethereum.erc55.withERC55Checksum
import org.kethereum.model.Address
import org.kethereum.wallet.loadKeysFromWalletJsonString
import org.kodein.di.KodeinAware
import org.kodein.di.android.closestKodein
import org.kodein.di.generic.instance
import org.ligi.kaxt.setVisibility
import org.ligi.kaxtui.alert
import org.threeten.bp.LocalDateTime
import org.walleth.R
import org.walleth.activities.qrscan.startScanActivityForResult
import org.walleth.data.AppDatabase
import org.walleth.data.DEFAULT_ETHEREUM_BIP44_PATH
import org.walleth.data.DEFAULT_PASSWORD
import org.walleth.data.addressbook.AddressBookEntry
import org.walleth.data.addressbook.getByAddressAsync
import org.walleth.data.keystore.WallethKeyStore
import org.walleth.khex.hexToByteArray
import java.io.FileNotFoundException

enum class KeyType {
    ECDSA, JSON, WORDLIST
}


private const val KEY_INTENT_EXTRA_TYPE = "TYPE"
private const val KEY_INTENT_EXTRA_KEYCONTENT = "KEY"

fun Context.getKeyImportIntent(key: String, type: KeyType) = Intent(this, ImportActivity::class.java).apply {
    putExtra(KEY_INTENT_EXTRA_TYPE, type.toString())
    putExtra(KEY_INTENT_EXTRA_KEYCONTENT, key)
}

private const val READ_REQUEST_CODE = 42

class ImportActivity : AppCompatActivity(), KodeinAware {

    override val kodein by closestKodein()

    private val keyStore: WallethKeyStore by instance()
    private val appDatabase: AppDatabase by instance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_import_json)

        intent.getStringExtra(KEY_INTENT_EXTRA_KEYCONTENT)?.let {
            key_content.setText(it)
        }

        val typeExtra = intent.getStringExtra(KEY_INTENT_EXTRA_TYPE) ?: KeyType.WORDLIST.toString()

        type_wordlist_select.isChecked = KeyType.valueOf(typeExtra) == KeyType.WORDLIST
        type_json_select.isChecked = KeyType.valueOf(typeExtra) == KeyType.JSON
        type_ecdsa_select.isChecked = !type_json_select.isChecked && !type_wordlist_select.isChecked

        key_type_select.setOnCheckedChangeListener { _, _ ->
            refreshKeyTypeDependingUI()
        }

        supportActionBar?.subtitle = getString(R.string.import_json_subtitle)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        fab.setOnClickListener {
            doImport()
        }

        refreshKeyTypeDependingUI()
    }

    private fun refreshKeyTypeDependingUI() {
        password_container.setVisibility(!type_ecdsa_select.isChecked)
        key_container.hint = getString(if (type_wordlist_select.isChecked) {
            R.string.key_input_wordlist_hint
        } else {
            R.string.key_input_key_hint
        })
    }

    var importing = false
    private fun doImport() = launch(UI) {
        if (importing) {
            return@launch
        }
        importing = true

        val alert = AlertDialog.Builder(this@ImportActivity)
        fab_progress_bar.visibility = View.VISIBLE
        try {

            val content = key_content.text.toString()

            val importKey = async {
                val key = when {
                    type_json_select.isChecked ->
                        content.loadKeysFromWalletJsonString(password.text.toString())
                    type_wordlist_select.isChecked -> {
                        val mnemonicWords = dirtyPhraseToMnemonicWords(content)
                        if (!mnemonicWords.validate(ENGLISH)) {
                            throw IllegalArgumentException("Mnemonic phrase not valid")
                        }
                        mnemonicWords.toKey(DEFAULT_ETHEREUM_BIP44_PATH).keyPair

                    }
                    else -> ECKeyPair.create(content.hexToByteArray())
                }

                keyStore.importKey(key, DEFAULT_PASSWORD)
            }.await()


            if (importKey != null) {

                val address = Address(importKey.hex).withERC55Checksum()
                alert.setMessage(getString(R.string.imported_key_alert_message, address))
                        .setTitle(getString(R.string.dialog_title_success))

                appDatabase.addressBook.getByAddressAsync(importKey) { oldEntry ->
                    val accountName = if (account_name.text.isBlank()) {
                        oldEntry?.name ?: getString(R.string.imported_key_default_entry_name)
                    } else {
                        account_name.text
                    }
                    val note = oldEntry?.note ?: getString(R.string.imported_key_entry_note, LocalDateTime.now())


                    launch(CommonPool) {
                        val newEntry = AddressBookEntry(
                                name = accountName.toString(),
                                address = importKey,
                                note = note, isNotificationWanted = false,
                                trezorDerivationPath = null
                        )
                        appDatabase.addressBook.upsert(newEntry)
                    }
                }

            }

            alert.setPositiveButton(android.R.string.ok) { _, _ -> finish() }

        } catch (e: Exception) {
            alert.setMessage(e.message)
                    .setTitle(getString(R.string.dialog_title_error))
                    .setPositiveButton(android.R.string.ok, null)
        }

        alert.show()
        fab_progress_bar.visibility = View.INVISIBLE
        importing = false
    }


    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_import, menu)
        menu.findItem(R.id.menu_open).isVisible = Build.VERSION.SDK_INT >= 19
        return super.onCreateOptionsMenu(menu)
    }

    public override fun onActivityResult(requestCode: Int, resultCode: Int,
                                         resultData: Intent?) {


        resultData?.let {
            if (it.hasExtra("SCAN_RESULT")) {
                key_content.setText(it.getStringExtra("SCAN_RESULT"))
            }
            if (requestCode == READ_REQUEST_CODE && resultCode == Activity.RESULT_OK) {

                key_content.setText(readTextFromUri(it.data))
            }
        }
    }

    private fun readTextFromUri(uri: Uri) = try {
        contentResolver.openInputStream(uri).reader().readText()
    } catch (fileNotFoundException: FileNotFoundException) {
        alert("Cannot read from $uri - if you think I should - please contact ligi@ligi.de with details of the device (Android version,Brand) and the beginning of the uri")
        null
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {

        R.id.menu_open -> true.also {
            tryOpen()
        }

        R.id.menu_scan -> true.also {
            startScanActivityForResult(this)
        }

        android.R.id.home -> true.also {
            finish()
        }

        else -> super.onOptionsItemSelected(item)
    }

    @TargetApi(19)
    private fun tryOpen() {
        try {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            intent.addCategory(Intent.CATEGORY_OPENABLE)
            intent.type = "*/*"

            startActivityForResult(intent, READ_REQUEST_CODE)
        } catch (e: ActivityNotFoundException) {
            alert(R.string.saf_activity_not_found_problem)
        }
    }
}
