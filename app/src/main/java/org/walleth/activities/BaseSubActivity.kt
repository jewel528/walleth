package org.walleth.activities

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.MenuItem
import androidx.annotation.LayoutRes
import kotlinx.android.synthetic.main.activity_base_w_actionbar.*
import kotlinx.android.synthetic.main.toolbar.*
import org.walleth.R

@SuppressLint("Registered")
open class BaseSubActivity : WallethActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        super.setContentView(R.layout.activity_base_w_actionbar)

        setSupportActionBar(toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
        }
    }

    override fun setContentView(@LayoutRes layoutResID: Int) {
        content_frame.removeAllViews()
        layoutInflater.inflate(layoutResID, content_frame)
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        android.R.id.home -> true.also {
            finish()
        }
        else -> super.onOptionsItemSelected(item)
    }
}