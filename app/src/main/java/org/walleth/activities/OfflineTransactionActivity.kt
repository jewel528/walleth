package org.walleth.activities

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import android.view.Menu
import android.view.MenuItem
import kotlinx.android.synthetic.main.activity_relay.*
import kotlinx.coroutines.experimental.CommonPool
import kotlinx.coroutines.experimental.android.UI
import kotlinx.coroutines.experimental.async
import org.json.JSONObject
import org.kethereum.eip155.extractChainID
import org.kethereum.eip155.extractFrom
import org.kethereum.erc681.ERC681
import org.kethereum.erc681.generateURL
import org.kethereum.functions.rlp.*
import org.kethereum.functions.toTransaction
import org.kethereum.functions.toTransactionSignatureData
import org.kethereum.keccakshortcut.keccak
import org.kethereum.model.Address
import org.kethereum.model.ChainDefinition
import org.kethereum.model.SignatureData
import org.kethereum.model.Transaction
import org.kodein.di.Kodein
import org.kodein.di.KodeinAware
import org.kodein.di.android.closestKodein
import org.kodein.di.generic.instance
import org.ligi.kaxt.startActivityFromClass
import org.ligi.kaxtui.alert
import org.walleth.R
import org.walleth.activities.qrscan.startScanActivityForResult
import org.walleth.data.AppDatabase
import org.walleth.data.networks.CurrentAddressProvider
import org.walleth.data.networks.NetworkDefinitionProvider
import org.walleth.data.transactions.TransactionState
import org.walleth.data.transactions.toEntity
import org.walleth.functions.toHexString
import org.walleth.khex.clean0xPrefix
import org.walleth.khex.hexToByteArray
import org.walleth.khex.toHexString
import org.walleth.ui.chainIDAlert
import org.walleth.util.isParityUnsignedTransactionJSON
import org.walleth.util.isSignedTransactionJSON
import org.walleth.util.isUnsignedTransactionJSON
import java.math.BigInteger

private const val KEY_CONTENT = "KEY_OFFLINE_TX_CONTENT"


fun Context.getOfflineTransactionIntent(content: String) = Intent(this, OfflineTransactionActivity::class.java).apply {
    putExtra(KEY_CONTENT, content)
}

class OfflineTransactionActivity : AppCompatActivity(), KodeinAware {

    override val kodein: Kodein by closestKodein()

