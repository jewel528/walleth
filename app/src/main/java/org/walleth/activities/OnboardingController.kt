package org.walleth.activities

import android.app.Activity
import kotlinx.android.synthetic.main.activity_main.*
import org.ligi.tracedroid.TraceDroid
import org.ligi.tracedroid.sending.TraceDroidEmailSender
import org.walleth.R
import org.walleth.data.config.Settings
import org.walleth.viewmodels.TransactionListViewModel
import uk.co.deanwild.materialshowcaseview.IShowcaseListener
import uk.co.deanwild.materialshowcaseview.MaterialShowcaseView

class OnboardingController(val activity: Activity,
                           private val viewModel: TransactionListViewModel,
                           val settings: Settings) {

    private val showcaseView by lazy {
        MaterialShowcaseView.Builder(activity)
                .setTarget(activity.receive_button)
                .setListener(object : IShowcaseListener {
                    override fun onShowcaseDismissed(showcaseView: MaterialShowcaseView?) {
                        dismiss()
                    }

                    override fun onShowcaseDisplayed(showcaseView: MaterialShowcaseView?) = Unit
                })
                .setDismissText(android.R.string.ok)
                .setTargetTouchable(true)
                .setContentText(R.string.onboard_showcase_message)
                .build()
    }

    fun install() {
        if (!settings.onboardingDone) {
            viewModel.isOnboardingVisible.value = true

            showcaseView.show(activity)

            settings.onboardingDone = true
        } else {
            if (TraceDroid.getStackTraceFiles().isNotEmpty()) {
                TraceDroidEmailSender.sendStackTraces("walleth@walleth.org", activity)
            }
        }

    }

    fun dismiss() {
        if (viewModel.isOnboardingVisible.value == true) {
            viewModel.isOnboardingVisible.value = false
            showcaseView.hide()
        }
    }
}