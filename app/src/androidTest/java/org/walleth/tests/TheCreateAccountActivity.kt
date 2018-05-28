package org.walleth.tests

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.support.test.espresso.Espresso.onView
import android.support.test.espresso.action.ViewActions.*
import android.support.test.espresso.assertion.ViewAssertions.matches
import android.support.test.espresso.intent.Intents.intending
import android.support.test.espresso.intent.matcher.IntentMatchers.hasComponent
import android.support.test.espresso.matcher.ViewMatchers.*
import com.google.common.truth.Truth.assertThat
import kotlinx.android.synthetic.main.activity_account_create.*
import org.junit.Rule
import org.junit.Test
import org.kethereum.model.Address
import org.ligi.trulesk.TruleskIntentRule
import org.walleth.R
import org.walleth.activities.CreateAccountActivity
import org.walleth.activities.qrscan.QRScanActivity
import org.walleth.infrastructure.TestApp


class TheCreateAccountActivity {

    @get:Rule
    var rule = TruleskIntentRule(CreateAccountActivity::class.java)

    @Test
    fun rejectsCriticallyLongAddress() {
        // https://github.com/walleth/walleth/issues/146
        val resultIntent = Intent()
        resultIntent.putExtra("SCAN_RESULT", "0x" + "a".repeat(65))
        intending(hasComponent(QRScanActivity::class.java.name)).respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, resultIntent))
        onView(withId(R.id.camera_button)).perform(click())
        onView(withText(R.string.title_invalid_address_alert)).check(matches(isDisplayed()))
    }

    @Test
    fun rejectsInvalidAddress() {

        onView(withId(R.id.fab)).perform(closeSoftKeyboard(), click())

        onView(withText(R.string.alert_problem_title)).check(matches(isDisplayed()))
        onView(withText(R.string.address_not_valid)).check(matches(isDisplayed()))

        rule.screenShot("address_not_valid")
        assertThat(rule.activity.isFinishing).isFalse()
    }


    @Test
    fun rejects_blank_name() {

        onView(withId(R.id.hexInput)).perform(scrollTo(), typeText("0xfdf1210fc262c73d0436236a0e07be419babbbc4"))

        onView(withId(R.id.fab)).perform(closeSoftKeyboard(), click())

        onView(withText(R.string.alert_problem_title)).check(matches(isDisplayed()))
        onView(withText(R.string.please_enter_name)).check(matches(isDisplayed()))

        rule.screenShot("address_not_valid")
        assertThat(rule.activity.isFinishing).isFalse()
    }

    @Test
    fun when_creating_new_address_old_gets_cleared() {

        onView(withId(R.id.new_address_button)).perform(closeSoftKeyboard(), click())

        val firstCreatedAddress = Address(rule.activity.hexInput.text.toString())

        assertThat(firstCreatedAddress.hex).startsWith("0x")

        onView(withId(R.id.new_address_button)).perform(click())

        val secondCreatedAddress = Address(rule.activity.hexInput.text.toString())

        onView(withId(R.id.nameInput)).perform(scrollTo(), typeText("nameProbe"))

        onView(withId(R.id.fab)).perform(closeSoftKeyboard(), click())

        assertThat(TestApp.keyStore.hasKeyForForAddress(firstCreatedAddress)).isFalse()
        assertThat(TestApp.keyStore.hasKeyForForAddress(secondCreatedAddress)).isTrue()
        assertThat(firstCreatedAddress).isNotEqualTo(secondCreatedAddress)
    }

    @Test
    fun savesValidAddress() {

        onView(withId(R.id.hexInput)).perform(scrollTo(), click(), typeText("0xfdf1210fc262c73d0436236a0e07be419babbbc4"))
        onView(withId(R.id.nameInput)).perform(scrollTo(), click(), typeText("nameProbe"))
        onView(withId(R.id.noteInput)).perform(scrollTo(), click(), typeText("noteProbe"))


        rule.screenShot("create")

        onView(withId(R.id.fab)).perform(closeSoftKeyboard(), click())

        val tested = TestApp.testDatabase.addressBook.byAddress(Address("0xfdf1210fc262c73d0436236a0e07be419babbbc4"))

        assertThat(tested).isNotNull()
        assertThat(tested!!.name).isEqualTo("nameProbe")
        assertThat(tested.note).isEqualTo("noteProbe")
        assertThat(rule.activity.isFinishing).isTrue()
    }


}
