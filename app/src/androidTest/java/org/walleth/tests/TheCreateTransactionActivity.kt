package org.walleth.tests

import android.content.Intent
import android.support.test.espresso.Espresso
import android.support.test.espresso.action.ViewActions
import android.support.test.espresso.assertion.ViewAssertions
import android.support.test.espresso.matcher.ViewMatchers
import com.google.common.truth.Truth
import org.junit.Rule
import org.junit.Test
import org.kethereum.model.Address
import org.ligi.trulesk.TruleskActivityRule
import org.walleth.R
import org.walleth.activities.CreateTransactionActivity
import org.walleth.data.balances.Balance
import org.walleth.infrastructure.TestApp
import java.math.BigInteger

class TheCreateTransactionActivity {

    @get:Rule
    var rule = TruleskActivityRule(CreateTransactionActivity::class.java, autoLaunch = false)

    @Test
    fun rejectsEmptyAddress() {
        rule.launchActivity()
        Espresso.onView(ViewMatchers.withId(R.id.fab)).perform(ViewActions.closeSoftKeyboard(), ViewActions.click())

        Espresso.onView(ViewMatchers.withText(R.string.create_tx_error_address_must_be_specified)).check(ViewAssertions.matches(ViewMatchers.isDisplayed()))

        rule.screenShot("address_empty")
        Truth.assertThat(rule.activity.isFinishing).isFalse()
    }

    @Test
    fun rejectsDifferentChainId() {
        val chainIdForTransaction = TestApp.mySettings.chain + 1
        rule.launchActivity(Intent.getIntentOld("ethereum:0x12345@" + chainIdForTransaction))

        Espresso.onView(ViewMatchers.withText(R.string.wrong_network)).check(ViewAssertions.matches(ViewMatchers.isDisplayed()))
        Espresso.onView(ViewMatchers.withText(rule.activity.getString(R.string.please_switch_network, TestApp.networkDefinitionProvider.getCurrent().getNetworkName(), chainIdForTransaction)))
                .check(ViewAssertions.matches(ViewMatchers.isDisplayed()))

        rule.screenShot("chainId_not_valid")
        Truth.assertThat(rule.activity.isFinishing).isFalse()
    }

    @Test
    fun acceptsDifferentChainId() {
        val chainIdForTransaction = TestApp.networkDefinitionProvider.getCurrent().chain.id
        rule.launchActivity(Intent.getIntentOld("ethereum:0x12345@" + chainIdForTransaction))

        Espresso.onView(ViewMatchers.withText(R.string.wrong_network)).check(ViewAssertions.doesNotExist())
        Espresso.onView(ViewMatchers.withText(rule.activity.getString(R.string.please_switch_network, TestApp.networkDefinitionProvider.getCurrent().getNetworkName(), chainIdForTransaction)))
                .check(ViewAssertions.doesNotExist())

        rule.screenShot("chainId_valid")
        Truth.assertThat(rule.activity.isFinishing).isFalse()
    }

    @Test
    fun acceptsSimpleAddress() {
        rule.launchActivity(Intent.getIntentOld("0x12345"))
        Espresso.onView(ViewMatchers.withId(R.id.to_address)).check(ViewAssertions.matches(ViewMatchers.withText("0x12345")))
    }

    @Test
    fun usesCorrectValuesForETHTransaction() {
        TestApp.testDatabase.balances.upsert(Balance(TestApp.currentAddressProvider.getCurrent(), Address("0x0"), TestApp.networkDefinitionProvider.getCurrent().chain, 1L, BigInteger.TEN * BigInteger("1" + "0".repeat(18))))
        rule.launchActivity(Intent.getIntentOld("ethereum:0x123456?value=1"))

        Espresso.onView(ViewMatchers.withId(R.id.fab)).perform(ViewActions.closeSoftKeyboard(), ViewActions.click())

        val allTransactionsForAddress = TestApp.testDatabase.transactions.getAllTransactionsForAddress(listOf(Address("0x123456")))
        Truth.assertThat(allTransactionsForAddress).hasSize(1)
        Truth.assertThat(allTransactionsForAddress.get(0).transaction.to?.hex).isEqualTo("0x123456")
    }
}