    private val networkDefinitionProvider: NetworkDefinitionProvider by instance()
    private val appDatabase: AppDatabase by instance()
    private val currentAddressProvider: CurrentAddressProvider by instance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_relay)

        supportActionBar?.subtitle = getString(R.string.relay_transaction)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        fab.setOnClickListener {
            execute()
        }

        parity_signer_button.setOnClickListener {
            startActivityFromClass(ParitySignerQRActivity::class.java)
        }

        intent.getStringExtra(KEY_CONTENT)?.let {
            if (!it.isEmpty()) {
                transaction_to_relay_hex.setText(it)
                execute()
            }
        }

    }

    private fun execute() {
        val content = transaction_to_relay_hex.text.toString()
        when {
            content.isUnsignedTransactionJSON() -> handleUnsignedTransactionJson(content)
            content.isParityUnsignedTransactionJSON() -> handleParityUnsignedTransactionJson(content)
            content.isSignedTransactionJSON() -> {
                val json = JSONObject(content)

                try {
                    val transactionRLP = json.getString("signedTransactionRLP").hexToByteArray()
                    val txRLP = transactionRLP.decodeRLP() as? RLPList
                            ?: throw IllegalArgumentException("RLP not a list")
                    if (txRLP.element.size != 9) {
                        throw IllegalArgumentException("RLP list has the wrong size ${txRLP.element.size} != 9")
                    }


                    val signatureData = txRLP.toTransactionSignatureData()
                    val transaction = txRLP.toTransaction()
                            ?: throw IllegalArgumentException("RLP list has the wrong size ${txRLP.element.size} != 9")

                    val chainID = (signatureData.extractChainID()
                            ?: throw IllegalArgumentException("Cannot extract chainID from RLP"))
                    transaction.chain = ChainDefinition(chainID.toLong())

                    transaction.from = Address(transaction.extractFrom(signatureData, chainID))
                    transaction.txHash = txRLP.encode().keccak().toHexString()
                    createTransaction(transaction, signatureData)
                } catch (e: Exception) {
                    alert(getString(R.string.input_not_valid_message, e.message), getString(R.string.input_not_valid_title))
                }

            }
            else -> executeForRLP()
        }

    }

    private fun handleParityUnsignedTransactionJson(content: String) {
        val json = JSONObject(content)

        val dataJSON = json.getJSONObject("data")
        val rlp = dataJSON.getString("rlp").hexToByteArray().decodeRLP()
        if (rlp is RLPList) {
            if (rlp.element.size != 9) {
                alert("Invalid RLP list - has size " + rlp.element.size + " should have 9")
                return
            }

            val transaction = rlp.toTransaction()

            val chainId = (rlp.element[6] as RLPElement).toBigIntegerFromRLP()

            chainIDAlert(networkDefinitionProvider, chainId.toLong()) {

                if (transaction == null) {
                    alert("could not decode transaction")
                } else {
                    handleUnsignedTransaction(
                            from = "0x" + dataJSON.getString("account").clean0xPrefix(),
                            to = transaction.to!!.hex,
                            data = transaction.input.toHexString(),
                            value = transaction.value.toHexString(),
                            nonce = transaction.nonce!!.toHexString(),
                            gasPrice = transaction.gasPrice.toHexString(),
                            gasLimit = transaction.gasLimit.toHexString(),
                            chainId = networkDefinitionProvider.getCurrent().chain.id,
                            parityFlow = true
                    )
                }
            }

        } else {
            alert("Invalid RLP")
        }
    }

    private fun handleUnsignedTransactionJson(content: String) {
        val json = JSONObject(content)
        handleUnsignedTransaction(
                from = json.getString("from"),
                chainId = json.getLong("chainId"),
                to = json.getString("to"),
                gasLimit = json.getString("gasLimit"),
                value = json.getString("value"),
                nonce = json.getString("nonce"),
                data = json.getString("data"),
                gasPrice = json.getString("gasPrice"),
                parityFlow = false
        )
    }

    private fun handleUnsignedTransaction(from: String,
                                          chainId: Long,
                                          to: String,
                                          value: String,
                                          gasLimit: String,
                                          nonce: String,
                                          data: String,
                                          gasPrice: String,
                                          parityFlow: Boolean) {

        val currentAccount = currentAddressProvider.getCurrent().hex
        if (from.clean0xPrefix().toLowerCase() != currentAccount.clean0xPrefix().toLowerCase()) {
            alert("The from field of the transaction ($from) does not match your current account ($currentAccount)")
            return
        }

        if (chainId != networkDefinitionProvider.getCurrent().chain.id) {
            alert("The chainId of the transaction ($chainId) does not match your current chainId")
            return
        }

        val url = ERC681(scheme = "ethereum",
                address = to,
                value = BigInteger(value.clean0xPrefix(), 16),
                gas = BigInteger(gasLimit.clean0xPrefix(), 16),
                chainId = chainId
        ).generateURL()

        startActivity(Intent(this, CreateTransactionActivity::class.java).apply {
            setData(Uri.parse(url))
            putExtra("nonce", nonce)
            putExtra("data", data)
            putExtra("gasPrice", gasPrice)
            putExtra("from", from)
            putExtra("parityFlow", parityFlow)
        })
    }

    private fun executeForRLP() {

        try {
            val transactionRLP = transaction_to_relay_hex.text.toString().hexToByteArray()

            val rlp = transactionRLP.decodeRLP()

            val rlpList = rlp as RLPList

            if (rlpList.element.size != 9) {
                alert("Found RLP without signature - this is not supported anymore - the transaction source must be in JSON and include the chainID")
            } else {

                val signatureData = rlpList.toTransactionSignatureData()
                val transaction = rlpList.toTransaction()?.apply {
                    txHash = rlpList.encode().keccak().toHexString()
                }

                ERC681(address = transaction?.to?.hex)


                val extractChainID = signatureData.extractChainID()
                val chainId = extractChainID?.toLong() ?: networkDefinitionProvider.getCurrent().chain.id

                transaction?.chain = ChainDefinition(chainId)
                transaction?.let {
                    createTransaction(it, signatureData)
                }
            }

        } catch (e: Exception) {
            alert(getString(R.string.input_not_valid_message, e.message), getString(R.string.input_not_valid_title))
        }
    }

    private fun createTransaction(transaction: Transaction, signatureData: SignatureData?) {
        async(UI) {
            try {

                async(CommonPool) {

                    val transactionState = TransactionState(needsSigningConfirmation = signatureData == null)

                    appDatabase.transactions.upsert(transaction.toEntity(signatureData, transactionState))

                }.await()

                startActivity(getTransactionActivityIntentForHash(transaction.txHash!!))
                finish()

            } catch (e: Exception) {
                alert("Problem " + e.message)
            }
        }
    }


    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_offline_transaction, menu)
        return super.onCreateOptionsMenu(menu)
    }

    public override fun onActivityResult(requestCode: Int, resultCode: Int, resultData: Intent?) {


        resultData?.let {
            if (it.hasExtra("SCAN_RESULT")) {

                val result = it.getStringExtra("SCAN_RESULT")
                transaction_to_relay_hex.setText(result)
                if (result.isUnsignedTransactionJSON() || result.isParityUnsignedTransactionJSON()) {
                    execute()
                }
            }
        }


    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {

        R.id.menu_scan -> true.also {
            startScanActivityForResult(this)
        }

        android.R.id.home -> true.also {
            finish()
        }

        else -> super.onOptionsItemSelected(item)
    }
}
