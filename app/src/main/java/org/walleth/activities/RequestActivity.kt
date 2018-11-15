package org.walleth.activities

import android.content.Intent
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.view.Menu
import android.view.MenuItem
import kotlinx.android.synthetic.main.activity_request.*
import org.kethereum.erc681.ERC681
import org.kethereum.erc681.generateURL
import org.koin.android.ext.android.inject
import org.ligi.compat.HtmlCompat
import org.ligi.kaxt.setVisibility
import org.walleth.R
import org.walleth.data.config.Settings
import org.walleth.data.exchangerate.ExchangeRateProvider
import org.walleth.data.networks.CurrentAddressProvider
import org.walleth.data.networks.NetworkDefinitionProvider
import org.walleth.data.tokens.CurrentTokenProvider
import org.walleth.data.tokens.isETH
import org.walleth.functions.setQRCode
import org.walleth.ui.valueview.ValueViewController
import org.walleth.util.copyToClipboard
import java.math.BigInteger

class RequestActivity : BaseSubActivity() {

    private lateinit var currentERC67String: String
    private val currentAddressProvider: CurrentAddressProvider by inject()
    private val currentTokenProvider: CurrentTokenProvider by inject()
    private val networkDefinitionProvider: NetworkDefinitionProvider by inject()
    private val exchangeRateProvider: ExchangeRateProvider by inject()
    private val settings: Settings by inject()

    private var valueInputController: ValueViewController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_request)

        supportActionBar?.subtitle = getString(R.string.request_transaction_subtitle)

        refreshQR()

        valueInputController = object : ValueViewController(value_input, exchangeRateProvider, settings) {
            override fun refreshNonValues() {
                super.refreshNonValues()
                refreshQR()
            }
        }.apply {
            setValue(BigInteger.ZERO, currentTokenProvider.currentToken)
        }
        val initText = if (networkDefinitionProvider.getCurrent().faucets.isNotEmpty()) {
            val faucetURL = networkDefinitionProvider.getCurrent()
                    .faucets.first()
                    .replace("%address%", currentAddressProvider.getCurrent().hex)
            getString(R.string.request_faucet_message,
                    networkDefinitionProvider.getCurrent().getNetworkName(),
                    faucetURL)
        } else {
            getString(R.string.no_faucet)
        }
        request_hint.text = HtmlCompat.fromHtml(initText)
        request_hint.movementMethod = LinkMovementMethod()

        add_value_checkbox.setOnCheckedChangeListener { _, isChecked ->
            value_input.setVisibility(isChecked)
            refreshQR()
        }

        receive_qrcode.setOnClickListener {
            startActivity(Intent(this, FullscreenQRCodeActivity::class.java).apply {
                putExtra(KEY_ERC681, currentERC67String)
            })
        }
    }

    private fun refreshQR() {

        val currentToken = currentTokenProvider.currentToken
        if (!add_value_checkbox.isChecked || currentToken.isETH()) {

            val relevantAddress = currentAddressProvider.getCurrent()
            currentERC67String = ERC681(address = relevantAddress.hex).generateURL()

            if (add_value_checkbox.isChecked) {
                try {
                    currentERC67String = ERC681(address = relevantAddress.hex, value = valueInputController?.getValue()).generateURL()
                } catch (e: NumberFormatException) {
                }
            }
        } else {
            val relevantAddress = currentToken.address.hex

            val userAddress = currentAddressProvider.getCurrent().hex
            val functionParams = mutableListOf("address" to userAddress)
            if (add_value_checkbox.isChecked) {
                try {
                    functionParams.add("uint256" to valueInputController?.getValue().toString())
                } catch (e: NumberFormatException) {
                }
            }

            currentERC67String = ERC681(address = relevantAddress, function = "transfer",
                    functionParams = functionParams).generateURL()
        }

        receive_qrcode.setQRCode(currentERC67String)
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_request, menu)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        R.id.menu_share -> true.also {
            val sendIntent = Intent().apply {
                action = Intent.ACTION_SEND
                putExtra(Intent.EXTRA_TEXT, currentERC67String)
                type = "text/plain"
            }

            startActivity(sendIntent)
        }
        R.id.menu_copy -> true.also {
            copyToClipboard(currentERC67String, receive_qrcode)
        }
        else -> super.onOptionsItemSelected(item)
    }
}
